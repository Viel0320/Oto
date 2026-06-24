package com.viel.oto.library.orchestrator

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import com.viel.oto.data.db.AppDatabase
import com.viel.oto.data.db.AudiobookSchema
import com.viel.oto.data.entity.BookEntity
import com.viel.oto.data.entity.LibraryRootEntity
import com.viel.oto.data.entity.ScanSessionEntity
import com.viel.oto.library.SourceInventoryScanner
import com.viel.oto.library.availability.MissingBookFileRecoveryChecker
import com.viel.oto.library.availability.MissingBookFileRecoveryResult
import com.viel.oto.library.availability.isDirectorySyncRoot
import com.viel.oto.library.orchestrator.draftmodels.ImportRunResult
import com.viel.oto.library.vfs.VfsFileInterface
import com.viel.oto.library.vfs.cache.DirectoryListingCache
import com.viel.oto.library.vfs.cache.NoOpDirectoryListingCache
import com.viel.oto.media.parser.MetadataResolver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * Incremental Configuration.
 *
 * Configures the source and depth characteristics of the current rescan request.
 */
enum class RescanType {
    COLD_START_LIGHT,
    USER_GLOBAL,
    USER_ROOTS,
    NEW_LIBRARY_ROOT
}

/**
 * Lifecycle Manager.
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
@OptIn(UnstableApi::class)
class ScanSessionRunner(
    private val context: Context,
    private val database: AppDatabase,
    vfsFileInterface: VfsFileInterface,
    directoryListingCache: DirectoryListingCache = NoOpDirectoryListingCache,
    private val triggerCoverRegeneration: (BookEntity) -> Unit = {},
    private val missingRecoveryChecker: MissingBookFileRecoveryChecker
) {
    private val rootDao = database.libraryRootDao()
    private val bookDao = database.bookDao()
    private val scanSessionDao = database.scanSessionDao()
    private val scanner = SourceInventoryScanner(
        context = context,
        directoryListingCache = directoryListingCache
    )

    private val metadataResolver = MetadataResolver(vfsFileInterface)
    private val pipeline = ImportPipeline(context, metadataResolver)
    private val importer = BookImporter(database)

    private val directoryAudioImporter = DirectoryAudioImporter(
        metadataResolver = metadataResolver,
        pipeline = pipeline,
        importer = importer,
        triggerCoverRegeneration = triggerCoverRegeneration
    )

    private val scopeOrchestrator = ScopeOrchestrator(
        context = context,
        database = database,
        scanner = scanner,
        pipeline = pipeline,
        directoryAudioImporter = directoryAudioImporter,
        importer = importer,
        triggerCoverRegeneration = triggerCoverRegeneration
    )

    /**
     * Session Entry Point.
     *
     * Registers a new scan session in the database, delegates import processing to ScopeOrchestrator,
     * updates system status, recalculates missing counts, and returns the final session model.
     */
    suspend fun rescan(
        type: RescanType,
        targetRootIds: Set<String> = emptySet(),
        allowedRootIds: Set<String>? = null
    ): ScanSessionEntity = withContext(Dispatchers.IO) {
        val scanId = UUID.randomUUID().toString()
        val trigger = when (type) {
            RescanType.COLD_START_LIGHT -> AudiobookSchema.ScanTrigger.COLD_START
            RescanType.USER_GLOBAL -> AudiobookSchema.ScanTrigger.USER
            RescanType.USER_ROOTS -> AudiobookSchema.ScanTrigger.USER
            RescanType.NEW_LIBRARY_ROOT -> AudiobookSchema.ScanTrigger.ADD_LIBRARY_ROOT
        }
        val session = ScanSessionEntity(
            id = scanId,
            trigger = trigger,
            status = AudiobookSchema.ScanStatus.RUNNING,
            startedAt = System.currentTimeMillis()
        )
        scanSessionDao.insertSession(session)

        var scannedRoots: List<LibraryRootEntity> = emptyList()
        val result = try {
            val roots = when (type) {
                RescanType.USER_ROOTS,
                RescanType.NEW_LIBRARY_ROOT -> targetRootIds.mapNotNull { rootId -> rootDao.getRootById(rootId) }
                RescanType.COLD_START_LIGHT ->
                    if (targetRootIds.isNotEmpty()) targetRootIds.mapNotNull { rootId -> rootDao.getRootById(rootId) }
                    else rootDao.getActiveRootsOnce()
                RescanType.USER_GLOBAL -> rootDao.getActiveRootsOnce()
            }
                .filter { root -> root.isDirectorySyncRoot() }
                .filter { root -> allowedRootIds == null || root.id in allowedRootIds }
            scannedRoots = roots
            val existingIndex = if (roots.size == 1) {
                val onlyRootId = roots.single().id
                ExistingClaimIndex.fromRoot(
                    files = bookDao.getBookFilesByRootId(onlyRootId),
                    books = bookDao.getActiveBooksByRootId(onlyRootId)
                )
            } else {
                ExistingClaimIndex.from(
                    files = bookDao.getAllBookFilesOnce(),
                    books = bookDao.getAllBooksOnce()
                )
            }

            Result.success(scopeOrchestrator.execute(scanId, roots, existingIndex, type))
        } catch (error: CancellationException) {
            scanSessionDao.markAbandoned(scanId)
            throw error
        } catch (error: Throwable) {
            Result.failure(error)
        }

        result.onSuccess { importResult ->
            val recoveryResult = if (type == RescanType.COLD_START_LIGHT) {
                scannedRoots.fold(MissingBookFileRecoveryResult()) { acc, root ->
                    acc + missingRecoveryChecker.recoverMissingAudioFiles(root.id)
                }
            } else {
                MissingBookFileRecoveryResult()
            }
            (importResult.readyImports.map { it.draft.book.rootId } + importResult.replacementImports.map { it.draft.book.rootId })
                .distinct()
                .forEach { scannedRootId ->
                rootDao.updateRootScanState(scannedRootId, System.currentTimeMillis())
            }
            scanSessionDao.markCompleted(
                id = scanId,
                discoveredBookCount = importResult.discoveredCount,
                unavailableBookCount = importResult.failureCount,
                partialBookCount = importResult.partialNewBookCount,
                updatedBookCount = importResult.updateExistingCount + recoveryResult.restoredBookCount,
                summaryJson = importResult.toSummaryJson(recoveryResult)
            )
        }

        result.onFailure {
            scanSessionDao.markAbandoned(scanId)
        }

        scanSessionDao.getSessionById(scanId) ?: session
    }

    private fun ImportRunResult.toSummaryJson(recoveryResult: MissingBookFileRecoveryResult = MissingBookFileRecoveryResult()): String =
        buildString {
            append('{')
            append("\"newBooks\":").append(discoveredNames.toJsonArray()).append(',')
            append("\"partialImports\":").append(partialNames.toJsonArray()).append(',')
            append("\"updatedBooks\":").append((updateExistingNames + recoveryResult.restoredBookTitles).toJsonArray()).append(',')
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
