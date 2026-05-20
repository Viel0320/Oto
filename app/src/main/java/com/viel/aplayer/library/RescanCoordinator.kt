package com.viel.aplayer.library

import android.content.Context
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.viel.aplayer.data.db.AppDatabase
import com.viel.aplayer.data.db.AudiobookSchema
import com.viel.aplayer.data.entity.ScanSessionEntity

enum class RescanType {
    COLD_START_LIGHT,
    USER_GLOBAL,
    NEW_LIBRARY_ROOT
}

// Rescan entrypoint: creates a session, runs scanner/import orchestration, then applies results.
class RescanCoordinator(private val context: Context) {
    private val database = AppDatabase.getInstance(context)
    private val rootDao = database.libraryRootDao()
    private val bookDao = database.bookDao()
    private val scanSessionDao = database.scanSessionDao()
    private val scanner = FileInventoryScanner(context)
    private val orchestrator = ImportOrchestrator(context)
    private val importer = BookImporter(context)
    private val missingRecoveryChecker = MissingBookFileRecoveryChecker(context)

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
            val inventory = scanner.scan(roots)
            val existingIndex = ExistingClaimIndex.from(bookDao.getAllBookFilesOnce())
            // Cold-start light scans only process files not already claimed by BookFile.
            val importInventory = if (type == RescanType.COLD_START_LIGHT) {
                inventory.onlyUnclaimed(existingIndex)
            } else {
                inventory
            }
            // 详尽的中文注释：主导入链路正式启用 FileInventory.groupByParent()，让每个同级物理目录作为独立导入上下文运行，
            // 避免启发式聚合、同目录封面/简介匹配、局域化冲突认领继续被全库级 inventory 混在一起处理。
            runImportByParent(scanId, importInventory, existingIndex)
        }

        result.onSuccess { importResult ->
            // 详尽的中文注释：目录分片已经在 runImportByParent 内逐片即时入库，这里只保留最终恢复检查、根目录状态刷新和扫描汇总展示。
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

    // 详尽的中文注释：按物理父目录逐片执行导入编排，并把各目录产出的命令合并成同一个扫描会话的 ImportRunResult。
    private suspend fun runImportByParent(
        scanId: String,
        inventory: FileInventory,
        existingIndex: ExistingClaimIndex
    ): ImportRunResult {
        // 详尽的中文注释：图片-only 目录不能单独形成书籍候选，过滤后可避免空分片反复进入清单解析与冲突裁决。
        val parentInventories = inventory.groupByParent().filter { parentInventory ->
            parentInventory.cueFiles.isNotEmpty() ||
                parentInventory.m3u8Files.isNotEmpty() ||
                parentInventory.audioFiles.isNotEmpty()
        }

        // 详尽的中文注释：空扫描仍需返回一个同 scanId 的空结果，后续完成会话和摘要统计可以沿用原有路径。
        if (parentInventories.isEmpty()) {
            return ImportRunResult(
                scanId = scanId,
                readyImports = emptyList(),
                refreshedBooks = emptyList(),
                pendingActions = emptyList(),
                failures = emptyList()
            )
        }

        val parentResults = mutableListOf<ImportRunResult>()
        // 详尽的中文注释：每个分片成功入库后立即刷新已有文件认领索引，让后续分片能够看到前面刚刚写入的 BookFile 所有权。
        var currentExistingIndex = existingIndex

        // 详尽的中文注释：每个目录分片独立运行 orchestrator 并立即入库，首页观察 Room Flow 时就能逐片刷新，而不是等全量分片结束。
        parentInventories.forEach { parentInventory ->
            val parentResult = runCatching {
                orchestrator.run(scanId, parentInventory, currentExistingIndex)
            }.getOrElse { throwable ->
                if (throwable is CancellationException) throw throwable
                // 详尽的中文注释：单个目录分片解析失败不再中断整个扫描，失败会进入最终汇总，其他分片继续导入。
                parentResults.add(parentInventory.toShardFailure(scanId, "目录分片导入失败", throwable))
                return@forEach
            }

            val appliedResult = runCatching {
                importer.applyImportRun(parentResult)
                parentResult
            }.getOrElse { throwable ->
                if (throwable is CancellationException) throw throwable
                // 详尽的中文注释：分片入库使用 BookImporter 内部事务，失败时该分片不会产生半截数据，并把入库错误纳入最终扫描汇总。
                parentInventory.toShardFailure(
                    scanId = scanId,
                    message = "目录分片入库失败",
                    throwable = throwable,
                    inheritedFailures = parentResult.failures
                )
            }

            parentResults.add(appliedResult)
            // 详尽的中文注释：只要分片入库成功，就重新读取 BookFile 快照，保证后续分片的冲突判断包含本轮已写入的数据。
            if (appliedResult.readyImports.isNotEmpty() ||
                appliedResult.refreshedBooks.isNotEmpty() ||
                appliedResult.pendingActions.isNotEmpty()
            ) {
                currentExistingIndex = ExistingClaimIndex.from(bookDao.getAllBookFilesOnce())
            }
        }

        // 详尽的中文注释：分片结果只改变导入决策的作用域，不改变对外扫描会话语义，因此这里合并回一个 ImportRunResult 交给原落库逻辑。
        return ImportRunResult(
            scanId = scanId,
            readyImports = parentResults.flatMap { it.readyImports },
            refreshedBooks = parentResults.flatMap { it.refreshedBooks },
            pendingActions = parentResults.flatMap { it.pendingActions },
            failures = parentResults.flatMap { it.failures }
        )
    }

    // 详尽的中文注释：把目录级异常转换成 ImportRunResult，使最终 ScanResultDialog 能汇总展示失败目录而不吞掉错误。
    private fun FileInventory.toShardFailure(
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
                    sourceUri = shardDisplayUri(),
                    message = message,
                    throwableMessage = throwable.localizedMessage ?: throwable.message
                )
            )
        )

    // 详尽的中文注释：为目录分片失败摘要挑一个稳定可读的来源 Uri，优先使用该分片实际文件的父目录。
    private fun FileInventory.shardDisplayUri(): String =
        cueFiles.firstOrNull()?.parentUri
            ?: m3u8Files.firstOrNull()?.parentUri
            ?: audioFiles.firstOrNull()?.parentUri
            ?: imageFilesByParent.keys.firstOrNull()
            ?: roots.firstOrNull()?.treeUri
            ?: "unknown-shard"

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
