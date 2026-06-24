package com.viel.oto.library.orchestrator

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import com.viel.oto.library.FileInventory
import com.viel.oto.library.FileRef
import com.viel.oto.library.orchestrator.draftmodels.CoverExtractedAggregated
import com.viel.oto.library.orchestrator.draftmodels.CoverExtractedCue
import com.viel.oto.library.orchestrator.draftmodels.CoverExtractedM3u8
import com.viel.oto.library.orchestrator.draftmodels.CoverExtractedResult
import com.viel.oto.library.orchestrator.draftmodels.CoverExtractedSingle
import com.viel.oto.library.orchestrator.draftmodels.ImportRunResult
import com.viel.oto.library.orchestrator.steps.GroupedBookDrafts
import com.viel.oto.library.orchestrator.steps.HeuristicGroupStep
import com.viel.oto.library.orchestrator.steps.ManifestParseStep
import com.viel.oto.library.orchestrator.steps.ManifestParsedResult
import com.viel.oto.library.orchestrator.steps.MetadataResolveStep
import com.viel.oto.library.orchestrator.steps.OwnershipClaimStep
import com.viel.oto.library.orchestrator.steps.ResolvedMetadataDrafts
import com.viel.oto.library.vfs.VfsFileInterface
import com.viel.oto.logger.ImportTimingLogger
import com.viel.oto.media.manifest.AudioMetadataRef
import com.viel.oto.media.parser.CoverExtractor
import com.viel.oto.media.parser.MetadataResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * Coordinates the import worker steps that turn a scanned file inventory into persisted book drafts.
 *
 * ScanSessionRunner depends on this single entry point while parsing, metadata resolution,
 * grouping, cover binding, and ownership decisions remain in dedicated step classes.
 */
@OptIn(UnstableApi::class)
class ImportPipeline(
    private val context: Context,
    metadataResolver: MetadataResolver
) {
    private val manifestParseStep = ManifestParseStep(context)
    private val metadataResolveStep = MetadataResolveStep(context, metadataResolver)
    private val heuristicGroupStep = HeuristicGroupStep(context)
    private val draftFactory = BookDraftFactory(metadataResolver)
    private val ownershipClaimStep = OwnershipClaimStep(context, draftFactory)
    private val coverExtractor = CoverExtractor(context)

    suspend fun run(
        scanId: String,
        inventory: FileInventory,
        existingClaimIndex: ExistingClaimIndex,
        runClaimLedger: RunClaimLedger = RunClaimLedger(),
        timingScopeId: String = "inventory:$scanId"
    ): ImportRunResult = withContext(Dispatchers.IO) {

        val scopeVfs = VfsFileInterface(context.applicationContext, rootsById = inventory.roots.associateBy { it.id })
        val importCtx = ImportContext(
            scanId = scanId,
            existingClaimIndex = existingClaimIndex,
            runClaimLedger = runClaimLedger,
            sharedInventory = inventory,
            scopeFileReader = scopeVfs
        )

        val manifestParsedResult = ImportTimingLogger.measure(
            scopeId = timingScopeId,
            stage = "orchestrator.manifestParse",
            detail = inventory.timingCountDetail()
        ) {
            manifestParseStep.execute(inventory, importCtx)
        }

        val resolvedMetadataDrafts = ImportTimingLogger.measure(
            scopeId = timingScopeId,
            stage = "orchestrator.metadataResolve",
            detail = "audio=${inventory.audioFiles.size}"
        ) {
            metadataResolveStep.execute(manifestParsedResult, importCtx)
        }

        val groupedBookDrafts = ImportTimingLogger.measure(
            scopeId = timingScopeId,
            stage = "orchestrator.heuristicGroup",
            detail = "loose=${resolvedMetadataDrafts.looseAudioMetadataRefs.size}"
        ) {
            heuristicGroupStep.execute(resolvedMetadataDrafts, importCtx)
        }

        val coverExtractedResult = ImportTimingLogger.measure(
            scopeId = timingScopeId,
            stage = "orchestrator.coverDefer",
            detail = "cue=${groupedBookDrafts.manifestParsedResult.cueDrafts.size} m3u8=${groupedBookDrafts.manifestParsedResult.m3u8Drafts.size} aggregated=${groupedBookDrafts.aggregatedPlans.size} single=${groupedBookDrafts.singleAudios.size}"
        ) {
            groupedBookDrafts.toDeferredCoverResult(inventory, scopeVfs)
        }

        val claimDecidedResult = ImportTimingLogger.measure(
            scopeId = timingScopeId,
            stage = "orchestrator.conflictClaim",
            detail = "cue=${coverExtractedResult.cueBooks.size} m3u8=${coverExtractedResult.m3u8Books.size} aggregated=${coverExtractedResult.aggregatedBooks.size} single=${coverExtractedResult.singleBooks.size}"
        ) {
            ownershipClaimStep.execute(coverExtractedResult, importCtx)
        }

        claimDecidedResult
    }

    internal suspend fun runWithPreResolvedAudio(
        scanId: String,
        inventory: FileInventory,
        existingClaimIndex: ExistingClaimIndex,
        runClaimLedger: RunClaimLedger,
        looseAudioMetadataRefs: List<AudioMetadataRef>,
        timingScopeId: String = "directory-audio:$scanId"
    ): ImportRunResult = withContext(Dispatchers.IO) {
        val scopeVfs = VfsFileInterface(context.applicationContext, rootsById = inventory.roots.associateBy { it.id })
        val importCtx = ImportContext(
            scanId = scanId,
            existingClaimIndex = existingClaimIndex,
            runClaimLedger = runClaimLedger,
            sharedInventory = inventory,
            scopeFileReader = scopeVfs
        )

        val resolvedMetadataDrafts = ResolvedMetadataDrafts(
            manifestParsedResult = ManifestParsedResult(
                cueDrafts = emptyList(),
                m3u8Drafts = emptyList()
            ),
            looseAudioMetadataRefs = looseAudioMetadataRefs
        )

        val groupedBookDrafts = ImportTimingLogger.measure(
            scopeId = timingScopeId,
            stage = "orchestrator.heuristicGroup",
            detail = "loose=${looseAudioMetadataRefs.size}"
        ) {
            heuristicGroupStep.execute(resolvedMetadataDrafts, importCtx)
        }

        val coverExtractedResult = ImportTimingLogger.measure(
            scopeId = timingScopeId,
            stage = "orchestrator.coverDefer",
            detail = "aggregated=${groupedBookDrafts.aggregatedPlans.size} single=${groupedBookDrafts.singleAudios.size}"
        ) {
            groupedBookDrafts.toDeferredCoverResult(inventory, scopeVfs)
        }

        ImportTimingLogger.measure(
            scopeId = timingScopeId,
            stage = "orchestrator.conflictClaim",
            detail = "aggregated=${coverExtractedResult.aggregatedBooks.size} single=${coverExtractedResult.singleBooks.size}"
        ) {
            ownershipClaimStep.execute(coverExtractedResult, importCtx)
        }
    }

    private fun FileInventory.timingCountDetail(): String =
        "cue=${cueFiles.size} m3u8=${m3u8Files.size} audio=${audioFiles.size} imageParents=${imageFilesByParent.size}"

    private suspend fun GroupedBookDrafts.toDeferredCoverResult(
        inventory: FileInventory,
        fileReader: VfsFileInterface
    ): CoverExtractedResult {
        val audioByVfsKey = inventory.audioFiles.associateBy { it.vfsKey }
        return CoverExtractedResult(
            cueBooks = manifestParsedResult.cueDrafts.map { cueDraft ->
                val audioRefs =
                    cueDraft.resolvedAudioKeys.values.mapNotNull { key -> audioByVfsKey[key] }
                val coverResult = cueDraft.result.sidecarCoverFile?.let { sidecarFile ->
                    saveExternalSidecarCover(sidecarFile, fileReader)
                }
                CoverExtractedCue(
                    UUID.randomUUID().toString(),
                    cueDraft,
                    audioRefs,
                    coverResult = coverResult
                )
            },
            m3u8Books = manifestParsedResult.m3u8Drafts.map { m3u8Draft ->
                val audioRefs =
                    m3u8Draft.resolvedAudioKeys.values.mapNotNull { key -> audioByVfsKey[key] }
                val coverResult = m3u8Draft.result.sidecarCoverFile?.let { sidecarFile ->
                    saveExternalSidecarCover(sidecarFile, fileReader)
                }
                CoverExtractedM3u8(
                    UUID.randomUUID().toString(),
                    m3u8Draft,
                    audioRefs,
                    coverResult = coverResult
                )
            },
            aggregatedBooks = aggregatedPlans.map { plan ->
                val bookId = UUID.randomUUID().toString()
                val coverResult = plan.sidecarCoverFile?.let { sidecarFile ->
                    saveExternalSidecarCover(sidecarFile, fileReader)
                } ?: savePreReadEmbeddedCover(plan.chapters.map { it.audio })
                CoverExtractedAggregated(bookId, plan, coverResult)
            },
            singleBooks = singleAudios.map { audioRef ->
                val bookId = UUID.randomUUID().toString()
                val coverResult = savePreReadEmbeddedCover(listOf(audioRef))
                CoverExtractedSingle(bookId, audioRef, coverResult)
            }
        )
    }

    private suspend fun saveExternalSidecarCover(sidecarFile: FileRef, fileReader: VfsFileInterface): CoverExtractor.CoverResult? {
        val result = coverExtractor.processExternalImage(sidecarFile.vfsKey) { fileReader.open(sidecarFile) }
        return result.takeIf { it.hasImage() }
    }

    private suspend fun savePreReadEmbeddedCover(audioRefs: List<AudioMetadataRef>): CoverExtractor.CoverResult? {
        for (audioRef in audioRefs) {
            val cover = audioRef.embeddedCover ?: continue
            val result = coverExtractor.saveEmbeddedImage("${audioRef.file.vfsKey}:covr", cover.bytes)
            if (result.hasImage()) return result
        }
        return null
    }

    private fun CoverExtractor.CoverResult.hasImage(): Boolean =
        originalPath != null || thumbnailPath != null
}