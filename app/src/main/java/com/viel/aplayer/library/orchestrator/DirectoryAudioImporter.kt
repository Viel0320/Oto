package com.viel.aplayer.library.orchestrator

import androidx.media3.common.util.UnstableApi
import com.viel.aplayer.data.entity.BookEntity
import com.viel.aplayer.library.FileInventory
import com.viel.aplayer.library.FileRef
import com.viel.aplayer.library.orchestrator.draftmodels.ImportCommand
import com.viel.aplayer.library.orchestrator.draftmodels.ImportFailure
import com.viel.aplayer.library.orchestrator.draftmodels.ImportRunResult
import com.viel.aplayer.library.sortedByStableFileKey
import com.viel.aplayer.logger.ImportTimingLogger
import com.viel.aplayer.media.manifest.AudioMetadataRef
import com.viel.aplayer.media.parser.MetadataResolver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Directory Audio Importer Station (DirectoryAudioImporter)
 * 
 * Extracted from the original RescanCoordinator to process ImportScopeKind.DIRECTORY_AUDIO scan imports.
 * Handles directory-level audio stream distribution, batch import of chaptered audio files, and heuristic grouping of chapterless tracks.
 * 
 * By isolating this complex concurrent stream routing, the main orchestrator's complexity is reduced,
 * decoupling session lifecycle management from directory-specific import logic.
 */
@UnstableApi
internal class DirectoryAudioImporter(
    private val metadataResolver: MetadataResolver,
    private val pipeline: ImportPipeline,
    private val importer: BookImporter,
    private val triggerCoverRegeneration: (BookEntity) -> Unit
) {

    // Chaptered Audio Batching (Performance stream optimization)
    // Processes chaptered tracks in bounded batches to stream metadata parsing and database writes, avoiding OOM from too many large files.
    private val CHAPTERED_AUDIO_IMPORT_BATCH_SIZE: Int = DEFAULT_SCOPE_IO_CONCURRENCY

    // Metadata Consuming Window (Pipelined parsing performance)
    // Fetches metadata in small batches so early results can import immediately, eliminating the need to block on parsing the whole directory.
    private val DIRECTORY_AUDIO_METADATA_BATCH_SIZE: Int = DEFAULT_SCOPE_IO_CONCURRENCY

    /**
     * Execute Directory Audio Scope Import (Concurrent routing entry)
     * Reads metadata concurrently for all unclaimed audio files in the directory, then distributes them:
     * 1. Chaptered audio files are prioritized and imported immediately in small batches.
     * 2. Chapterless tracks are accumulated and imported together as a single heuristically aggregated audiobook.
     */
    suspend fun import(
        scope: ImportScope,
        existingClaimIndex: ExistingClaimIndex,
        claimLedger: RunClaimLedger,
        scanId: String
    ): List<SubBatchResult> {
        val scopeResults = mutableListOf<SubBatchResult>()
        val timingScopeId = scope.timingScopeId()
        val heuristicRefs = mutableListOf<AudioMetadataRef>()
        val candidateAudios = scope.inventory.audioFiles
            .filterNot { existingClaimIndex.has(it.identity) }
        val metadataStartedAt = ImportTimingLogger.mark()

        coroutineScope {
            val metadataSemaphore = Semaphore(DEFAULT_SCOPE_IO_CONCURRENCY)
            // Bounded Metadata Jobs Execution (Saturated thread pooling)
            // Fires all metadata jobs utilizing a semaphore to limit memory footprint while pipelining imports to maintain throughput.
            val metadataJobs = candidateAudios.map { audio ->
                async {
                    metadataSemaphore.withPermit {
                        // VFS Metadata Extraction (Abstract path decoding)
                        // Resolves metadata via the VFS FileRef, eliminating provider-specific URIs and reuse cached covers to avoid redundant MP4 Range reads.
                        val extracted = metadataResolver.extractWithEmbeddedCover(audio)
                        AudioMetadataRef(audio, extracted.metadata, extracted.embeddedCover)
                    }
                }
            }
            // Metadata Profiling Coroutine (Wall-time measurement)
            // Runs a standalone coroutine to track when all metadata resolves, ensuring metrics isolate parsing wall-time from database writes.
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
                    if (existingClaimIndex.has(audioRef.file.identity)) return@forEach
                    if (audioRef.metadata.chapters.isNotEmpty()) {
                        // Pipelined Chaptered Imports (Low-latency ingestion)
                        // Forwards chaptered tracks to the import queue immediately without waiting for the rest of the directory to finish parsing.
                        chapteredRefs.add(audioRef)
                    } else {
                        // Chapterless Tracks Buffering (Heuristic scoping)
                        // Accumulates chapterless tracks to retain complete directory context for heuristic audiobook grouping.
                        heuristicRefs.add(audioRef)
                    }
                }
                ImportTimingLogger.logDuration(
                    scopeId = timingScopeId,
                    stage = "directoryAudio.metadataResolveBatch",
                    elapsedMs = ImportTimingLogger.elapsedMs(batchMetadataStartedAt),
                    detail = "batch=${index + 1}/${metadataBatches.size} input=${batchJobs.size} resolved=${batchRefs.size} chaptered=${chapteredRefs.size} heuristic=${batchRefs.size - chapteredRefs.size}"
                )

                // In-Order Ingestion (Sequence and UI responsiveness)
                // Generates one chaptered sub-batch per metadata batch to maintain sequence stability and update the UI incrementally.
                chapteredRefs.chunked(CHAPTERED_AUDIO_IMPORT_BATCH_SIZE).forEachIndexed { chunkIndex, batchRefs ->
                    val result = importSubBatch(
                        scope = scope,
                        refs = batchRefs,
                        claimLedger = claimLedger,
                        existingClaimIndex = existingClaimIndex,
                        scanId = scanId,
                        failureMessage = "有章节音频批量提前导入失败",
                        timingSuffix = "chaptered-batch:${index + 1}/${metadataBatches.size}.${chunkIndex + 1}:files=${batchRefs.size}"
                    )
                    scopeResults.add(result)
                }
            }
            metadataSummaryJob.await()
        }

        // Heuristic Grouping Processing (Intact aggregation)
        // Aggregates all chapterless audio files in a single pass to prevent multi-file audiobooks from being fragmented.
        val heuristicResult = importSubBatch(
            scope = scope,
            refs = heuristicRefs,
            claimLedger = claimLedger,
            existingClaimIndex = existingClaimIndex,
            scanId = scanId,
            failureMessage = "无章节音频启发式导入失败",
            timingSuffix = "heuristic"
        )
        scopeResults.add(heuristicResult)

        return scopeResults
    }

    /**
     * Import Sub-Batch Ingestion (Isolated transactional pipeline)
     * Forks a temporary claim ledger for the sub-batch, executes the import orchestrator, and writes to database.
     * Merges changes into the parent scan ledger and invokes cover restoration upon a successful transaction.
     */
    private suspend fun importSubBatch(
        scope: ImportScope,
        refs: List<AudioMetadataRef>,
        claimLedger: RunClaimLedger,
        existingClaimIndex: ExistingClaimIndex,
        scanId: String,
        failureMessage: String,
        timingSuffix: String
    ): SubBatchResult {
        if (refs.isEmpty()) {
            return SubBatchResult(
                result = ImportRunResult(
                    scanId,
                    emptyList(),
                    emptyList(),
                    emptyList(),
                    emptyList()
                ),
                success = true
            )
        }
        val timingScopeId = "${scope.timingScopeId()}#$timingSuffix"
        val batchStartedAt = ImportTimingLogger.mark()
        val scopedInventory = scope.inventory.withAudioFiles(refs.map { it.file })
        val scopeLedger = claimLedger.fork()
        
        val scopeResult = runCatching {
            pipeline.runWithPreResolvedAudio(
                scanId = scanId,
                inventory = scopedInventory,
                existingClaimIndex = existingClaimIndex,
                runClaimLedger = scopeLedger,
                looseAudioMetadataRefs = refs,
                timingScopeId = timingScopeId
            )
        }.getOrElse { throwable ->
            if (throwable is CancellationException) throw throwable
            // Fault-Tolerant Batch Import (Non-blocking sub-batch errors)
            // Records sub-batch failures to the final report without halting other sub-batch processing within the directory.
            ImportTimingLogger.logDuration(
                scopeId = timingScopeId,
                stage = "scope.failed",
                elapsedMs = ImportTimingLogger.elapsedMs(batchStartedAt),
                detail = "phase=orchestrator files=${refs.size}"
            )
            val failResult = scope.toScopeFailure(scanId, failureMessage, throwable)
            return SubBatchResult(result = failResult, success = false)
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
            // Database Transactional Integrity (ACID rollback guarantee)
            // Ensures sub-batch database write is atomic, avoiding partial book inserts upon insertion errors.
            val dbFailResult = scope.toScopeFailure(
                scanId = scanId,
                message = "目录音频子批次入库失败",
                throwable = throwable,
                inheritedFailures = scopeResult.failures
            )
            return SubBatchResult(result = dbFailResult, success = false)
        }

        // Ledger Transaction Commit (Ownership locks promotion)
        // Only promotes claims from the sub-ledger if database transaction succeeds, preventing failed audio files from locking resources.
        claimLedger.commitFrom(scopeLedger)
        // Immediate Cover Regeneration (Async visual rendering update)
        // Schedules cover recovery immediately for successfully imported books so that the shelf reflects updates without delay.
        appliedResult.triggerCoverRegenerationForReadyBooks()

        ImportTimingLogger.logDuration(
            scopeId = timingScopeId,
            stage = "scope.total",
            elapsedMs = ImportTimingLogger.elapsedMs(batchStartedAt),
            detail = "files=${refs.size} ${appliedResult.timingCommandDetail()}"
        )

        return SubBatchResult(result = appliedResult, success = true)
    }

    /**
     * Build Sub-Batch File Inventory (Directory asset preservation)
     * Filters the inventory to include target audio files while preserving image sidecars to ensure cover recovery resolves correctly.
     */
    private fun FileInventory.withAudioFiles(audioFiles: List<FileRef>): FileInventory {
        val parentKeys = audioFiles.map { it.parentSourceKey }.toSet()
        val rootIds = audioFiles.map { it.rootId }.toSet()
        return FileInventory(
            roots = roots.filter { it.id in rootIds }.ifEmpty { roots },
            cueFiles = emptyList(),
            m3u8Files = emptyList(),
            audioFiles = audioFiles.sortedByStableFileKey(),
            imageFilesByParent = imageFilesByParent
                .filterKeys { it in parentKeys }
                .mapValues { (_, images) -> images.sortedByStableFileKey() },
            // Text Sidecars Retention (Context preservation)
            // Retains text description sidecars alongside image sidecars to support description fallback computations.
            textFilesByParent = textFilesByParent
                .filterKeys { it in parentKeys }
                .mapValues { (_, texts) -> texts.sortedByStableFileKey() }
        )
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
            pendingActions = emptyList(),
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

    private fun ImportRunResult.timingCommandDetail(): String =
        "ready=${readyImports.size} refreshed=${refreshedBooks.size} pending=${pendingActions.size} failures=${failures.size}"

    private fun ImportRunResult.triggerCoverRegenerationForReadyBooks() {
        readyImports.forEach { command ->
            triggerCoverRegeneration(command.draft.book)
        }
    }
}

/**
 * Sub-Batch Result Wrapper (Data transfer object)
 * Encapsulates execution results and success status of a sub-batch database insertion.
 */
internal data class SubBatchResult(
    val result: ImportRunResult,
    val success: Boolean  // Success indicator (Determines whether to commit this sub-batch to the parent claim ledger)
)
