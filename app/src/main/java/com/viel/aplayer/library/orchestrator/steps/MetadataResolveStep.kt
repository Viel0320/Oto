package com.viel.aplayer.library.orchestrator.steps

import android.content.Context
import androidx.media3.common.util.UnstableApi
import com.viel.aplayer.library.orchestrator.ImportContext
import com.viel.aplayer.library.orchestrator.mapWithBoundedConcurrency
import com.viel.aplayer.media.manifest.AudioMetadataRef
import com.viel.aplayer.media.parser.MetadataResolver

/**
 * Concurrently Parse Media ID3 Metadata (Performance Optimization)
 *
 * This step has been refactored to remove the legacy generic ImportStep<I, O> interface and StepResult wrapper.
 * The execute method now directly returns ResolvedMetadataDrafts, allowing exceptions to bubble up naturally.
 */
// Restrict Visibility to Internal (Prevent Compilation Errors)
// Restricting visibility prevents type exposure errors caused by leaking internal audio entities.

@UnstableApi
internal class MetadataResolveStep(
    private val context: Context,
    // Inject MetadataResolver Externally (Dependency Injection Decorator)
    // Enforce injection of MetadataResolver initialized by VfsFileInterface.
    private val metadataResolver: MetadataResolver
) {

    /**
     * Parse Media Metadata (Concurrent Extraction)
     *
     * Concurrently extracts metadata for loose audio files and returns ResolvedMetadataDrafts directly without wrapping.
     */
    suspend fun execute(
        input: ManifestParsedResult,
        context: ImportContext
    ): ResolvedMetadataDrafts {
        // Reserve CUE Audio Identities (Conflict Prevention)
        // Record all audio file identities declared in CUE manifests to prevent duplicate scans.
        // Query Via VFS File Keys (Storage Decoupling)
        // Use VFS file keys to lookup scan snapshots instead of relying on provider URIs.
        val audioByVfsKey = context.sharedInventory?.audioFiles.orEmpty().associateBy { it.vfsKey }
        input.cueDrafts.forEach { draft ->
            draft.resolvedAudioKeys.values.forEach { fileKey ->
                val audioRef = audioByVfsKey[fileKey]
                if (audioRef != null) {
                    context.reservedAudioIdentities.add(audioRef.identity)
                }
            }
        }

        // Reserve M3U8 Audio Identities (Conflict Prevention)
        // Record all audio file identities declared in M3U8 lists into the reserved ledger.
        input.m3u8Drafts.forEach { draft ->
            draft.resolvedAudioKeys.values.forEach { fileKey ->
                val audioRef = audioByVfsKey[fileKey]
                if (audioRef != null) {
                    context.reservedAudioIdentities.add(audioRef.identity)
                }
            }
        }

        // Filter Out Claimed Audio Files (Incremental Scanning)
        // Extract loose audio files that are neither reserved by manifests nor claimed in the database.
        val allAudios = context.sharedInventory?.audioFiles.orEmpty()
        val looseAudios = allAudios.filter { audio ->
            !context.reservedAudioIdentities.contains(audio.identity) &&
                    !context.existingClaimIndex.has(audio.identity)
        }

        // Concurrently Extract Loose Audio Metadata (Performance Optimization)
        // Extracts metadata with bounded concurrency to preserve memory, while claim arbitration runs downstream.
        val resolvedList = looseAudios.mapWithBoundedConcurrency { audio ->
            // Resolve Files Via VFS References (Storage Decoupling)
            // Open files directly through VFS file references instead of resolving URIs at this layer.
            // Request Metadata and Embedded Cover (Asset Recovery)
            // Explicitly load embedded covers so that MP4 metadata covers propagate to cover cache writers.
            val extracted = metadataResolver.extractWithEmbeddedCover(audio)
            AudioMetadataRef(audio, extracted.metadata, extracted.embeddedCover)
        }

        return ResolvedMetadataDrafts(
            manifestParsedResult = input,
            looseAudioMetadataRefs = resolvedList
        )
    }
}

/**
 * Metadata Extraction Results Wrapper (Type Safety)
 *
 * Wraps the output of concurrent metadata extraction steps, restricted to internal visibility to prevent leakage.
 */
internal data class ResolvedMetadataDrafts(
    // Propagate Manifest Parse Data (Pipeline Context Preservation)
    // Preserves parsed manifest drafts to allow downstream consolidation into book drafts.
    val manifestParsedResult: ManifestParsedResult,
    
    // List of Loose Audio References (Data Propagation)
    // References to loose audio files with extracted metadata properties.
    val looseAudioMetadataRefs: List<AudioMetadataRef>
)
