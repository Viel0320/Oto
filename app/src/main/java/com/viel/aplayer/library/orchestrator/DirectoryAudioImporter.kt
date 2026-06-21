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
 * DirectoryAudioImporter.
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

    private val chapterdAudioImportBatchSize: Int = DEFAULT_SCOPE_IO_CONCURRENCY

    private val directoryAudioMetadataBatchSize: Int = DEFAULT_SCOPE_IO_CONCURRENCY

    /**
     * Concurrent routing entry.
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
            val metadataJobs = candidateAudios.map { audio ->
                async {
                    metadataSemaphore.withPermit {
                        val extracted = metadataResolver.extractWithEmbeddedCover(audio)
                        AudioMetadataRef(audio, extracted.metadata, extracted.embeddedCover)
                    }
                }
            }
            val metadataSummaryJob = async {
                val allRefs = metadataJobs.awaitAll()
                val chapteredCount = allRefs.count { it.metadata.chapters.isNotEmpty() }
                ImportTimingLogger.logDuration(
                    scopeId = timingScopeId,
                    stage = "directoryAudio.metadataResolve",
                    elapsedMs = ImportTimingLogger.elapsedMs(metadataStartedAt),
                    detail = "input=${scope.inventory.audioFiles.size} resolved=${allRefs.size} chaptered=$chapteredCount heuristic=${allRefs.size - chapteredCount} batches=${metadataJobs.chunked(directoryAudioMetadataBatchSize).size}"
                )
            }

            val metadataBatches = metadataJobs.chunked(directoryAudioMetadataBatchSize)
            metadataBatches.forEachIndexed { index, batchJobs ->
                val batchMetadataStartedAt = ImportTimingLogger.mark()
                val batchRefs = batchJobs.awaitAll()
                val chapteredRefs = mutableListOf<AudioMetadataRef>()
                batchRefs.forEach { audioRef ->
                    if (existingClaimIndex.has(audioRef.file.identity)) return@forEach
                    if (audioRef.metadata.chapters.isNotEmpty()) {
                        chapteredRefs.add(audioRef)
                    } else {
                        heuristicRefs.add(audioRef)
                    }
                }
                ImportTimingLogger.logDuration(
                    scopeId = timingScopeId,
                    stage = "directoryAudio.metadataResolveBatch",
                    elapsedMs = ImportTimingLogger.elapsedMs(batchMetadataStartedAt),
                    detail = "batch=${index + 1}/${metadataBatches.size} input=${batchJobs.size} resolved=${batchRefs.size} chaptered=${chapteredRefs.size} heuristic=${batchRefs.size - chapteredRefs.size}"
                )

                chapteredRefs.chunked(chapterdAudioImportBatchSize).forEachIndexed { chunkIndex, batchRefs ->
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
     * Isolated transactional pipeline.
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
            val dbFailResult = scope.toScopeFailure(
                scanId = scanId,
                message = "目录音频子批次入库失败",
                throwable = throwable,
                inheritedFailures = scopeResult.failures
            )
            return SubBatchResult(result = dbFailResult, success = false)
        }

        claimLedger.commitFrom(scopeLedger)
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
     * Directory asset preservation.
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

/**
 * Data transfer object.
 * Encapsulates execution results and success status of a sub-batch database insertion.
 */
internal data class SubBatchResult(
    val result: ImportRunResult,
    val success: Boolean
)
