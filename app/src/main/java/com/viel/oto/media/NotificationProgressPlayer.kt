package com.viel.oto.media

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.viel.oto.data.entity.BookFileEntity
import com.viel.oto.data.entity.ChapterEntity
import com.viel.oto.timeline.PositionMapper
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Expose chapter or full-book bounds to system notifications.
 */
@OptIn(UnstableApi::class)
class NotificationProgressPlayer(player: Player) : ForwardingPlayer(player) {
    private val sessionListeners = CopyOnWriteArraySet<Player.Listener>()

    private var bookId: String? = null
    private var files: List<BookFileEntity> = emptyList()
    private var chapters: List<ChapterEntity> = emptyList()
    private var isChapterMode: Boolean = false
    private var seekBackIncrementMs: Long = 10_000L
    private var seekForwardIncrementMs: Long = 20_000L
    private var lastDisplayWindow: DisplayWindow? = null

    override fun addListener(listener: Player.Listener) {
        sessionListeners.add(listener)
        super.addListener(listener)
    }

    override fun removeListener(listener: Player.Listener) {
        sessionListeners.remove(listener)
        super.removeListener(listener)
    }

    fun updateBookTimeline(bookId: String, files: List<BookFileEntity>, chapters: List<ChapterEntity>) {
        this.bookId = bookId
        this.files = files.sortedBy { it.index }
        this.chapters = chapters.sortedBy { it.startPositionMs }
        lastDisplayWindow = null
        notifyTimelineShapeChanged()
    }

    fun setChapterMode(enabled: Boolean) {
        if (isChapterMode == enabled) return
        isChapterMode = enabled
        lastDisplayWindow = null
        notifyTimelineShapeChanged()
    }

    fun currentGlobalPosition(): Long = currentRawGlobalPosition()

    fun setSeekIncrements(backwardMs: Long, forwardMs: Long) {
        seekBackIncrementMs = backwardMs.coerceAtLeast(0L)
        seekForwardIncrementMs = forwardMs.coerceAtLeast(0L)
    }


    override fun getDuration(): Long = currentDisplayWindow().also(::refreshNotificationWindowIfNeeded).durationMs

    override fun getCurrentPosition(): Long = currentDisplayWindow().also(::refreshNotificationWindowIfNeeded).positionMs

    override fun getBufferedPosition(): Long =
        currentDisplayWindowWithBuffer(currentBufferedGlobalPosition()).also(::refreshNotificationWindowIfNeeded).bufferedPositionMs

    override fun getBufferedPercentage(): Int {
        val duration = duration
        return if (duration > 0) ((bufferedPosition * 100L) / duration).toInt().coerceIn(0, 100) else 0
    }

    override fun getContentPosition(): Long = wrappedPlayer.contentPosition.coerceKnown()

    override fun getContentDuration(): Long = wrappedPlayer.contentDuration.coerceKnown()

    override fun getSeekBackIncrement(): Long = seekBackIncrementMs

    override fun getSeekForwardIncrement(): Long = seekForwardIncrementMs

    override fun seekTo(positionMs: Long) {
        seekDisplayPosition(positionMs)
    }

    override fun seekTo(mediaItemIndex: Int, positionMs: Long) {
        wrappedPlayer.seekTo(mediaItemIndex, positionMs)
    }

    override fun seekBack() {
        seekDisplayPosition((currentPosition - seekBackIncrementMs).coerceAtLeast(0L))
    }

    override fun seekForward() {
        seekDisplayPosition((currentPosition + seekForwardIncrementMs).coerceAtMost(duration))
    }

    private fun seekDisplayPosition(positionMs: Long) {
        val totalDuration = totalBookDuration()
        if (files.isEmpty() || totalDuration <= 0L) {
            wrappedPlayer.seekTo(positionMs)
            return
        }

        val targetGlobal = if (isChapterMode && chapters.isNotEmpty()) {
            val window = currentDisplayWindow()
            (window.globalStartMs + positionMs.coerceIn(0L, window.durationMs)).coerceIn(0L, totalDuration)
        } else {
            positionMs.coerceIn(0L, totalDuration)
        }
        val (fileIndex, filePosition) = PositionMapper.globalToFilePosition(targetGlobal, files)
        wrappedPlayer.seekTo(fileIndex, filePosition)
    }

    private fun currentDisplayWindow(globalPosition: Long = currentRawGlobalPosition()): DisplayWindow {
        val totalDuration = totalBookDuration()
        if (totalDuration <= 0L) {
            val fallbackDuration = wrappedPlayer.duration.coerceKnown()
            val fallbackPosition = wrappedPlayer.currentPosition.coerceKnown().coerceAtMost(fallbackDuration)
            return DisplayWindow(0L, fallbackPosition, fallbackPosition, fallbackDuration)
        }

        if (!isChapterMode || chapters.isEmpty()) {
            val position = globalPosition.coerceIn(0L, totalDuration)
            return DisplayWindow(0L, position, position, totalDuration)
        }

        val chapter = ChapterTimeline.currentChapter(chapters, globalPosition)
        val chapterStart = ChapterTimeline.start(chapter)
        val chapterDuration = ChapterTimeline.duration(chapters, chapter, totalDuration)
        val chapterPosition = ChapterTimeline.positionInChapter(chapters, chapter, globalPosition, totalDuration)
        return DisplayWindow(chapterStart, chapterPosition, chapterPosition, chapterDuration)
    }

    private fun currentDisplayWindowWithBuffer(bufferedGlobalPosition: Long): DisplayWindow {
        val window = currentDisplayWindow()
        val bufferedPosition = if (isChapterMode && chapters.isNotEmpty()) {
            (bufferedGlobalPosition - window.globalStartMs).coerceIn(0L, window.durationMs)
        } else {
            bufferedGlobalPosition.coerceIn(0L, window.durationMs)
        }
        return window.copy(bufferedPositionMs = bufferedPosition)
    }

    private fun currentRawGlobalPosition(): Long {
        if (files.isEmpty()) return wrappedPlayer.currentPosition.coerceKnown()
        val fileIndex = wrappedPlayer.currentMediaItemIndex.coerceIn(0, files.lastIndex)
        return PositionMapper.fileToGlobalPosition(fileIndex, wrappedPlayer.currentPosition.coerceKnown(), files)
            .coerceIn(0L, totalBookDuration().coerceAtLeast(0L))
    }

    private fun currentBufferedGlobalPosition(): Long {
        if (files.isEmpty()) return wrappedPlayer.bufferedPosition.coerceKnown()
        val fileIndex = wrappedPlayer.currentMediaItemIndex.coerceIn(0, files.lastIndex)
        return PositionMapper.fileToGlobalPosition(fileIndex, wrappedPlayer.bufferedPosition.coerceKnown(), files)
            .coerceIn(0L, totalBookDuration().coerceAtLeast(0L))
    }

    private fun totalBookDuration(): Long = files.sumOf { it.durationMs }.takeIf { it > 0L } ?: 0L

    private fun Long.coerceKnown(): Long =
        if (this == C.TIME_UNSET || this < 0L) 0L else this

    private fun notifyTimelineShapeChanged() {
        val events = Player.Events(
            androidx.media3.common.FlagSet.Builder()
                .add(EVENT_TIMELINE_CHANGED)
                .add(EVENT_POSITION_DISCONTINUITY)
                .build()
        )
        sessionListeners.forEach { listener ->
            listener.onTimelineChanged(currentTimeline, TIMELINE_CHANGE_REASON_SOURCE_UPDATE)
            listener.onEvents(this, events)
        }
    }

    private fun refreshNotificationWindowIfNeeded(window: DisplayWindow) {
        val previous = lastDisplayWindow
        lastDisplayWindow = window
        if (previous == null) return
        if (previous.globalStartMs != window.globalStartMs || previous.durationMs != window.durationMs) {
            notifyTimelineShapeChanged()
        }
    }

    private data class DisplayWindow(
        val globalStartMs: Long,
        val positionMs: Long,
        val bufferedPositionMs: Long,
        val durationMs: Long
    )
}
