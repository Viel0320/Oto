package com.viel.aplayer.library

import android.content.Context
import java.util.UUID
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
            orchestrator.run(scanId, importInventory, existingIndex)
        }

        result.onSuccess { importResult ->
            importer.applyImportRun(importResult)
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