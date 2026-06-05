package com.viel.aplayer.library.orchestrator

import android.content.Context
import androidx.media3.common.util.UnstableApi
import com.viel.aplayer.data.db.AppDatabase
import com.viel.aplayer.data.db.AudiobookSchema
import com.viel.aplayer.data.entity.BookEntity
import com.viel.aplayer.data.entity.ScanSessionEntity
import com.viel.aplayer.library.SourceInventoryScanner
import com.viel.aplayer.library.availability.MissingBookFileRecoveryChecker
import com.viel.aplayer.library.availability.MissingBookFileRecoveryResult
import com.viel.aplayer.library.orchestrator.draftmodels.ImportRunResult
import com.viel.aplayer.library.vfs.VfsFileInterface
import com.viel.aplayer.library.vfs.cache.DirectoryListingCache
import com.viel.aplayer.library.vfs.cache.NoOpDirectoryListingCache
import com.viel.aplayer.media.parser.MetadataResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * Rescan Trigger Type (Incremental Configuration)
 *
 * Configures the source and depth characteristics of the current rescan request.
 */
enum class RescanType {
    COLD_START_LIGHT,
    USER_GLOBAL,
    NEW_LIBRARY_ROOT
}

/**
 * Audiobook Scan Session Executor (Lifecycle Manager)
 *
 * Serves as the primary external lifecycle control entry for scanning tasks.
 * Key responsibilities:
 * 1. Initializes the scan session tracker (ScanSessionEntity).
 * 2. Coordinates VFS structures, MetadataResolver, ImportPipeline, and BookImporter.
 * 3. Allocates ScopeOrchestrator to run folder collections and incremental updates.
 * 4. Finalizes library state recalibrations, self-heals missing files, and writes back diagnostic session records.
 *
 * Designed to replace legacy architectures by isolating orchestration from individual folder parsing steps.
 */
@UnstableApi
class ScanSessionRunner(
    private val context: Context,
    // Inject VFS Facade Singleton (Dependency Decoupling)
    // Ensures file operations refer to a single virtual file system to prevent raw resource creation.
    vfsFileInterface: VfsFileInterface,
    // Directory Listing Cache Injection (Scanner-only listing reuse boundary)
    // Supplies WebDAV child snapshots to SourceInventoryScanner without changing playback, availability, or metadata range readers.
    directoryListingCache: DirectoryListingCache = NoOpDirectoryListingCache,
    // Cover Image Caching Hook (Decoupled Callback)
    // Forwards cover reconstruction triggers to higher layers rather than managing extraction directly.
    private val triggerCoverRegeneration: (BookEntity) -> Unit = {}
) {
    private val database = AppDatabase.getInstance(context)
    private val rootDao = database.libraryRootDao()
    private val bookDao = database.bookDao()
    private val scanSessionDao = database.scanSessionDao()
    private val scanner = SourceInventoryScanner(
        context = context,
        directoryListingCache = directoryListingCache
    )
    
    private val metadataResolver = MetadataResolver(vfsFileInterface)
    private val pipeline = ImportPipeline(context, metadataResolver)
    private val importer = BookImporter(context)
    private val missingRecoveryChecker = MissingBookFileRecoveryChecker(context)

    // Initialize Scoped Directory Importer (Subcomponent Orchestration)
    // Spawns a dedicated coordinator responsible for processing files and writing them in sub-batches.
    private val directoryAudioImporter = DirectoryAudioImporter(
        metadataResolver = metadataResolver,
        pipeline = pipeline,
        importer = importer,
        triggerCoverRegeneration = triggerCoverRegeneration
    )

    // Initialize Scoped Orchestrator Engine (Subcomponent Orchestration)
    // Allocates the coordination logic responsible for cache validation, file trees, and pipeline execution.
    private val scopeOrchestrator = ScopeOrchestrator(
        context = context,
        scanner = scanner,
        pipeline = pipeline,
        directoryAudioImporter = directoryAudioImporter,
        importer = importer,
        triggerCoverRegeneration = triggerCoverRegeneration
    )

    /**
     * Dispatch Rescan Operation (Session Entry Point)
     *
     * Registers a new scan session in the database, delegates import processing to ScopeOrchestrator,
     * updates system status, recalculates missing counts, and returns the final session model.
     */
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
        // Flush Stale Pending Actions (Data Integrity Protection)
        // Removes old, unconfirmed conflict entries from the DB to ensure a clean rescan environment.
        scanSessionDao.clearPendingActions()
        scanSessionDao.insertSession(session)

        val result = runCatching {
            val roots = when (type) {
                RescanType.NEW_LIBRARY_ROOT -> rootId?.let { rootDao.getRootById(it) }?.let(::listOf).orEmpty()
                else -> rootDao.getActiveRootsOnce()
            }
            val existingIndex = ExistingClaimIndex.from(bookDao.getAllBookFilesOnce())
            
            // Delegate Import Pipeline (Pipeline Execution)
            // Invokes ScopeOrchestrator to parse and import all files within folders.
            scopeOrchestrator.execute(scanId, roots, existingIndex, type)
        }

        result.onSuccess { importResult ->
            // Run Cold-Start File Recovery (Asset Self-Healing)
            // Triggers missing file checks during light cold starts to restore database references if file access returns.
            val recoveryResult = if (type == RescanType.COLD_START_LIGHT) {
                missingRecoveryChecker.recoverMissingAudioFiles()
            } else {
                MissingBookFileRecoveryResult()
            }
            importResult.readyImports.map { it.draft.book.rootId }.distinct().forEach { scannedRootId ->
                rootDao.updateRootScanState(scannedRootId, System.currentTimeMillis())
            }
            scanSessionDao.markCompleted(
                id = scanId,
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

    // Compile Session Summary Log (JSON Diagnostics Compilation)
    // Serializes discovered names, updates, conflicts, and failures into a diagnostic JSON string.
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

    // Encode Collection to JSON Array (JSON Formatting Utility)
    // Safely encodes and joins a list of strings into a standard JSON array representation.
    private fun List<String>.toJsonArray(): String =
        joinToString(prefix = "[", postfix = "]") { value -> "\"${value.escapeJson()}\"" }

    // Escape Special Characters for JSON (Formatting Security)
    // Sanitizes strings to prevent JSON structural breakdowns when stored or parsed.
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
