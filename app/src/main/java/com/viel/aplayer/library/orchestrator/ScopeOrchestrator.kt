package com.viel.aplayer.library.orchestrator

import android.content.Context
import android.util.Log
import androidx.media3.common.util.UnstableApi
import com.viel.aplayer.data.db.AppDatabase
import com.viel.aplayer.data.entity.BookEntity
import com.viel.aplayer.data.entity.DirectoryCacheEntity
import com.viel.aplayer.data.entity.LibraryRootEntity
import com.viel.aplayer.library.FileInventory
import com.viel.aplayer.library.SourceInventoryScanner
import com.viel.aplayer.library.orchestrator.draftmodels.ImportCommand
import com.viel.aplayer.library.orchestrator.draftmodels.ImportFailure
import com.viel.aplayer.library.orchestrator.draftmodels.ImportRunResult
import com.viel.aplayer.library.vfs.VfsFileInterface
import com.viel.aplayer.logger.ImportTimingLogger
import kotlinx.coroutines.CancellationException

/**
 * 全局扫描会话调度引擎工位（ScopeOrchestrator）。
 * 
 * 本类是由原 RescanCoordinator 中拆分抽离出来的增量缓存与 Scope 调度核心。
 * 它的核心职责是：
 * 1. 驱动 SourceInventoryScanner 流式获取设备物理目录树。
 * 2. 对每个物理目录执行 Room DAO directoryCache 的 lastModified 时间戳进行增量缓存校验，
 *    一旦缓存命中，毫秒级快速跳过无变动目录的元数据分析，极大提升了重扫速度。
 * 3. 驱动 ImportScopeBuilder 增量构建 Scope，并在 Scope 层面按规则有序路由给 pipeline 或是 DirectoryAudioImporter。
 * 4. 驱动 BookImporter 事务入库，并在入库成功后即时刷新 claim 认领快照以支持下一批 Scope。
 * 
 * 这种彻底的职责物理分拆实现了“单目录的物理/元数据解析能力（DirectoryAudioImporter）”
 * 与“全库全局增量与 Scope 生命周期协调（ScopeOrchestrator）”的架构级解耦，完全消除了原上帝类的复杂度。
 */
@UnstableApi
internal class ScopeOrchestrator(
    private val context: Context,
    private val scanner: SourceInventoryScanner,
    private val pipeline: ImportPipeline,
    private val directoryAudioImporter: DirectoryAudioImporter,
    private val importer: BookImporter,
    private val triggerCoverRegeneration: (BookEntity) -> Unit
) {

    private val database = AppDatabase.getInstance(context)
    private val bookDao = database.bookDao()
    private val directoryCacheDao = database.directoryCacheDao()

    /**
     * 执行全局扫描的 Scope 流式编排与导入，对应原 RescanCoordinator.runImportByScope 方法。
     * 本方法对扫描会话的 roots 物理目录流进行消费，协调缓存判断、Scope 逐一解析落库，并平摊返回整轮扫描的总运行结果。
     */
    suspend fun execute(
        scanId: String,
        roots: List<LibraryRootEntity>,
        initialClaimIndex: ExistingClaimIndex,
        type: RescanType
    ): ImportRunResult {
        // 记录整轮扫描导入耗时，让 Logcat 可以从 scan.total 一眼看到本轮端到端成本。
        val scanImportStartedAt = ImportTimingLogger.mark()
        ImportTimingLogger.logEvent(
            scopeId = "scan:$scanId",
            stage = "scan.start",
            detail = "type=$type roots=${roots.size}"
        )
        // ImportScopeBuilder 按扫描会话创建，避免有状态的目录缓存跨扫描复用污染后续结果。
        // 构建会话级唯一的 VFS 读取门面快照，向下层步骤统一注入以避免重复构造
        val scopeVfs = VfsFileInterface(context.applicationContext, rootsById = roots.associateBy { it.id })
        val scopeBuilder = ImportScopeBuilder(context, scopeVfs)
        val scopeResults = mutableListOf<ImportRunResult>()
        // 全局扫描账本跨 scope 保留本轮 claim 结果，防止 pending/partial 或刚入库 scope 的音频被后续启发式重复认领。
        val scanClaimLedger = RunClaimLedger()
        // 每个 scope 成功入库后立即刷新已有文件认领索引，让后续 scope 能够看到前面刚刚写入的 BookFile 所有权。
        var currentExistingIndex = initialClaimIndex

        // 本地挂起函数复用 scope 入库逻辑，既处理扫描过程中释放的 scope，也处理 finish() 的兜底 scope。
        suspend fun applyScopes(importScopes: List<ImportScope>) {
            importScopes.forEach { scope ->
                if (scope.kind == ImportScopeKind.DIRECTORY_AUDIO) {
                    // 目录剩余音频先按元数据章节分流，有章节音频即时入库，无章节音频继续进入启发式聚合批次。
                    val subBatchResults = directoryAudioImporter.import(
                        scope = scope,
                        existingClaimIndex = currentExistingIndex,
                        claimLedger = scanClaimLedger,
                        scanId = scanId
                    )
                    // 把子批次的入库结果全部收集起来，用于最终扫描汇总展示
                    scopeResults.addAll(subBatchResults.map { it.result })

                    // 如果有子批次入库成功，并且有新建/刷新/挂起记录，则刷新 existing claim index
                    val anySuccessApplied = subBatchResults.any { 
                        it.success && (it.result.readyImports.isNotEmpty() || it.result.refreshedBooks.isNotEmpty() || it.result.pendingActions.isNotEmpty()) 
                    }
                    if (anySuccessApplied) {
                        currentExistingIndex = ImportTimingLogger.measure(
                            scopeId = scope.timingScopeId(),
                            stage = "db.refreshClaimIndex"
                        ) {
                            ExistingClaimIndex.from(bookDao.getAllBookFilesOnce())
                        }
                    }
                    return@forEach
                }

                val scopeLedger = scanClaimLedger.fork()
                val timingScopeId = scope.timingScopeId()
                val scopeStartedAt = ImportTimingLogger.mark()
                val scopeResult = runCatching {
                    pipeline.run(
                        scanId = scanId,
                        inventory = scope.inventory,
                        existingClaimIndex = currentExistingIndex,
                        runClaimLedger = scopeLedger,
                        timingScopeId = timingScopeId
                    )
                }.getOrElse { throwable ->
                    if (throwable is CancellationException) throw throwable
                    // 单个 scope 解析失败不再中断整个扫描，失败会进入最终汇总，其他 scope 继续导入。
                    ImportTimingLogger.logDuration(
                        scopeId = timingScopeId,
                        stage = "scope.failed",
                        elapsedMs = ImportTimingLogger.elapsedMs(scopeStartedAt),
                        detail = "phase=orchestrator ${scope.inventory.timingCountDetail()}"
                    )
                    scopeResults.add(scope.toScopeFailure(scanId, "导入 scope 解析失败", throwable))
                    return@forEach
                }

                val appliedResult = runCatching {
                    ImportTimingLogger.measure(
                        scopeId = timingScopeId,
                        stage = "db.apply",
                        detail = scopeResult.timingCommandDetail()
                    ) {
                        importer.applyImportRun(scopeResult)
                    }
                    scopeResult
                }.getOrElse { throwable ->
                    if (throwable is CancellationException) throw throwable
                    // scope 入库使用 BookImporter 内部事务，失败时该 scope 不会产生半截数据，并把入库错误纳入最终扫描汇总。
                    scope.toScopeFailure(
                        scanId = scanId,
                        message = "导入 scope 入库失败",
                        throwable = throwable,
                        inheritedFailures = scopeResult.failures
                    )
                }

                scopeResults.add(appliedResult)
                // 只有入库成功的 scope 才提交本轮 claim 账本，避免失败 scope 的预留污染后续候选裁决。
                if (appliedResult === scopeResult) {
                    scanClaimLedger.commitFrom(scopeLedger)
                    // 普通 manifest/启发式 scope 入库成功后同样只派发异步封面重建，不再把封面解码放在导入事务前阻塞。
                    appliedResult.triggerCoverRegenerationForReadyBooks()
                }

                // 只要 scope 入库成功，就重新读取 BookFile 快照，保证后续 scope 的冲突判断包含本轮已写入的数据。
                if (appliedResult.readyImports.isNotEmpty() ||
                    appliedResult.refreshedBooks.isNotEmpty() ||
                    appliedResult.pendingActions.isNotEmpty()
                ) {
                    currentExistingIndex = ImportTimingLogger.measure(
                        scopeId = timingScopeId,
                        stage = "db.refreshClaimIndex"
                    ) {
                        ExistingClaimIndex.from(bookDao.getAllBookFilesOnce())
                    }
                }

                ImportTimingLogger.logDuration(
                    scopeId = timingScopeId,
                    stage = "scope.total",
                    elapsedMs = ImportTimingLogger.elapsedMs(scopeStartedAt),
                    detail = appliedResult.timingCommandDetail()
                )
            }
        }

        scanner.scanDirectories(roots).collect { directory ->
            // 执行增量秒级扫描自愈与物理拦截跳过逻辑。
            // 当且仅当该文件夹在数据库中存在 lastModified 缓存，且其值等于当前物理文件夹的修改时间，
            // 且该文件夹旗下的所有音频文件早已被数据库导入占用（在动态已导入内存索引 currentExistingIndex 中全量存在）时，
            // 证明其物理结构没有任何改动。此时直接跳过整个目录后续的一切物理文件分析、CUE/M3U8 认领与深度 ID3 元数据提取，实现毫秒级“零开销”增量重扫！
            val cachedDir = directoryCacheDao.getBySourcePath(directory.root.id, directory.sourcePath)
            val isCacheValid = cachedDir != null && 
                               cachedDir.lastModified == directory.lastModified && 
                               directory.audioFiles.all { currentExistingIndex.has(it.identity) }
            
            if (isCacheValid) {
                ImportTimingLogger.logEvent(
                    scopeId = "directory:${directory.root.id}:${directory.sourcePath}",
                    stage = "scan.skipByCache",
                    detail = "sourcePath=${directory.sourcePath.ifBlank { "<root>" }} files=${directory.audioFiles.size} - 缓存完全命中，快速跳过物理文件分析与元数据读取"
                )
                return@collect
            }

            // 冷启动轻量扫描在 DirectoryInventory 层过滤已认领文件，保持旧版 onlyUnclaimed 语义并减少后续 scope 噪声。
            val importDirectory = if (type == RescanType.COLD_START_LIGHT) {
                directory.onlyUnclaimed(currentExistingIndex)
            } else {
                directory
            }
            val scopes = scopeBuilder.onDirectoryClosed(importDirectory)
            ImportTimingLogger.logEvent(
                scopeId = "directory:${importDirectory.root.id}:${importDirectory.sourcePath}",
                stage = "scope.build",
                detail = "sourcePath=${importDirectory.sourcePath.ifBlank { "<root>" }} scopes=${scopes.size} cue=${importDirectory.cueFiles.size} m3u8=${importDirectory.m3u8Files.size} audio=${importDirectory.audioFiles.size}"
            )
            applyScopes(scopes)

            // 该文件夹从未被跳过且经过 scopes 解析、导入流程闭合后，
            // 自动将其当前的物理 lastModified 时间戳与 rootId 持久化更新存储到数据库缓存中，为下次增量重扫建立加速基线，实现物理缓存生命周期闭环。
            try {
                directoryCacheDao.insert(
                    DirectoryCacheEntity(
                        cacheKey = "${directory.root.id}:${directory.sourcePath}",
                        sourcePath = directory.sourcePath,
                        lastModified = directory.lastModified,
                        rootId = directory.root.id
                    )
                )
            } catch (e: Exception) {
                Log.e("ScopeOrchestrator", "Failed to cache directory lastModified for ${directory.root.id}:${directory.sourcePath}", e)
            }
        }
        // 扫描流结束后调用 finish，当前目录级策略通常为空，后续跨目录策略仍可在这里收尾。
        applyScopes(scopeBuilder.finish())

        // scope 结果只改变导入决策的作用域，不改变对外扫描会话语义，因此这里合并回一个 ImportRunResult 交给原汇总逻辑。
        val finalResult = ImportRunResult(
            scanId = scanId,
            readyImports = scopeResults.flatMap { it.readyImports },
            refreshedBooks = scopeResults.flatMap { it.refreshedBooks },
            pendingActions = scopeResults.flatMap { it.pendingActions },
            failures = scopeResults.flatMap { it.failures }
        )
        ImportTimingLogger.logDuration(
            scopeId = "scan:$scanId",
            stage = "scan.total",
            elapsedMs = ImportTimingLogger.elapsedMs(scanImportStartedAt),
            detail = finalResult.timingCommandDetail()
        )
        return finalResult
    }

    // 把 scope 级异常转换成 ImportRunResult，使最终 ScanResultDialog 能汇总展示失败 scope 而不吞掉错误。
    private fun ImportScope.toScopeFailure(
        scanId: String,
        message: String,
        throwable: Throwable,
        inheritedFailures: List<ImportCommand.RecordFailure> = emptyList()
    ): ImportRunResult =
        ImportRunResult(
            scanId = scanId,
            readyImports = emptyList(),
            refreshedBooks = emptyList(),
            pendingActions = emptyList(),
            failures = inheritedFailures + ImportCommand.RecordFailure(
                ImportFailure(
                    sourceUri = displayUri(),
                    message = message,
                    throwableMessage = throwable.localizedMessage ?: throwable.message
                )
            )
        )

    // 为 scope 失败摘要挑一个稳定可读的 VFS 来源标识，不再使用 provider URI。
    private fun ImportScope.displayUri(): String =
        inventory.cueFiles.firstOrNull()?.let { "${it.rootId}:${it.sourcePath}" }
            ?: inventory.m3u8Files.firstOrNull()?.let { "${it.rootId}:${it.sourcePath}" }
            ?: inventory.audioFiles.firstOrNull()?.parentSourceKey
            ?: inventory.imageFilesByParent.keys.firstOrNull()
            ?: inventory.roots.firstOrNull()?.sourceUri
            ?: id

    // 为性能日志生成稳定 scope 名称，保留 scope 类型和可读来源，便于从 Logcat 反查慢目录或慢清单。
    private fun ImportScope.timingScopeId(): String = "${kind.name}:${displayUri()}"

    // 统一输出 scope 的文件规模，让同一阶段耗时可以和输入规模一起分析。
    private fun FileInventory.timingCountDetail(): String =
        "cue=${cueFiles.size} m3u8=${m3u8Files.size} audio=${audioFiles.size} imageParents=${imageFilesByParent.size}"

    // 统一输出入库命令规模，判断耗时到底来自新书、刷新、待处理项还是失败记录。
    private fun ImportRunResult.timingCommandDetail(): String =
        "ready=${readyImports.size} refreshed=${refreshedBooks.size} pending=${pendingActions.size} failures=${failures.size}"

    // 只对本次新建成功的 READY 书籍触发封面重建；刷新已有书和 pending/conflict 不在这里处理，避免误触发用户尚未确认的候选项。
    private fun ImportRunResult.triggerCoverRegenerationForReadyBooks() {
        readyImports.forEach { command ->
            triggerCoverRegeneration(command.draft.book)
        }
    }
}
