package com.viel.aplayer.library.orchestrator

import android.content.Context
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
import com.viel.aplayer.logger.SecureLog
import kotlinx.coroutines.CancellationException

/**
 * Core Service Coordinator.
 *
 * Extracted from legacy RescanCoordinator to oversee incremental caching and directory-scope imports.
 * Key responsibilities:
 * 1. Coordinates SourceInventoryScanner to stream the physical file hierarchy.
 * 2. Matches lastModified timestamps of physical directories against Room directory cache;
 *    on a match, skips metadata processing for unmodified folders to improve rescanning speeds.
 *    unclaimed audio file check is used to verify existing claimed status.
 * 3. Instructs ImportScopeBuilder to construct incremental scopes, routing them to the pipeline or DirectoryAudioImporter.
 * 4. Invokes BookImporter to write files transactionally, updating the file claim index immediately upon success.
 *
 * DirectoryAudioImporter. from global scope lifecycle
 * management (ScopeOrchestrator), avoiding large God-class architectures.
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
     * Lifecycle Controller.
     *
     * Consumes the stream of physical folders, managing caching checks, parsing, transactional writes,
     * and compiles the final ImportRunResult.
     */
    suspend fun execute(
        scanId: String,
        roots: List<LibraryRootEntity>,
        initialClaimIndex: ExistingClaimIndex,
        type: RescanType
    ): ImportRunResult {
        val scanImportStartedAt = ImportTimingLogger.mark()
        ImportTimingLogger.logEvent(
            scopeId = "scan:$scanId",
            stage = "scan.start",
            detail = "type=$type roots=${roots.size}"
        )
        val scopeVfs = VfsFileInterface(context.applicationContext, rootsById = roots.associateBy { it.id })
        val scopeBuilder = ImportScopeBuilder(context, scopeVfs)
        val scopeResults = mutableListOf<ImportRunResult>()
        val scanClaimLedger = RunClaimLedger()
        var currentExistingIndex = initialClaimIndex

        suspend fun applyScopes(importScopes: List<ImportScope>) {
            importScopes.forEach { scope ->
                if (scope.kind == ImportScopeKind.DIRECTORY_AUDIO) {
                    val subBatchResults = directoryAudioImporter.import(
                        scope = scope,
                        existingClaimIndex = currentExistingIndex,
                        claimLedger = scanClaimLedger,
                        scanId = scanId
                    )
                    scopeResults.addAll(subBatchResults.map { it.result })

                    val anySuccessApplied = subBatchResults.any {
                        it.success && (
                            it.result.readyImports.isNotEmpty() ||
                                it.result.refreshedBooks.isNotEmpty() ||
                                it.result.replacementImports.isNotEmpty()
                            )
                    }
                    if (anySuccessApplied) {
                        currentExistingIndex = ImportTimingLogger.measure(
                            scopeId = scope.timingScopeId(),
                            stage = "db.refreshClaimIndex"
                        ) {
                            ExistingClaimIndex.from(
                                files = bookDao.getAllBookFilesOnce(),
                                books = bookDao.getAllBooksOnce()
                            )
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
                    ImportTimingLogger.logDuration(
                        scopeId = timingScopeId,
                        stage = "scope.failed",
                        elapsedMs = ImportTimingLogger.elapsedMs(scopeStartedAt),
                        detail = "phase=orchestrator ${scope.inventory.timingCountDetail()}"
                    )
                    scopeResults.add(scope.toScopeFailure(scanId, "Import scope parsing failed", throwable))
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
                    scope.toScopeFailure(
                        scanId = scanId,
                        message = "Import scope database write failed",
                        throwable = throwable,
                        inheritedFailures = scopeResult.failures
                    )
                }

                scopeResults.add(appliedResult)
                if (appliedResult === scopeResult) {
                    scanClaimLedger.commitFrom(scopeLedger)
                    appliedResult.triggerCoverRegenerationForReadyBooks()
                }

                if (appliedResult.readyImports.isNotEmpty() ||
                    appliedResult.refreshedBooks.isNotEmpty() ||
                    appliedResult.replacementImports.isNotEmpty()
                ) {
                    currentExistingIndex = ImportTimingLogger.measure(
                        scopeId = timingScopeId,
                        stage = "db.refreshClaimIndex"
                    ) {
                        ExistingClaimIndex.from(
                            files = bookDao.getAllBookFilesOnce(),
                            books = bookDao.getAllBooksOnce()
                        )
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
            val cachedDir = directoryCacheDao.getBySourcePath(directory.root.id, directory.sourcePath)
            val isCacheValid = cachedDir != null &&
                                cachedDir.lastModified == directory.lastModified &&
                                directory.audioFiles.all { currentExistingIndex.has(it.identity) }

            if (isCacheValid) {
                ImportTimingLogger.logEvent(
                    scopeId = "directory:${directory.root.id}:${directory.sourcePath}",
                    stage = "scan.skipByCache",
                    detail = "sourcePath=${directory.sourcePath.ifBlank { "<root>" }} files=${directory.audioFiles.size} - cache hit; skipped physical file analysis and metadata reads"
                )
                return@collect
            }

            val importDirectory = if (type == RescanType.COLD_START_LIGHT) {
                directory.forLightScanClaimReconciliation(currentExistingIndex)
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
                SecureLog.error("ScopeOrchestrator", "Failed to cache directory lastModified for ${directory.root.id}:${directory.sourcePath}", e)
            }
        }
        applyScopes(scopeBuilder.finish())

        val finalResult = ImportRunResult(
            scanId = scanId,
            readyImports = scopeResults.flatMap { it.readyImports },
            refreshedBooks = scopeResults.flatMap { it.refreshedBooks },
            failures = scopeResults.flatMap { it.failures },
            replacementImports = scopeResults.flatMap { it.replacementImports }
        )
        ImportTimingLogger.logDuration(
            scopeId = "scan:$scanId",
            stage = "scan.total",
            elapsedMs = ImportTimingLogger.elapsedMs(scanImportStartedAt),
            detail = finalResult.timingCommandDetail()
        )
        return finalResult
    }

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
            failures = inheritedFailures + ImportCommand.RecordFailure(
                ImportFailure(
                    sourceUri = displayUri(),
                    message = message,
                    throwableMessage = throwable.localizedMessage ?: throwable.message
                )
            )
        )

    private fun ImportScope.displayUri(): String =
        inventory.cueFiles.firstOrNull()?.let { "${it.rootId}:${it.sourcePath}" }
            ?: inventory.m3u8Files.firstOrNull()?.let { "${it.rootId}:${it.sourcePath}" }
            ?: inventory.audioFiles.firstOrNull()?.parentSourceKey
            ?: inventory.imageFilesByParent.keys.firstOrNull()
            ?: inventory.roots.firstOrNull()?.sourceUri
            ?: id

    private fun ImportScope.timingScopeId(): String = "${kind.name}:${displayUri()}"

    private fun FileInventory.timingCountDetail(): String =
        "cue=${cueFiles.size} m3u8=${m3u8Files.size} audio=${audioFiles.size} imageParents=${imageFilesByParent.size}"

    private fun ImportRunResult.timingCommandDetail(): String =
        "ready=${readyImports.size} refreshed=${refreshedBooks.size} replaced=${replacementImports.size} failures=${failures.size}"

    private fun ImportRunResult.triggerCoverRegenerationForReadyBooks() {
        readyImports.forEach { command ->
            triggerCoverRegeneration(command.draft.book)
        }
        replacementImports.forEach { command ->
            triggerCoverRegeneration(command.draft.book)
        }
    }
}
