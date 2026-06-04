package com.viel.aplayer.ui.player

import com.viel.aplayer.data.entity.ChapterEntity
import com.viel.aplayer.data.gateway.BookQueryGateway
import com.viel.aplayer.media.BookPlaybackPlan
import com.viel.aplayer.media.ChapterTimeline
import com.viel.aplayer.media.PlaybackManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

/**
 * Media playback delegate (Controller routing requests to playback service)
 * Encapsulates interaction workflows with ExoPlayer-backed PlaybackManager.
 */
class MediaPlaybackDelegate(
    private val playbackManager: () -> PlaybackManager?,
    private val repository: BookQueryGateway,
    private val scope: CoroutineScope
) {
    fun play() = playbackManager()?.play()
    fun pause() = playbackManager()?.pause()
    fun seekTo(positionMs: Long) = playbackManager()?.seekTo(positionMs)
    fun setPlaybackSpeed(speed: Float) = playbackManager()?.setPlaybackSpeed(speed)

    /**
     * Load audiobook (To pass playback plan coordinates to the media engine)
     */
    fun loadBook(
        plan: BookPlaybackPlan,
        playWhenReady: Boolean,
        onCoverUpdate: (String?) -> Unit
    ) {
        playbackManager()?.setBookPlaybackPlan(plan, playWhenReady)

        // Poll cover path (To query updated thumbnail and cover paths sequentially)
        scope.launch {
            repeat(5) {
                val book = repository.getBookById(plan.bookId)
                if (book != null && (book.coverPath != null || book.thumbnailPath != null)) {
                    // Player UI should use the cached thumbnail when available and fall back to the original cover.
                    onCoverUpdate(book.thumbnailPath ?: book.coverPath)
                    return@launch
                }
                delay(1000.milliseconds)
            }
        }
    }

    /**
     * Skip forward chapter (To advance seek coordinates to start position of next chapter)
     */
    fun skipToNextChapter(chapters: List<ChapterEntity>, currentPosition: Long) {
        if (chapters.isEmpty()) return
        val sortedChapters = ChapterTimeline.sorted(chapters)
        // Chapter navigation uses the same ordering as chapter boundary calculation.
        val currentIndex = sortedChapters.indexOfLast { currentPosition >= it.startPositionMs }
        if (currentIndex != -1 && currentIndex < chapters.size - 1) {
            seekTo(sortedChapters[currentIndex + 1].startPositionMs)
        }
    }

    /**
     * Skip backward chapter (To rewind seek coordinates to start position of current/previous chapter)
     */
    fun skipToPreviousChapter(chapters: List<ChapterEntity>, currentPosition: Long) {
        if (chapters.isEmpty()) return
        val sortedChapters = ChapterTimeline.sorted(chapters)
        // Chapter navigation uses sorted starts so single-file and aggregated books behave consistently.
        val currentIndex = sortedChapters.indexOfLast { currentPosition >= it.startPositionMs }
        if (currentIndex != -1) {
            if (currentPosition - sortedChapters[currentIndex].startPositionMs > 3000) {
                seekTo(sortedChapters[currentIndex].startPositionMs)
            } else if (currentIndex > 0) {
                seekTo(sortedChapters[currentIndex - 1].startPositionMs)
            }
        }
    }
}
