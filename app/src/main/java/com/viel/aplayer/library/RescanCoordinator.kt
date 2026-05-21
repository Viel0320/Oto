package com.viel.aplayer.library

import android.content.Context
import android.net.Uri
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import com.viel.aplayer.data.db.AppDatabase
import com.viel.aplayer.data.db.AudiobookSchema
import com.viel.aplayer.data.entity.BookEntity
import com.viel.aplayer.data.entity.LibraryRootEntity
import com.viel.aplayer.data.entity.ScanSessionEntity
import com.viel.aplayer.media.parse.MetadataExtractor
import androidx.core.net.toUri

enum class RescanType {
    COLD_START_LIGHT,
    USER_GLOBAL,
    NEW_LIBRARY_ROOT
}

// 详尽的中文注释：有章节音频按有界 I/O 并发规模成批入库，让封面解析和数据库写入都从“单文件循环”变成“小批量流水”，同时避免一次塞入过多大文件。
private val CHAPTERED_AUDIO_IMPORT_BATCH_SIZE: Int = DEFAULT_SCOPE_IO_CONCURRENCY

// 详尽的中文注释：目录音频元数据也按同样的小批次被消费，第一批 metadata 读完即可进入导入，而不是等完整目录全部读完。
private val DIRECTORY_AUDIO_METADATA_BATCH_SIZE: Int = DEFAULT_SCOPE_IO_CONCURRENCY

// Rescan entrypoint: creates a session, runs scanner/import orchestration, then applies results.
class RescanCoordinator(
    private val context: Context,
    // 详尽的中文注释：导入链路只负责先把书和章节落库；封面缓存重建通过外部注入的异步回调复用 Repository 里已有的 CoverRecoveryHelper 去重与后台执行能力。
    private val triggerCoverRegeneration: (BookEntity) -> Unit = {}
) {
    private val database = AppDatabase.getInstance(context)
    private val rootDao = database.libraryRootDao()
    private val bookDao = database.bookDao()
    private val scanSessionDao = database.scanSessionDao()
    // 为每一次改动添加详尽的中文注释：声明增量扫描目录缓存表的 DAO 引用
    private val directoryCacheDao = database.directoryCacheDao()
    private val scanner = FileInventoryScanner(context)
    private val orchestrator = ImportOrchestrator(context)
    private val importer = BookImporter(context)
    private val missingRecoveryChecker = MissingBookFileRecoveryChecker(context)
    // 详尽的中文注释：目录剩余音频需要先读取元数据来识别“自带章节”的可提前入库音频，避免整个大目录被启发式批处理阻塞。
    private val metadataExtractor = MetadataExtractor(context)

    suspend fun rescan(type: RescanType, rootId: String? = null): ScanSessionEntity = withContext(Dispatchers.IO) {
        val scanId = UUID.randomUUID().toString()
        val trigger = when (type) {
            RescanType.COLD_START_LIGHT -> AudiobookSchema.ScanTrigger.COLD_START
            RescanType.USER_GLOBAL -> AudiobookSchema.ScanTrigger.USER
            RescanType.NEW_LIBRARY_ROOT -> AudiobookSchema.ScanTrigger.ADD_LIBRARY_ROOT
        }
        val session = ScanSessionEntity(
            id = scanId,
            trigger = trigger,
            status = AudiobookSchema.ScanStatus.RUNNING,
            startedAt = System.currentTimeMillis()
        )
        // Each scan rebuilds the pending queue from scratch, so old actions cannot trigger this run's dialog.
        scanSessionDao.clearPendingActions()
        scanSessionDao.insertSession(session)

        val result = runCatching {
            val roots = when (type) {
                RescanType.NEW_LIBRARY_ROOT -> rootId?.let { rootDao.getRootById(it) }?.let(::listOf).orEmpty()
                else -> rootDao.getActiveRootsOnce()
            }
            val existingIndex = ExistingClaimIndex.from(bookDao.getAllBookFilesOnce())
            // 详尽的中文注释：主导入链路从全量 scanner.scan() 切换到 DirectoryClosed 流，由 ImportScopeBuilder 增量释放 claim-safe scope。
            runImportByScope(scanId, roots, existingIndex, type)
        }

        result.onSuccess { importResult ->
            // 详尽的中文注释：ImportScope 已经在 runImportByScope 内逐个即时入库，这里只保留最终恢复检查、根目录状态刷新和扫描汇总展示。
            val recoveryResult = if (type == RescanType.COLD_START_LIGHT) {
                // Cold-start still imports only unclaimed files; this extra pass only restores missing BookFile rows.
                missingRecoveryChecker.recoverMissingAudioFiles()
            } else {
                MissingBookFileRecoveryResult()
            }
            importResult.readyImports.map { it.draft.book.rootId }.distinct().forEach { scannedRootId ->
                rootDao.updateRootScanState(scannedRootId, System.currentTimeMillis())
            }
            scanSessionDao.markCompleted(
                id = scanId,
                // These counts are derived from the actual ImportRunResult applied above.
                discoveredBookCount = importResult.discoveredCount,
                unavailableBookCount = importResult.failureCount,
                partialBookCount = importResult.partialNewBookCount,
                updatedBookCount = importResult.updateExistingCount + recoveryResult.restoredBookCount,
                pendingActionCount = importResult.pendingActions.size,
                summaryJson = importResult.toSummaryJson(recoveryResult)
            )
        }

        result.onFailure {
            scanSessionDao.markAbandoned(scanId)
        }

        scanSessionDao.getSessionById(scanId) ?: session
    }

    // 详尽的中文注释：按 claim-safe scope 逐片执行导入编排，并把各 scope 产出的命令合并成同一个扫描会话的 ImportRunResult。
    private suspend fun runImportByScope(
        scanId: String,
        roots: List<LibraryRootEntity>,
        existingIndex: ExistingClaimIndex,
        type: RescanType
    ): ImportRunResult {
        // 详尽的中文注释：记录整轮扫描导入耗时，让 Logcat 可以从 scan.total 一眼看到本轮端到端成本。
        val scanImportStartedAt = ImportTimingLogger.mark()
        ImportTimingLogger.logEvent(
            scopeId = "scan:$scanId",
            stage = "scan.start",
            detail = "type=$type roots=${roots.size}"
        )
        // 详尽的中文注释：ImportScopeBuilder 按扫描会话创建，避免有状态的目录缓存跨扫描复用污染后续结果。
        val scopeBuilder = ImportScopeBuilder(context)
        val scopeResults = mutableListOf<ImportRunResult>()
        // 详尽的中文注释：全局扫描账本跨 scope 保留本轮 claim 结果，防止 pending/partial 或刚入库 scope 的音频被后续启发式重复认领。
        val scanClaimLedger = RunClaimLedger()
        // 详尽的中文注释：每个 scope 成功入库后立即刷新已有文件认领索引，让后续 scope 能够看到前面刚刚写入的 BookFile 所有权。
        var currentExistingIndex = existingIndex

        suspend fun applyResolvedDirectoryAudio(
            scope: ImportScope,
            refs: List<AudioMetadataRef>,
            failureMessage: String,
            timingSuffix: String
        ) {
            if (refs.isEmpty()) return
            val timingScopeId = "${scope.timingScopeId()}#$timingSuffix"
            val batchStartedAt = ImportTimingLogger.mark()
            val scopedInventory = scope.inventory.withAudioFiles(refs.map { it.file })
            val scopeLedger = scanClaimLedger.fork()
            val scopeResult = runCatching {
                orchestrator.runResolvedDirectoryAudio(
                    scanId = scanId,
                    inventory = scopedInventory,
                    existingClaimIndex = currentExistingIndex,
                    runClaimLedger = scopeLedger,
                    looseAudioMetadataRefs = refs,
                    timingScopeId = timingScopeId
                )
            }.getOrElse { throwable ->
                if (throwable is CancellationException) throw throwable
                // 详尽的中文注释：目录内子批次失败只记录到最终汇总，不阻塞同目录内其他已识别音频继续导入。
                ImportTimingLogger.logDuration(
                    scopeId = timingScopeId,
                    stage = "scope.failed",
                    elapsedMs = ImportTimingLogger.elapsedMs(batchStartedAt),
                    detail = "phase=orchestrator files=${refs.size}"
                )
                scopeResults.add(scope.toScopeFailure(scanId, failureMessage, throwable))
                return
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
                // 详尽的中文注释：目录内子批次入库仍由 BookImporter 事务保护，失败时不会产生半截书籍数据。
                scope.toScopeFailure(
                    scanId = scanId,
                    message = "目录音频子批次入库失败",
                    throwable = throwable,
                    inheritedFailures = scopeResult.failures
                )
            }

            scopeResults.add(appliedResult)
            // 详尽的中文注释：只有成功入库的子批次才提交 claim，防止失败音频把后续启发式批次错误占住。
            if (appliedResult === scopeResult) {
                scanClaimLedger.commitFrom(scopeLedger)
                // 详尽的中文注释：子批次成功入库后立刻异步调度封面重建，让书架先显示新书，再由 Room Flow 在封面补齐后刷新图片缓存时间戳。
                appliedResult.triggerCoverRegenerationForReadyBooks()
            }

            // 详尽的中文注释：子批次入库后立即刷新 DB claim 快照，后面的同目录批次也能看到刚写入的 BookFile。
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
                elapsedMs = ImportTimingLogger.elapsedMs(batchStartedAt),
                detail = "files=${refs.size} ${appliedResult.timingCommandDetail()}"
            )
        }

        suspend fun applyDirectoryAudioScope(scope: ImportScope) {
            val timingScopeId = scope.timingScopeId()
            val heuristicRefs = mutableListOf<AudioMetadataRef>()
            val candidateAudios = scope.inventory.audioFiles
                .filterNot { currentExistingIndex.has(it.identity) }
            val metadataStartedAt = ImportTimingLogger.mark()
            coroutineScope {
                val metadataSemaphore = Semaphore(DEFAULT_SCOPE_IO_CONCURRENCY)
                // 详尽的中文注释：所有 metadata 任务一次性启动并用 semaphore 限并发，后续批次会在当前批次导入时继续读取，避免小批次流式入库拖慢总吞吐。
                val metadataJobs = candidateAudios.map { audio ->
                    async {
                        metadataSemaphore.withPermit {
                            AudioMetadataRef(audio, metadataExtractor.extract(audio.uri.toUri()))
                        }
                    }
                }
                // 详尽的中文注释：单独的汇总协程记录“全目录 metadata 何时全部完成”，即使前台已经开始入库，也能保持日志口径只反映 metadata wall time。
                val metadataSummaryJob = async {
                    val allRefs = metadataJobs.awaitAll()
                    val chapteredCount = allRefs.count { it.metadata.chapters.isNotEmpty() }
                    ImportTimingLogger.logDuration(
                        scopeId = timingScopeId,
                        stage = "directoryAudio.metadataResolve",
                        elapsedMs = ImportTimingLogger.elapsedMs(metadataStartedAt),
                        detail = "input=${scope.inventory.audioFiles.size} resolved=${allRefs.size} chaptered=$chapteredCount heuristic=${allRefs.size - chapteredCount} batches=${metadataJobs.chunked(DIRECTORY_AUDIO_METADATA_BATCH_SIZE).size}"
                    )
                }

                val metadataBatches = metadataJobs.chunked(DIRECTORY_AUDIO_METADATA_BATCH_SIZE)
                metadataBatches.forEachIndexed { index, batchJobs ->
                    val batchMetadataStartedAt = ImportTimingLogger.mark()
                    val batchRefs = batchJobs.awaitAll()
                    val chapteredRefs = mutableListOf<AudioMetadataRef>()
                    batchRefs.forEach { audioRef ->
                        if (currentExistingIndex.has(audioRef.file.identity)) return@forEach
                        if (audioRef.metadata.chapters.isNotEmpty()) {
                            // 详尽的中文注释：当前 metadata 小批次里确认有章节的音频马上进入导入队列，不再等待同目录剩余文件完成元数据读取。
                            chapteredRefs.add(audioRef)
                        } else {
                            // 详尽的中文注释：无章节音频仍累计到目录级启发式窗口，保持多文件书聚合所需的完整同目录上下文。
                            heuristicRefs.add(audioRef)
                        }
                    }
                    ImportTimingLogger.logDuration(
                        scopeId = timingScopeId,
                        stage = "directoryAudio.metadataResolveBatch",
                        elapsedMs = ImportTimingLogger.elapsedMs(batchMetadataStartedAt),
                        detail = "batch=${index + 1}/${metadataBatches.size} input=${batchJobs.size} resolved=${batchRefs.size} chaptered=${chapteredRefs.size} heuristic=${batchRefs.size - chapteredRefs.size}"
                    )

                    // 详尽的中文注释：每个 metadata 小批次最多产生一个章节音频导入批次，保持稳定顺序，并让书架尽快看到第一批有章节书。
                    chapteredRefs.chunked(CHAPTERED_AUDIO_IMPORT_BATCH_SIZE).forEachIndexed { chunkIndex, batchRefs ->
                        applyResolvedDirectoryAudio(
                            scope = scope,
                            refs = batchRefs,
                            failureMessage = "有章节音频批量提前导入失败",
                            timingSuffix = "chaptered-batch:${index + 1}/${metadataBatches.size}.${chunkIndex + 1}:files=${batchRefs.size}"
                        )
                    }
                }
                metadataSummaryJob.await()
            }

            // 详尽的中文注释：同目录所有无章节音频在完整扫描后统一交给启发式聚合，避免普通多文件书被文件级流式拆散。
            applyResolvedDirectoryAudio(
                scope = scope,
                refs = heuristicRefs,
                failureMessage = "无章节音频启发式导入失败",
                timingSuffix = "heuristic"
            )
        }

        // 详尽的中文注释：本地挂起函数复用 scope 入库逻辑，既处理扫描过程中释放的 scope，也处理 finish() 的兜底 scope。
        suspend fun applyScopes(importScopes: List<ImportScope>) {
            importScopes.forEach { scope ->
                if (scope.kind == ImportScopeKind.DIRECTORY_AUDIO) {
                    // 详尽的中文注释：目录剩余音频先按元数据章节分流，有章节音频即时入库，无章节音频继续进入启发式聚合批次。
                    applyDirectoryAudioScope(scope)
                    return@forEach
                }

                val scopeLedger = scanClaimLedger.fork()
                val timingScopeId = scope.timingScopeId()
                val scopeStartedAt = ImportTimingLogger.mark()
                val scopeResult = runCatching {
                    orchestrator.run(
                        scanId = scanId,
                        inventory = scope.inventory,
                        existingClaimIndex = currentExistingIndex,
                        runClaimLedger = scopeLedger,
                        timingScopeId = timingScopeId
                    )
                }.getOrElse { throwable ->
                    if (throwable is CancellationException) throw throwable
                    // 详尽的中文注释：单个 scope 解析失败不再中断整个扫描，失败会进入最终汇总，其他 scope 继续导入。
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
                    // 详尽的中文注释：scope 入库使用 BookImporter 内部事务，失败时该 scope 不会产生半截数据，并把入库错误纳入最终扫描汇总。
                    scope.toScopeFailure(
                        scanId = scanId,
                        message = "导入 scope 入库失败",
                        throwable = throwable,
                        inheritedFailures = scopeResult.failures
                    )
                }

                scopeResults.add(appliedResult)
                // 详尽的中文注释：只有入库成功的 scope 才提交本轮 claim 账本，避免失败 scope 的预留污染后续候选裁决。
                if (appliedResult === scopeResult) {
                    scanClaimLedger.commitFrom(scopeLedger)
                    // 详尽的中文注释：普通 manifest/启发式 scope 入库成功后同样只派发异步封面重建，不再把封面解码放在导入事务前阻塞。
                    appliedResult.triggerCoverRegenerationForReadyBooks()
                }

                // 详尽的中文注释：只要 scope 入库成功，就重新读取 BookFile 快照，保证后续 scope 的冲突判断包含本轮已写入的数据。
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
            // 为每一次改动添加详尽的中文注释：执行增量秒级扫描自愈与物理拦截跳过逻辑。
            // 当且仅当该文件夹在数据库中存在 lastModified 缓存，且其值等于当前物理文件夹的修改时间，
            // 且该文件夹旗下的所有音频文件早已被数据库导入占用（在动态已导入内存索引 currentExistingIndex 中全量存在）时，
            // 证明其物理结构没有任何改动。此时直接跳过整个目录后续的一切物理文件分析、CUE/M3U8 认领与深度 ID3 元数据提取，实现毫秒级“零开销”增量重扫！
            val cachedDir = directoryCacheDao.getByUri(directory.directoryUri)
            val isCacheValid = cachedDir != null && 
                               cachedDir.lastModified == directory.lastModified && 
                               directory.audioFiles.all { currentExistingIndex.has(it.identity) }
            
            if (isCacheValid) {
                ImportTimingLogger.logEvent(
                    scopeId = "directory:${directory.directoryUri}",
                    stage = "scan.skipByCache",
                    detail = "relativePath=${directory.relativePath.ifBlank { "<root>" }} files=${directory.audioFiles.size} - 缓存完全命中，快速跳过物理文件分析与元数据读取"
                )
                return@collect
            }

            // 详尽的中文注释：冷启动轻量扫描在 DirectoryInventory 层过滤已认领文件，保持旧版 onlyUnclaimed 语义并减少后续 scope 噪声。
            val importDirectory = if (type == RescanType.COLD_START_LIGHT) {
                directory.onlyUnclaimed(currentExistingIndex)
            } else {
                directory
            }
            val scopes = scopeBuilder.onDirectoryClosed(importDirectory)
            ImportTimingLogger.logEvent(
                scopeId = "directory:${importDirectory.directoryUri}",
                stage = "scope.build",
                detail = "relativePath=${importDirectory.relativePath.ifBlank { "<root>" }} scopes=${scopes.size} cue=${importDirectory.cueFiles.size} m3u8=${importDirectory.m3u8Files.size} audio=${importDirectory.audioFiles.size}"
            )
            applyScopes(scopes)

            // 为每一次改动添加详尽的中文注释：该文件夹从未被跳过且经过 scopes 解析、导入流程闭合后，
            // 自动将其当前的物理 lastModified 时间戳与 rootId 持久化更新存储到数据库缓存中，为下次增量重扫建立加速基线，实现物理缓存生命周期闭环。
            try {
                directoryCacheDao.insert(
                    com.viel.aplayer.data.entity.DirectoryCacheEntity(
                        directoryUri = directory.directoryUri,
                        lastModified = directory.lastModified,
                        rootId = directory.root.id
                    )
                )
            } catch (e: Exception) {
                android.util.Log.e("RescanCoordinator", "Failed to cache directory lastModified for ${directory.directoryUri}", e)
            }
        }
        // 详尽的中文注释：扫描流结束后调用 finish，当前目录级策略通常为空，后续跨目录策略仍可在这里收尾。
        applyScopes(scopeBuilder.finish())

        // 详尽的中文注释：scope 结果只改变导入决策的作用域，不改变对外扫描会话语义，因此这里合并回一个 ImportRunResult 交给原汇总逻辑。
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

    // 详尽的中文注释：把 scope 级异常转换成 ImportRunResult，使最终 ScanResultDialog 能汇总展示失败 scope 而不吞掉错误。
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

    // 详尽的中文注释：为 scope 失败摘要挑一个稳定可读的来源 Uri，优先使用清单或音频的实际物理位置。
    private fun ImportScope.displayUri(): String =
        inventory.cueFiles.firstOrNull()?.uri
            ?: inventory.m3u8Files.firstOrNull()?.uri
            ?: inventory.audioFiles.firstOrNull()?.parentUri
            ?: inventory.imageFilesByParent.keys.firstOrNull()
            ?: inventory.roots.firstOrNull()?.treeUri
            ?: id

    // 详尽的中文注释：为性能日志生成稳定 scope 名称，保留 scope 类型和可读来源，便于从 Logcat 反查慢目录或慢清单。
    private fun ImportScope.timingScopeId(): String = "${kind.name}:${displayUri()}"

    // 详尽的中文注释：统一输出 scope 的文件规模，让同一阶段耗时可以和输入规模一起分析。
    private fun FileInventory.timingCountDetail(): String =
        "cue=${cueFiles.size} m3u8=${m3u8Files.size} audio=${audioFiles.size} imageParents=${imageFilesByParent.size}"

    // 详尽的中文注释：统一输出入库命令规模，判断耗时到底来自新书、刷新、待处理项还是失败记录。
    private fun ImportRunResult.timingCommandDetail(): String =
        "ready=${readyImports.size} refreshed=${refreshedBooks.size} pending=${pendingActions.size} failures=${failures.size}"

    // 详尽的中文注释：只对本次新建成功的 READY 书籍触发封面重建；刷新已有书和 pending/conflict 不在这里处理，避免误触发用户尚未确认的候选项。
    private fun ImportRunResult.triggerCoverRegenerationForReadyBooks() {
        readyImports.forEach { command ->
            triggerCoverRegeneration(command.draft.book)
        }
    }

    // 详尽的中文注释：目录音频子批次只携带参与本次裁决的音频，但保留对应父目录图片，保证封面 sidecar 仍可匹配。
    private fun FileInventory.withAudioFiles(audioFiles: List<FileRef>): FileInventory {
        val parentUris = audioFiles.map { it.parentUri }.toSet()
        val rootIds = audioFiles.map { it.rootId }.toSet()
        return FileInventory(
            roots = roots.filter { it.id in rootIds }.ifEmpty { roots },
            cueFiles = emptyList(),
            m3u8Files = emptyList(),
            audioFiles = audioFiles.sortedByStableFileKey(),
            imageFilesByParent = imageFilesByParent
                .filterKeys { it in parentUris }
                .mapValues { (_, images) -> images.sortedByStableFileKey() }
        )
    }

    // Store only compact display labels so the dialog can render concrete scan items.
    private fun ImportRunResult.toSummaryJson(recoveryResult: MissingBookFileRecoveryResult = MissingBookFileRecoveryResult()): String =
        buildString {
            append('{')
            append("\"newBooks\":").append(discoveredNames.toJsonArray()).append(',')
            append("\"partialImports\":").append(partialNames.toJsonArray()).append(',')
            append("\"updatedBooks\":").append((updateExistingNames + recoveryResult.restoredBookTitles).toJsonArray()).append(',')
            append("\"pendingActions\":").append(pendingNames.toJsonArray()).append(',')
            append("\"failures\":").append(failureNames.toJsonArray())
            append('}')
        }

    private fun List<String>.toJsonArray(): String =
        joinToString(prefix = "[", postfix = "]") { value -> "\"${value.escapeJson()}\"" }

    private fun String.escapeJson(): String =
        buildString {
            this@escapeJson.forEach { char ->
                when (char) {
                    '\\' -> append("\\\\")
                    '"' -> append("\\\"")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> append(char)
                }
            }
        }
}
