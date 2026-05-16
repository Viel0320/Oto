package com.viel.aplayer.ui.viewmodel

import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.viel.aplayer.data.ChapterEntity
import com.viel.aplayer.data.LibraryRepository
import com.viel.aplayer.playback.PlaybackManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 媒体播放逻辑委托类。
 * 封装与 Media3 PlaybackManager 的交互逻辑。
 */
class MediaPlaybackDelegate(
    private val playbackManager: () -> PlaybackManager?,
    private val repository: LibraryRepository,
    private val scope: CoroutineScope
) {
    fun play() = playbackManager()?.play()
    fun pause() = playbackManager()?.pause()
    fun seekTo(positionMs: Long) = playbackManager()?.seekTo(positionMs)
    fun setPlaybackSpeed(speed: Float) = playbackManager()?.setPlaybackSpeed(speed)

    /**
     * 加载媒体资源。
     */
    fun loadMedia(
        uri: Uri,
        title: String,
        author: String,
        narrator: String,
        startPositionMs: Long,
        playWhenReady: Boolean,
        onCoverUpdate: (String?) -> Unit
    ) {
        val metadataBuilder = MediaMetadata.Builder().setTitle(title)
        if (author != "Unknown Author") metadataBuilder.setArtist(author)
        if (narrator.isNotBlank()) metadataBuilder.setComposer(narrator)

        val extension = MimeTypeMap.getFileExtensionFromUrl(uri.toString())
        val mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension.lowercase()) ?: run {
            when {
                uri.toString().endsWith(".m4b", ignoreCase = true) -> "audio/mp4"
                uri.toString().endsWith(".m4a", ignoreCase = true) -> "audio/mp4"
                else -> "audio/mpeg"
            }
        }

        val mediaItem = MediaItem.Builder()
            .setMediaId(uri.toString())
            .setUri(uri)
            .setMimeType(mimeType)
            .setMediaMetadata(metadataBuilder.build())
            .build()
            
        playbackManager()?.let { manager ->
            manager.setMediaItem(mediaItem, startPositionMs)
            if (playWhenReady) manager.play()
        }

        // 轮询封面路径
        scope.launch {
            repeat(5) {
                val entity = repository.getByUri(uri.toString())
                if (entity != null && (entity.coverPath != null || entity.thumbnailPath != null)) {
                    onCoverUpdate(entity.coverPath)
                    return@launch
                }
                delay(1000)
            }
        }
    }

    /**
     * 跳转到下一章节。
     */
    fun skipToNextChapter(chapters: List<ChapterEntity>, currentPosition: Long) {
        if (chapters.isEmpty()) return
        val currentIndex = chapters.indexOfLast { currentPosition >= it.startPosition }
        if (currentIndex != -1 && currentIndex < chapters.size - 1) {
            seekTo(chapters[currentIndex + 1].startPosition)
        }
    }

    /**
     * 跳转到上一章节。
     */
    fun skipToPreviousChapter(chapters: List<ChapterEntity>, currentPosition: Long) {
        if (chapters.isEmpty()) return
        val currentIndex = chapters.indexOfLast { currentPosition >= it.startPosition }
        if (currentIndex != -1) {
            if (currentPosition - chapters[currentIndex].startPosition > 3000) {
                seekTo(chapters[currentIndex].startPosition)
            } else if (currentIndex > 0) {
                seekTo(chapters[currentIndex - 1].startPosition)
            }
        }
    }
}
