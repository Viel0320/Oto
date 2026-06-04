package com.viel.aplayer.library.orchestrator

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import com.viel.aplayer.library.FileInventory
import com.viel.aplayer.library.FileRef
import com.viel.aplayer.library.orchestrator.draftmodels.CoverExtractedAggregated
import com.viel.aplayer.library.orchestrator.draftmodels.CoverExtractedCue
import com.viel.aplayer.library.orchestrator.draftmodels.CoverExtractedM3u8
import com.viel.aplayer.library.orchestrator.draftmodels.CoverExtractedResult
import com.viel.aplayer.library.orchestrator.draftmodels.CoverExtractedSingle
import com.viel.aplayer.library.orchestrator.draftmodels.ImportRunResult
import com.viel.aplayer.library.orchestrator.steps.GroupedBookDrafts
import com.viel.aplayer.library.orchestrator.steps.HeuristicGroupStep
import com.viel.aplayer.library.orchestrator.steps.ManifestParseStep
import com.viel.aplayer.library.orchestrator.steps.ManifestParsedResult
import com.viel.aplayer.library.orchestrator.steps.MetadataResolveStep
import com.viel.aplayer.library.orchestrator.steps.OwnershipClaimStep
import com.viel.aplayer.library.orchestrator.steps.ResolvedMetadataDrafts
import com.viel.aplayer.library.vfs.VfsFileInterface
import com.viel.aplayer.logger.ImportTimingLogger
import com.viel.aplayer.media.manifest.AudioMetadataRef
import com.viel.aplayer.media.parser.CoverExtractor
import com.viel.aplayer.media.parser.MetadataResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * Scan Import Orchestration Router (Pipeline Delegation Entry)
 *
 * This class has been refactored from a large monolithic class into decoupled worker steps
 * housed under the `orchestrator/steps` package.
 * It serves as a backward-compatible delegation gateway that bridges the legacy run method
 * to the new sequential processing steps, protecting ScanSessionRunner dependencies.
 */
@OptIn(UnstableApi::class)
class ImportPipeline(
    private val context: Context,
    // Inject Shared VFS Interface (Dependency Decoupling)
    // Avoids allocating redundant file reader instances across scope compilation runs.
    metadataResolver: MetadataResolver
) {
    // Initialize Pipeline Steps (Single Responsibility Coordination)
    // Instantiates decoupled workers corresponding to individual orchestration stages.
    private val manifestParseStep = ManifestParseStep(context)
    private val metadataResolveStep = MetadataResolveStep(context, metadataResolver)
    private val heuristicGroupStep = HeuristicGroupStep(context)
    private val draftFactory = BookDraftFactory(metadataResolver)
    private val ownershipClaimStep = OwnershipClaimStep(context, draftFactory)
    // Defer Heavy Cover Extraction (Asset Optimization)
    // Avoids re-opening audio tracks during import; writes pre-read cover bytes directly to cache.
    private val coverExtractor = CoverExtractor(context)

    suspend fun run(
        scanId: String,
        inventory: FileInventory,
        existingClaimIndex: ExistingClaimIndex,
        runClaimLedger: RunClaimLedger = RunClaimLedger(),
        timingScopeId: String = "inventory:$scanId"
    ): ImportRunResult = withContext(Dispatchers.IO) {

        // Initialize Import Context (State Setup)
        // Groups pre-scanned files into a session-scoped ImportContext and allocates a unique VfsFileInterface facade.
        val scopeVfs = VfsFileInterface(context.applicationContext, rootsById = inventory.roots.associateBy { it.id })
        val importCtx = ImportContext(
            scanId = scanId,
            existingClaimIndex = existingClaimIndex,
            // Shared Claim Ledger Delegation (Ownership Preservation)
            // Integrates a copy of the scan-wide claim ledger to preserve audio track reservations across incremental scopes.
            runClaimLedger = runClaimLedger,
            sharedInventory = inventory,
            scopeFileReader = scopeVfs
        )

        // Execute Step 1: Manifest Parsing (Cue/M3u8 Parsing Stage)
        val manifestParsedResult = ImportTimingLogger.measure(
            scopeId = timingScopeId,
            stage = "orchestrator.manifestParse",
            detail = inventory.timingCountDetail()
        ) {
            // Processes CUE/M3U8 structures to resolve declared track names.
            manifestParseStep.execute(inventory, importCtx)
        }

        // Execute Step 2: Metadata Extraction (ID3 Resolution Stage)
        val resolvedMetadataDrafts = ImportTimingLogger.measure(
            scopeId = timingScopeId,
            stage = "orchestrator.metadataResolve",
            detail = "audio=${inventory.audioFiles.size}"
        ) {
            // Reads tags and metadata structures from loose audio tracks.
            metadataResolveStep.execute(manifestParsedResult, importCtx)
        }

        // Execute Step 3: Heuristic Grouping (Clustering Stage)
        val groupedBookDrafts = ImportTimingLogger.measure(
            scopeId = timingScopeId,
            stage = "orchestrator.heuristicGroup",
            detail = "loose=${resolvedMetadataDrafts.looseAudioMetadataRefs.size}"
        ) {
            // Compiles aggregation plans based on naming conventions and metadata alignment.
            heuristicGroupStep.execute(resolvedMetadataDrafts, importCtx)
        }

        // Execute Step 4: Deferred Cover Extraction (Asset Binding Stage)
        // Bypasses heavy decoding of file payloads; binds pre-read cover bytes to caches, leaving missing assets to async helpers.
        val coverExtractedResult = ImportTimingLogger.measure(
            scopeId = timingScopeId,
            stage = "orchestrator.coverDefer",
            detail = "cue=${groupedBookDrafts.manifestParsedResult.cueDrafts.size} m3u8=${groupedBookDrafts.manifestParsedResult.m3u8Drafts.size} aggregated=${groupedBookDrafts.aggregatedPlans.size} single=${groupedBookDrafts.singleAudios.size}"
        ) {
            groupedBookDrafts.toDeferredCoverResult(inventory, scopeVfs)
        }

        // Execute Step 5: Ownership Decision (Conflict and Draft Compilation Stage)
        val claimDecidedResult = ImportTimingLogger.measure(
            scopeId = timingScopeId,
            stage = "orchestrator.conflictClaim",
            detail = "cue=${coverExtractedResult.cueBooks.size} m3u8=${coverExtractedResult.m3u8Books.size} aggregated=${coverExtractedResult.aggregatedBooks.size} single=${coverExtractedResult.singleBooks.size}"
        ) {
            // Runs conflict resolution rules to compile the finalized BookDraft elements.
            ownershipClaimStep.execute(coverExtractedResult, importCtx)
        }

        // Export Finalized Commands (Pipeline Output)
        claimDecidedResult
    }

    // Pre-Resolved Scoped Run (Performance Optimization)
    // Leverages pre-extracted metadata to run grouping without performing duplicate track parses.
    internal suspend fun runWithPreResolvedAudio(
        scanId: String,
        inventory: FileInventory,
        existingClaimIndex: ExistingClaimIndex,
        runClaimLedger: RunClaimLedger,
        looseAudioMetadataRefs: List<AudioMetadataRef>,
        timingScopeId: String = "directory-audio:$scanId"
    ): ImportRunResult = withContext(Dispatchers.IO) {
        // Initialize Scoped VFS Facade (Storage Decoupling)
        // Allocates a directory-scoped VfsFileInterface facade to route file operations.
        val scopeVfs = VfsFileInterface(context.applicationContext, rootsById = inventory.roots.associateBy { it.id })
        val importCtx = ImportContext(
            scanId = scanId,
            existingClaimIndex = existingClaimIndex,
            // Retain Scoped Claim Ledger (State Isolation)
            // Restricts updates to the provided ledger copy to keep transactional boundaries intact.
            runClaimLedger = runClaimLedger,
            sharedInventory = inventory,
            scopeFileReader = scopeVfs
        )

        // Process Loose Files Directly (Pipeline Routing Rules)
        // Bypasses manifest parses, routing pre-read audio references straight to clustering steps.
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
            // Compile aggregation plans based on loose track structures.
            heuristicGroupStep.execute(resolvedMetadataDrafts, importCtx)
        }

        // Defer Cover Writes for Batches (Asset Optimization)
        // Binds metadata cover bytes to prevent reading heavy MP4 wrappers again.
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
            // Formulates finalized ownership decisions and resolves draft mapping details.
            ownershipClaimStep.execute(coverExtractedResult, importCtx)
        }
    }

    // Format Scoped File Counts (Diagnostics Metrics)
    // Renders brief inventory counts to contextualize performance metrics.
    private fun FileInventory.timingCountDetail(): String =
        "cue=${cueFiles.size} m3u8=${m3u8Files.size} audio=${audioFiles.size} imageParents=${imageFilesByParent.size}"

    // Bind Pre-Read Cover Assets (Performance Optimization)
    // Matches pre-read cover bytes into CoverExtractedResult, allowing default recovery logic to handle missing assets.
    private suspend fun GroupedBookDrafts.toDeferredCoverResult(
        inventory: FileInventory,
        fileReader: VfsFileInterface
    ): CoverExtractedResult {
        // Query Tracks via VFS Keys (Storage Decoupling)
        // Matches tracks using stable VFS coordinates, removing obsolete URI association pathways.
        val audioByVfsKey = inventory.audioFiles.associateBy { it.vfsKey }
        return CoverExtractedResult(
            cueBooks = manifestParsedResult.cueDrafts.map { cueDraft ->
                // Map Tracks in Scope (Decoupled Resolution)
                // Maps track listings from local scope inventories to prevent cover processing from blocking claim logic.
                val audioRefs =
                    cueDraft.resolvedAudioKeys.values.mapNotNull { key -> audioByVfsKey[key] }
                val coverResult = cueDraft.result.sidecarCoverFile?.let { sidecarFile ->
                    // Save Selected Sidecar Covers (Asset Processing)
                    // Commits the sidecar candidate selected by the parser without repeating image searches.
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
                // Compile Playlist Track Listings (Decoupled Resolution)
                // Retains resolved track lists to run claim and chapter steps instantly while deferring cover operations.
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
                // Resolve Heuristic Cover Options (Asset Processing)
                // Binds sidecar covers or falls back to pre-read embedded bytes; writes empty covers if both options fail.
                val bookId = UUID.randomUUID().toString()
                val coverResult = plan.sidecarCoverFile?.let { sidecarFile ->
                    saveExternalSidecarCover(sidecarFile, fileReader)
                } ?: savePreReadEmbeddedCover(plan.chapters.map { it.audio })
                CoverExtractedAggregated(bookId, plan, coverResult)
            },
            singleBooks = singleAudios.map { audioRef ->
                // Resolve Single File Cover (Asset Processing)
                // Reuses cover bytes from the metadata stage, keeping a null placeholder if no image exists.
                val bookId = UUID.randomUUID().toString()
                val coverResult = savePreReadEmbeddedCover(listOf(audioRef))
                CoverExtractedSingle(bookId, audioRef, coverResult)
            }
        )
    }

    // Write External Sidecar Cover (Asset Processing)
    // Extracts and writes out sidecar cover files using the shared VFS interface.
    private suspend fun saveExternalSidecarCover(sidecarFile: FileRef, fileReader: VfsFileInterface): CoverExtractor.CoverResult? {
        val result = coverExtractor.processExternalImage(sidecarFile.vfsKey) { fileReader.open(sidecarFile) }
        return result.takeIf { it.hasImage() }
    }

    // Write Embedded Cover (Asset Processing)
    // Binds embedded cover bytes gathered during the metadata stage to avoid re-opening track streams.
    private suspend fun savePreReadEmbeddedCover(audioRefs: List<AudioMetadataRef>): CoverExtractor.CoverResult? {
        // Iterate and process audio metadata references to extract pre-cached cover bytes.
        for (audioRef in audioRefs) {
            val cover = audioRef.embeddedCover ?: continue
            val result = coverExtractor.saveEmbeddedImage("${audioRef.file.vfsKey}:covr", cover.bytes)
            if (result.hasImage()) return result
        }
        return null
    }

    private fun CoverExtractor.CoverResult.hasImage(): Boolean =
        // Check Image Availability (Prevent Redundant Image Restoration)
        // Returns true if either the original cover or its thumbnail path is successfully written.
        originalPath != null || thumbnailPath != null
}