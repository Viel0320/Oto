package com.viel.aplayer.ui.player

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.viel.aplayer.data.LibraryRepository
import com.viel.aplayer.data.entity.ChapterEntity
import com.viel.aplayer.media.BookPlaybackPlan
import com.viel.aplayer.media.ChapterTimeline
import com.viel.aplayer.media.PlaybackManager

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
     * 加载书籍。
     */
    fun loadBook(
        plan: BookPlaybackPlan,
        playWhenReady: Boolean,
        onCoverUpdate: (String?) -> Unit
    ) {
        playbackManager()?.let { manager ->
            manager.setBookPlaybackPlan(plan)
            if (playWhenReady) manager.play()
        }

        // 轮询封面路径
        scope.launch {
            repeat(5) {
                val book = repository.getBookById(plan.bookId)
                if (book != null && (book.coverPath != null || book.thumbnailPath != null)) {
                    // Player UI should use the cached thumbnail when available and fall back to the original cover.
                    onCoverUpdate(book.thumbnailPath ?: book.coverPath)
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
        val sortedChapters = ChapterTimeline.sorted(chapters)
        // Chapter navigation uses the same ordering as chapter boundary calculation.
        val currentIndex = sortedChapters.indexOfLast { currentPosition >= it.startPositionMs }
        if (currentIndex != -1 && currentIndex < chapters.size - 1) {
            seekTo(sortedChapters[currentIndex + 1].startPositionMs)
        }
    }

    /**
     * 跳转到上一章节。
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