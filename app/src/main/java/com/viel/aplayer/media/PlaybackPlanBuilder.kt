package com.viel.aplayer.media

import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata

/**
 * Converts BookPlaybackPlan domain models into standard Media3 MediaItem lists.
 * Decouples PlaybackManager by extracting MediaItem and MediaMetadata construction logic.
 * This simplifies player boundary interfaces and shields bridge layers from multi-track composition details.
 */
object PlaybackPlanBuilder {

    /**
     * Transforms the given BookPlaybackPlan into a list of MediaItem entities.
     * Shares identical artwork URIs across items to reduce IPC transfer overhead in cross-process sessions.
     * Attaches internal virtual file system (VFS) playback URIs and applies the plan's buffer
     * policy to the URI scheme that Media3 uses for LoadControl classification.
     *
     * @param plan The target BookPlaybackPlan model
     * @return Converted list of ExoPlayer-compatible MediaItems
     */
    fun buildMediaItems(plan: BookPlaybackPlan): List<MediaItem> {
        return plan.files.map { file ->
            val metadata = MediaMetadata.Builder()
                .setTitle(plan.title)
                .setArtist(plan.author)
                .setAlbumTitle(plan.title)
                .setArtworkUri(plan.artworkUri)
                .build()

            MediaItem.Builder()
                .setMediaId(PlaybackMediaId.compose(plan.bookId, file.id))
                .setUri(VfsPlaybackUri.fromBookFile(file, plan.bufferPolicy))
                .setMediaMetadata(metadata)
                .build()
        }
    }
}
