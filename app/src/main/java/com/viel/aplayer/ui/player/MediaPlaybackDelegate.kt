package com.viel.aplayer.ui.player

import com.viel.aplayer.application.library.player.PlayerChapterItem
import com.viel.aplayer.application.library.player.PlayerChapterTimeline
import com.viel.aplayer.application.library.player.PlayerLibraryReadModel
import com.viel.aplayer.application.playback.PlayerPlaybackController
import com.viel.aplayer.media.BookPlaybackPlan
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

/**
 * Controller routing requests to playback service.
 * Encapsulates player interactions through the playback controller seam.
 */
class MediaPlaybackDelegate(
    private val playbackController: () -> PlayerPlaybackController?,
    private val playerLibraryReadModel: PlayerLibraryReadModel,
    private val scope: CoroutineScope
) {
    fun play() = playbackController()?.play()
    fun pause() = playbackController()?.pause()
    fun seekTo(positionMs: Long) = playbackController()?.seekTo(positionMs)
    fun setPlaybackSpeed(speed: Float) = playbackController()?.setPlaybackSpeed(speed)

    /**
     * To pass playback plan coordinates to the media engine.
     */
    fun loadBook(
        plan: BookPlaybackPlan,
        playWhenReady: Boolean,
        onCoverUpdate: (String?) -> Unit
    ) {
        playbackController()?.loadPlaybackPlan(plan, playWhenReady)

        scope.launch {
            repeat(5) {
                val coverPath = playerLibraryReadModel.findDisplayCoverPath(plan.bookId)
                if (coverPath != null) {
                    onCoverUpdate(coverPath)
                    return@launch
                }
                delay(1000.milliseconds)
            }
        }
    }

    /**
     * To advance seek coordinates to start position of next chapter.
     */
    fun skipToNextChapter(chapters: List<PlayerChapterItem>, currentPosition: Long) {
        if (chapters.isEmpty()) return
        val sortedChapters = PlayerChapterTimeline.sorted(chapters)
        val currentIndex = sortedChapters.indexOfLast { currentPosition >= it.startPositionMs }
        if (currentIndex != -1 && currentIndex < chapters.size - 1) {
            seekTo(sortedChapters[currentIndex + 1].startPositionMs)
        }
    }

    /**
     * To rewind seek coordinates to start position of current/previous chapter.
     */
    fun skipToPreviousChapter(chapters: List<PlayerChapterItem>, currentPosition: Long) {
        if (chapters.isEmpty()) return
        val sortedChapters = PlayerChapterTimeline.sorted(chapters)
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
