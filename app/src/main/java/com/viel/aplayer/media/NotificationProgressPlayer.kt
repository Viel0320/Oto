package com.viel.aplayer.media

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.viel.aplayer.data.entity.BookFileEntity
import com.viel.aplayer.data.entity.ChapterEntity
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Notification Playback Window Wrapper (Expose chapter or full-book bounds to system notifications)
 */
@OptIn(UnstableApi::class)
class NotificationProgressPlayer(player: Player) : ForwardingPlayer(player) {
    private val sessionListeners = CopyOnWriteArraySet<Player.Listener>()

    private var bookId: String? = null
    private var files: List<BookFileEntity> = emptyList()
    private var chapters: List<ChapterEntity> = emptyList()
    private var isChapterMode: Boolean = false
    // Track Previous Display Boundaries (Detect transition events across chapter indexes within one track)
    private var lastDisplayWindow: DisplayWindow? = null

    // ForwardingPlayer wraps callbacks for normal player events; we keep the original listeners for manual timeline updates.
    override fun addListener(listener: Player.Listener) {
        sessionListeners.add(listener)
        super.addListener(listener)
    }

    // Manual listener bookkeeping mirrors addListener so mode changes stop notifying removed MediaSession listeners.
    override fun removeListener(listener: Player.Listener) {
        sessionListeners.remove(listener)
        super.removeListener(listener)
    }

    // The service updates this whenever playback switches to a file from another book.
    fun updateBookTimeline(bookId: String, files: List<BookFileEntity>, chapters: List<ChapterEntity>) {
        this.bookId = bookId
        this.files = files.sortedBy { it.index }
        this.chapters = chapters.sortedBy { it.startPositionMs }
        // Reset Cache Limits (Clear stored display boundaries when metadata is updated)
        lastDisplayWindow = null
        notifyTimelineShapeChanged()
    }

    // Settings changes only affect the displayed progress window, not actual ExoPlayer playback.
    fun setChapterMode(enabled: Boolean) {
        if (isChapterMode == enabled) return
        isChapterMode = enabled
        // 模式切换会改变通知进度条长度，需要立刻通知 MediaSession 刷新。
        lastDisplayWindow = null
        notifyTimelineShapeChanged()
    }

    // Bookmarks and persistence need the stable global position, regardless of notification display mode.
    fun currentGlobalPosition(): Long = currentRawGlobalPosition()


    // Notification seek bars read duration from MediaSession; expose current chapter length when chapter mode is enabled.
    override fun getDuration(): Long = currentDisplayWindow().also(::refreshNotificationWindowIfNeeded).durationMs

    // Notification seek bars read current position from MediaSession; expose relative chapter progress when needed.
    override fun getCurrentPosition(): Long = currentDisplayWindow().also(::refreshNotificationWindowIfNeeded).positionMs

    // Keep buffering proportional to the displayed timeline so Android notification progress remains coherent.
    override fun getBufferedPosition(): Long =
        currentDisplayWindowWithBuffer(currentBufferedGlobalPosition()).also(::refreshNotificationWindowIfNeeded).bufferedPositionMs

    override fun getBufferedPercentage(): Int {
        val duration = duration
        return if (duration > 0) ((bufferedPosition * 100L) / duration).toInt().coerceIn(0, 100) else 0
    }

    // Expose Original contentPosition (Ensure real playback offset is preserved for internal diagnostics)
    override fun getContentPosition(): Long = wrappedPlayer.contentPosition.coerceKnown()

    // Expose Original contentDuration (Ensure total stream duration is preserved from window overrides)
    override fun getContentDuration(): Long = wrappedPlayer.contentDuration.coerceKnown()

    // A notification seek in the current displayed window is mapped back to the real playlist item and file offset.
    override fun seekTo(positionMs: Long) {
        seekDisplayPosition(positionMs)
    }

    // App-internal seeks already provide the real file index and file offset, so keep this path unmodified.
    override fun seekTo(mediaItemIndex: Int, positionMs: Long) {
        wrappedPlayer.seekTo(mediaItemIndex, positionMs)
    }

    // Notification rewind follows the same displayed timeline as the notification progress bar.
    override fun seekBack() {
        seekDisplayPosition((currentPosition - seekBackIncrement).coerceAtLeast(0L))
    }

    // Notification fast-forward follows the same displayed timeline as the notification progress bar.
    override fun seekForward() {
        seekDisplayPosition((currentPosition + seekForwardIncrement).coerceAtMost(duration))
    }

    private fun seekDisplayPosition(positionMs: Long) {
        val totalDuration = totalBookDuration()
        if (files.isEmpty() || totalDuration <= 0L) {
            wrappedPlayer.seekTo(positionMs)
            return
        }

        // Chapter mode treats notification seeks as relative to the current chapter; global mode uses the whole book.
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

        // Notification chapter mode uses the same chapter boundaries as the Compose UI.
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
        // Notify MediaSession that duration/currentPosition semantics changed even though ExoPlayer media did not.
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
            // Force Notification Repaints (Trigger updates when crossing chapter bounds within the same physical file)
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