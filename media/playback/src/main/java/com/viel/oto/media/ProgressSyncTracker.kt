package com.viel.oto.media

import android.content.Context
import androidx.media3.session.MediaController
import com.viel.oto.data.book.BookCatalogGateway
import com.viel.oto.data.entity.BookFileEntity
import com.viel.oto.data.entity.BookProgressEntity
import com.viel.oto.data.progress.ProgressGateway
import com.viel.oto.timeline.PositionMapper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration.Companion.milliseconds

/**
 * Helper class decoupled from PlaybackManager singletons.
 * Handles high-frequency polling loops and manages database persistence operations.
 * Isolates progress mathematical calculation models and defines precise lifecycle controls.
 *
 * Decouples the legacy LibraryRepository by splitting operations into BookCatalogGateway and ProgressGateway.
 */
class ProgressSyncTracker(
    private val context: Context,
    private val bookCatalogGateway: BookCatalogGateway,
    private val progressGateway: ProgressGateway,
    private val remotePlaybackSessionSyncGateway: RemotePlaybackSessionSyncGateway,
    private val scope: CoroutineScope,
    private val getController: () -> MediaController?,
    private val getCurrentPlan: () -> BookPlaybackPlan?,
    private val onProgressUpdated: (positionMs: Long, durationMs: Long, bufferedPositionMs: Long) -> Unit
) {
    private var pollingJob: Job? = null

    private val progressWriteMutex = Mutex()

    private val progressSnapshotClock = AtomicLong(0L)

    /**
     * Spawns the high-frequency progress polling coroutine.
     * Refreshes UI progress flows every 500ms and commits state checkpoints to Room every 10s (20 cycles).
     * Automatically cancelled when the parent scope is destroyed or when stopPolling() is invoked.
     */
    fun startPolling() {
        if (pollingJob?.isActive == true) return
        pollingJob = scope.launch {
            var saveCounter = 0
            while (isActive) {
                val controller = getController()
                if (controller != null && controller.isPlaying) {
                    updateProgress(controller)
                    saveCounter++
                    if (saveCounter >= 20) {
                        saveCounter = 0
                        saveProgressDirectly(controller)
                    }
                }
                val delayTime = if (getController()?.isPlaying == true) 500L else 2000L
                delay(delayTime.milliseconds)
            }
        }
    }

    /**
     * Terminates the active progress polling coroutine.
     * Cancels the running job and cleans up references to prevent background coroutine leaks.
     */
    fun stopPolling() {
        pollingJob?.cancel()
        pollingJob = null
    }

    /**
     * Calculates global position and duration relative to the current playback plan.
     * Queries MediaController indices and local offsets to resolve overall progress via PositionMapper algorithms.
     *
     * @param player The active MediaController instance
     */
    fun updateProgress(player: MediaController) {
        val plan = getCurrentPlan()
        if (plan != null && plan.files.isNotEmpty() && player.currentMediaItem != null) {
            val fileIndex = player.currentMediaItemIndex.coerceIn(0, plan.files.lastIndex)
            val positionInFile = player.currentPosition.coerceAtLeast(0L)
            val totalDur = plan.totalDurationMs
            val globalPos = PositionMapper.fileToGlobalPosition(fileIndex, positionInFile, plan.files)
                .coerceIn(0L, totalDur.coerceAtLeast(0L))
            val bufferedInFile = player.bufferedPosition
                .coerceAtLeast(positionInFile)
            val globalBuffered = PositionMapper.fileToGlobalPosition(fileIndex, bufferedInFile, plan.files)
                .coerceIn(globalPos, totalDur.coerceAtLeast(0L))
            onProgressUpdated(globalPos, totalDur, globalBuffered)
        } else {
            val currentPosition = player.currentPosition.coerceAtLeast(0L)
            val duration = player.duration.coerceAtLeast(0L)
            val bufferedPosition = player.bufferedPosition
                .coerceAtLeast(currentPosition)
                .coerceIn(currentPosition, duration.coerceAtLeast(currentPosition))
            onProgressUpdated(
                currentPosition,
                duration,
                bufferedPosition
            )
        }
    }

    /**
     * Executes progress serialization immediately upon state transitions, e.g., pause, seek, track skips.
     */
    fun saveProgress() {
        val controller = getController() ?: return
        saveProgressDirectly(controller)
    }

    /**
     * Helper resolving mediaId components and writing BookProgressEntity snapshots to SQLite.
     *
     * @param controller The active MediaController instance
     */
    private fun saveProgressDirectly(controller: MediaController) {
        val mediaParts = PlaybackMediaId.parse(controller.currentMediaItem?.mediaId) ?: return
        enqueueProgress(
            ProgressSnapshot(
                bookId = mediaParts.bookId,
                fileIndex = controller.currentMediaItemIndex.coerceAtLeast(0),
                positionInFileMs = controller.currentPosition.coerceAtLeast(0L),
                lastPlayedAt = nextProgressSnapshotTime()
            )
        )
    }

    /**
     * Manually overrides progress state, bypassing active player queries.
     * Utilized during explicit seeks or plan loading, writing changes via background threads.
     *
     * @param bookId Target audiobook identifier
     * @param fileIndex Current track index inside the playlist
     * @param positionInFile Local offset in milliseconds relative to the track file
     */
    fun persistProgress(bookId: String, fileIndex: Int, positionInFile: Long) {
        enqueueProgress(
            ProgressSnapshot(
                bookId = bookId,
                fileIndex = fileIndex.coerceAtLeast(0),
                positionInFileMs = positionInFile.coerceAtLeast(0L),
                lastPlayedAt = nextProgressSnapshotTime()
            )
        )
    }

    private fun enqueueProgress(snapshot: ProgressSnapshot): Job = scope.launch {
        val files = bookCatalogGateway.getFilesForBookSync(snapshot.bookId)
        val progress = snapshot.toEntity(files) ?: return@launch
        val accepted = progressWriteMutex.withLock {
            progressGateway.saveProgressIfNewer(progress)
        }
        if (!accepted) return@launch
        val book = bookCatalogGateway.getBookById(snapshot.bookId)
        if (book != null) {
            remotePlaybackSessionSyncGateway.syncProgress(
                book = book,
                progress = progress,
                durationMs = files.sumOf { it.durationMs }
            )
        }
    }

    private fun nextProgressSnapshotTime(): Long {
        val wallClockTime = System.currentTimeMillis()
        return progressSnapshotClock.updateAndGet { previousTime ->
            maxOf(wallClockTime, previousTime + 1L)
        }
    }

    private data class ProgressSnapshot(
        val bookId: String,
        val fileIndex: Int,
        val positionInFileMs: Long,
        val lastPlayedAt: Long
    ) {
        fun toEntity(files: List<BookFileEntity>): BookProgressEntity? {
            if (files.isEmpty()) return null
            val safeFileIndex = fileIndex.coerceIn(0, files.lastIndex)
            val safePositionInFile = positionInFileMs.coerceAtLeast(0L)
            val totalDurationMs = files.sumOf { it.durationMs }.coerceAtLeast(0L)
            val globalPositionMs = PositionMapper.fileToGlobalPosition(safeFileIndex, safePositionInFile, files)
                .coerceIn(0L, totalDurationMs)
            return BookProgressEntity(
                bookId = bookId,
                globalPositionMs = globalPositionMs,
                bookFileId = files.getOrNull(safeFileIndex)?.id,
                currentFileIndex = safeFileIndex,
                positionInFileMs = safePositionInFile,
                lastPlayedAt = lastPlayedAt
            )
        }
    }
}
