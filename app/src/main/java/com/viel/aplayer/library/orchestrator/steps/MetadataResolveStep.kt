package com.viel.aplayer.library.orchestrator.steps

import android.content.Context
import androidx.media3.common.util.UnstableApi
import com.viel.aplayer.library.orchestrator.ImportContext
import com.viel.aplayer.library.orchestrator.mapWithBoundedConcurrency
import com.viel.aplayer.media.manifest.AudioMetadataRef
import com.viel.aplayer.media.parser.MetadataResolver

/**
 * Extracts metadata for loose audio files that were not reserved by manifests or existing claims.
 */
@UnstableApi
internal class MetadataResolveStep(
    private val context: Context,
    private val metadataResolver: MetadataResolver
) {

    /**
     * Extracts loose-audio metadata with bounded concurrency and forwards the manifest drafts.
     */
    suspend fun execute(
        input: ManifestParsedResult,
        context: ImportContext
    ): ResolvedMetadataDrafts {
        val audioByVfsKey = context.sharedInventory?.audioFiles.orEmpty().associateBy { it.vfsKey }
        input.cueDrafts.forEach { draft ->
            draft.resolvedAudioKeys.values.forEach { fileKey ->
                val audioRef = audioByVfsKey[fileKey]
                if (audioRef != null) {
                    context.reservedAudioIdentities.add(audioRef.identity)
                }
            }
        }

        input.m3u8Drafts.forEach { draft ->
            draft.resolvedAudioKeys.values.forEach { fileKey ->
                val audioRef = audioByVfsKey[fileKey]
                if (audioRef != null) {
                    context.reservedAudioIdentities.add(audioRef.identity)
                }
            }
        }

        val allAudios = context.sharedInventory?.audioFiles.orEmpty()
        val looseAudios = allAudios.filter { audio ->
            !context.reservedAudioIdentities.contains(audio.identity) &&
                    !context.existingClaimIndex.has(audio.identity)
        }

        val resolvedList = looseAudios.mapWithBoundedConcurrency { audio ->
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
 * Carries parsed manifest drafts alongside metadata extracted from unclaimed loose audio files.
 */
internal data class ResolvedMetadataDrafts(
    val manifestParsedResult: ManifestParsedResult,

    val looseAudioMetadataRefs: List<AudioMetadataRef>
)
