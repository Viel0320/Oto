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
 * Global Scan Session Orchestrator Engine (Core Service Coordinator)
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
 * This division separates single-folder metadata parsing (DirectoryAudioImporter) from global scope lifecycle
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
     * Execute Global Scan and Import Orchestration (Lifecycle Controller)
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
        // Log Scan Session Start (Timing Diagnostics)
        // Mark starting timestamp to allow end-to-end execution cost evaluations under scan.total.
        val scanImportStartedAt = ImportTimingLogger.mark()
        ImportTimingLogger.logEvent(
            scopeId = "scan:$scanId",
            stage = "scan.start",
            detail = "type=$type roots=${roots.size}"
        )
        // Scoped Builder Initialization (State Protection)
        // Allocate a new builder per session to prevent cache pollution between rescans.
        // Initialize a single VfsFileInterface facade to share across all downstream steps.
        val scopeVfs = VfsFileInterface(context.applicationContext, rootsById = roots.associateBy { it.id })
        val scopeBuilder = ImportScopeBuilder(context, scopeVfs)
        val scopeResults = mutableListOf<ImportRunResult>()
        // Session-Wide Claim Ledger (Ownership Collision Protection)
        // Tracks audio file reservations across scopes to prevent downstream heuristics from reclaiming files.
        val scanClaimLedger = RunClaimLedger()
        // Incremental Claim Index Refresh (Data Consistency)
        // Reloads claimed audio files immediately after writing a scope so subsequent steps see new ownership.
        var currentExistingIndex = initialClaimIndex

        // Scoped Import Processor Helper (Reusable Pipeline Routine)
        // Processes scopes emitted during directory scanning as well as leftover scopes in the finish() stage.
        suspend fun applyScopes(importScopes: List<ImportScope>) {
            importScopes.forEach { scope ->
                if (scope.kind == ImportScopeKind.DIRECTORY_AUDIO) {
                    // Route Loose Audio Tracks (Pipeline Routing Rules)
                    // Tracks containing embedded chapters are written immediately, while others proceed to heuristic grouping.
                    val subBatchResults = directoryAudioImporter.import(
                        scope = scope,
                        existingClaimIndex = currentExistingIndex,
                        claimLedger = scanClaimLedger,
                        scanId = scanId
                    )
                    // Consolidate Batch Results (Statistics Gathering)
                    // Aggregates downstream results for final scan report output.
                    scopeResults.addAll(subBatchResults.map { it.result })

                    // Conditionally Reload Claim Index (Data Consistency)
                    // Refreshes the database claim index if any files are newly mapped, refreshed, or replaced.
                    val anySuccessApplied = subBatchResults.any {
                        // Claim Snapshot Refresh Trigger (Replacement-aware ownership)
                        // Reloads persisted claims after replacements too, because deleted old books and inserted priority owners change downstream conflict decisions.
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
                    // Graceful Scope Failure Handlers (Resiliency Architecture)
                    // Failures in a single directory or manifest do not crash the rescan; errors aggregate in the final log.
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
                    // Transactional Scope Execution (Data Integrity Guard)
                    // Employs transactional writes in BookImporter to prevent partial records, logging errors in the final summary.
                    scope.toScopeFailure(
                        scanId = scanId,
                        message = "导入 scope 入库失败",
                        throwable = throwable,
                        inheritedFailures = scopeResult.failures
                    )
                }

                scopeResults.add(appliedResult)
                // Commit Claims on Success (Claim Ledger Sync)
                // Only merge temporary scope claims if database writes succeed to prevent stale allocations.
                if (appliedResult === scopeResult) {
                    scanClaimLedger.commitFrom(scopeLedger)
                    // Asynchronous Cover Regrowth (Performance Optimization)
                    // Triggers cover extraction out-of-band to prevent UI blocking during import operations.
                    appliedResult.triggerCoverRegenerationForReadyBooks()
                }

                // Refresh Claim Snapshots (Ownership Integrity)
                // Reload book file claims from Room database on successful writes to inform downstream conflict resolution.
                if (appliedResult.readyImports.isNotEmpty() ||
                    appliedResult.refreshedBooks.isNotEmpty() ||
                    appliedResult.replacementImports.isNotEmpty()
                ) {
                    currentExistingIndex = ImportTimingLogger.measure(
                        scopeId = timingScopeId,
                        stage = "db.refreshClaimIndex"
                    ) {
                        // Existing Claim Priority Snapshot (Post-transaction state)
                        // Refreshes both files and books so subsequent scopes see ownership replacements with correct source priority.
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
            // Incremental Caching Analysis (Performance Optimization)
            // Skip directory processing if lastModified dates match and all audio files are already claimed in currentExistingIndex.
            // Bypasses physical file system evaluations, manifest parses, and ID3 parsing for zero-cost incremental updates.
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

            // Cold-Start Lightweight Filtering (Incremental Scan Rules)
            // Filters out previously claimed files in the Inventory layer during cold starts to reduce scope overhead.
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

            // Cache Directory Modification State (Incremental Sync Caching)
            // Save current lastModified timestamp to DB cache after parsing to serve as accelerating baseline for subsequent rescans.
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
                // Release Error Boundary (Sanitize directory cache timestamp failures)
                // Directory sourcePath is a user storage coordinate, so retained errors must not write it directly to Logcat.
                SecureLog.error("ScopeOrchestrator", "Failed to cache directory lastModified for ${directory.root.id}:${directory.sourcePath}", e)
            }
        }
        // Pipeline Finalization Step (Lifecycle Finalize)
        // Closes the building process by flushing remaining scopes.
        applyScopes(scopeBuilder.finish())

        // Consolidate Pipeline Outputs (Lifecycle Finalize)
        // Combines all incremental scope results into a single ImportRunResult for backward-compatible reporting.
        val finalResult = ImportRunResult(
            scanId = scanId,
            readyImports = scopeResults.flatMap { it.readyImports },
            refreshedBooks = scopeResults.flatMap { it.refreshedBooks },
            failures = scopeResults.flatMap { it.failures },
            // Replacement Result Aggregation (Resolved ownership conflicts)
            // Carries deterministic replacements into scan session summaries instead of dropping them after per-scope writes.
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

    // Map Exception to Failure Commands (Error Diagnostics)
    // Converts scope errors into a formal ImportRunResult with failure commands to ensure they show up in UI diagnostics.
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

    // Determine Stable Scope Identifier (Storage Decoupling)
    // Selects a representative path representation (e.g. rootId:sourcePath) for diagnostics instead of protocol URIs.
    private fun ImportScope.displayUri(): String =
        inventory.cueFiles.firstOrNull()?.let { "${it.rootId}:${it.sourcePath}" }
            ?: inventory.m3u8Files.firstOrNull()?.let { "${it.rootId}:${it.sourcePath}" }
            ?: inventory.audioFiles.firstOrNull()?.parentSourceKey
            ?: inventory.imageFilesByParent.keys.firstOrNull()
            ?: inventory.roots.firstOrNull()?.sourceUri
            ?: id

    // Format Diagnostics Scope ID (Performance Tracing)
    // Generates a descriptive string indicating type and path for debugging slow manifests or folders.
    private fun ImportScope.timingScopeId(): String = "${kind.name}:${displayUri()}"

    // Format Scoped File Counts (Diagnostics Metrics)
    // Generates counts for various file assets to contextualize performance metrics.
    private fun FileInventory.timingCountDetail(): String =
        "cue=${cueFiles.size} m3u8=${m3u8Files.size} audio=${audioFiles.size} imageParents=${imageFilesByParent.size}"

    // Format Command Execution Counts (Diagnostics Metrics)
    // Returns counts of operations (inserts, updates, conflicts) to evaluate DB bottlenecks.
    private fun ImportRunResult.timingCommandDetail(): String =
        "ready=${readyImports.size} refreshed=${refreshedBooks.size} replaced=${replacementImports.size} failures=${failures.size}"

    // Trigger Covers for Successful Imports (Asset Recovery)
    // Enqueues cover extraction jobs for newly imported ready books; avoids doing so for updates or unconfirmed files.
    private fun ImportRunResult.triggerCoverRegenerationForReadyBooks() {
        readyImports.forEach { command ->
            triggerCoverRegeneration(command.draft.book)
        }
        // Replacement Cover Regeneration (Visual state continuity)
        // Schedules the new owner for cover recovery just like a fresh import, because the old cover rows are removed with the replaced book.
        replacementImports.forEach { command ->
            triggerCoverRegeneration(command.draft.book)
        }
    }
}
