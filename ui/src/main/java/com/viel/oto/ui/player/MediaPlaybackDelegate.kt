package com.viel.oto.ui.player

import com.viel.oto.application.library.player.PlayerChapterItem
import com.viel.oto.application.library.player.PlayerChapterTimeline
import com.viel.oto.application.library.player.PlayerLibraryReadModel
import com.viel.oto.application.playback.PlayerPlaybackController
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
     * Polls the display cover after playback preparation.
     * Cover recovery may finish shortly after the runtime accepts the playback request, so the UI
     * delegate keeps the retry window without learning the media-layer playback plan shape.
     */
    fun refreshCoverAfterLoad(
        bookId: String,
        onCoverUpdate: (String?) -> Unit
    ) {
        scope.launch {
            repeat(5) {
                val coverPath = playerLibraryReadModel.findDisplayCoverPath(bookId)
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
