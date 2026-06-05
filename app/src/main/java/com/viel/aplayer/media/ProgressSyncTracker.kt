package com.viel.aplayer.media

import android.content.Context
import androidx.media3.session.MediaController
import com.viel.aplayer.abs.playback.AbsPlaybackSessionSyncer
import com.viel.aplayer.data.entity.BookProgressEntity
import com.viel.aplayer.data.gateway.BookQueryGateway
import com.viel.aplayer.data.gateway.ProgressGateway
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

/**
 * Progress Synchronization Tracker (Helper class decoupled from PlaybackManager singletons)
 * Handles high-frequency polling loops and manages database persistence operations.
 * Isolates progress mathematical calculation models and defines precise lifecycle controls.
 * 
 * Decouples the legacy LibraryRepository by splitting operations into BookQueryGateway and ProgressGateway.
 */
class ProgressSyncTracker(
    private val context: Context,
    private val bookQueryGateway: BookQueryGateway,
    private val progressGateway: ProgressGateway,
    private val absPlaybackSessionSyncer: AbsPlaybackSessionSyncer,
    private val scope: CoroutineScope,
    private val getController: () -> MediaController?,
    private val getCurrentPlan: () -> BookPlaybackPlan?,
    private val onProgressUpdated: (positionMs: Long, durationMs: Long) -> Unit
) {
    // Polling Job Coordinator (Coroutine job reference for active player progress loop, managed dynamically via playback states)
    private var pollingJob: Job? = null

    /**
     * Start Polling Routine (Spawns the high-frequency progress polling coroutine)
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
                    // State Emission (Propagates global playback positions and overall duration counts to state flows)
                    updateProgress(controller)
                    saveCounter++
                    if (saveCounter >= 20) {
                        saveCounter = 0
                        // Checkpoint Save (Persists current progress checkpoint to DB every 10 seconds)
                        saveProgressDirectly(controller)
                    }
                }
                // Dynamic Polling Interval (Delay 500ms during playback to keep UI precise; throttle to 2s when paused to save CPU)
                val delayTime = if (getController()?.isPlaying == true) 500L else 2000L
                delay(delayTime.milliseconds)
            }
        }
    }

    /**
     * Stop Polling Routine (Terminates the active progress polling coroutine)
     * Cancels the running job and cleans up references to prevent background coroutine leaks.
     */
    fun stopPolling() {
        pollingJob?.cancel()
        pollingJob = null
    }

    /**
     * Resolve Global Position (Calculates global position and duration relative to the current playback plan)
     * Queries MediaController indices and local offsets to resolve overall progress via PositionMapper algorithms.
     *
     * @param player The active MediaController instance
     */
    fun updateProgress(player: MediaController) {
        val plan = getCurrentPlan()
        if (plan != null && plan.files.isNotEmpty() && player.currentMediaItem != null) {
            val fileIndex = player.currentMediaItemIndex.coerceIn(0, plan.files.lastIndex)
            val positionInFile = player.currentPosition.coerceAtLeast(0L)
            val totalDur = plan.files.sumOf { it.durationMs }
            val globalPos = PositionMapper.fileToGlobalPosition(fileIndex, positionInFile, plan.files)
                .coerceIn(0L, totalDur.coerceAtLeast(0L))
            onProgressUpdated(globalPos, totalDur)
        } else {
            // Fallback Progress Check (Falls back to reporting raw media track position if no multi-file plan exists)
            onProgressUpdated(
                player.currentPosition.coerceAtLeast(0L),
                player.duration.coerceAtLeast(0L)
            )
        }
    }

    /**
     * Force Checkpoint Save (Executes progress serialization immediately upon state transitions, e.g., pause, seek, track skips)
     */
    fun saveProgress() {
        val controller = getController() ?: return
        saveProgressDirectly(controller)
    }

    /**
     * Database Checkpoint Write (Helper resolving mediaId components and writing BookProgressEntity snapshots to SQLite)
     *
     * @param controller The active MediaController instance
     */
    private fun saveProgressDirectly(controller: MediaController) {
        val mediaParts = PlaybackMediaId.parse(controller.currentMediaItem?.mediaId) ?: return
        val bookId = mediaParts.bookId
        val fileIndex = controller.currentMediaItemIndex.coerceAtLeast(0)
        val positionInFile = controller.currentPosition.coerceAtLeast(0L)

        scope.launch {
            // Fetch Track Configurations (Resolves all physical audio files associated with the book using bookQueryGateway)
            val files = bookQueryGateway.getFilesForBookSync(bookId)
            if (files.isNotEmpty()) {
                val globalPos = PositionMapper.fileToGlobalPosition(fileIndex, positionInFile, files)
                val bookFileId = files.getOrNull(fileIndex)?.id

                // Write Progress Checkpoint (Persists calculated progress coordinates to the DB using progressGateway)
                progressGateway.saveProgress(
                    BookProgressEntity(
                        bookId = bookId,
                        globalPositionMs = globalPos,
                        bookFileId = bookFileId,
                        currentFileIndex = fileIndex,
                        positionInFileMs = positionInFile,
                        lastPlayedAt = System.currentTimeMillis()
                    )
                )
                val book = bookQueryGateway.getBookById(bookId)
                if (book != null) {
                    absPlaybackSessionSyncer.syncProgress(
                        book = book,
                        progress = BookProgressEntity(
                            bookId = bookId,
                            globalPositionMs = globalPos,
                            bookFileId = bookFileId,
                            currentFileIndex = fileIndex,
                            positionInFileMs = positionInFile,
                            lastPlayedAt = System.currentTimeMillis()
                        ),
                        durationMs = files.sumOf { it.durationMs }
                    )
                }
            }
        }
    }

    /**
     * Direct Progress Persistence (Manually overrides progress state, bypassing active player queries)
     * Utilized during explicit seeks or plan loading, writing changes via background threads.
     *
     * @param bookId Target audiobook identifier
     * @param fileIndex Current track index inside the playlist
     * @param positionInFile Local offset in milliseconds relative to the track file
     */
    fun persistProgress(bookId: String, fileIndex: Int, positionInFile: Long) {
        scope.launch {
            // Fetch Track Configs Sync (Synchronously retrieves track configurations via the read-only gateway)
            val files = bookQueryGateway.getFilesForBookSync(bookId)
            if (files.isNotEmpty()) {
                val safeFileIndex = fileIndex.coerceIn(0, files.lastIndex)
                val safePositionInFile = positionInFile.coerceAtLeast(0L)
                val globalPos = PositionMapper.fileToGlobalPosition(safeFileIndex, safePositionInFile, files)
                    .coerceIn(0L, files.sumOf { it.durationMs }.coerceAtLeast(0L))
                val bookFileId = files.getOrNull(safeFileIndex)?.id

                // Commit Localization Checkpoint (Saves high-precision seek positions to the progress DB)
                progressGateway.saveProgress(
                    BookProgressEntity(
                        bookId = bookId,
                        globalPositionMs = globalPos,
                        bookFileId = bookFileId,
                        currentFileIndex = safeFileIndex,
                        positionInFileMs = safePositionInFile,
                        lastPlayedAt = System.currentTimeMillis()
                    )
                )
                val book = bookQueryGateway.getBookById(bookId)
                if (book != null) {
                    absPlaybackSessionSyncer.syncProgress(
                        book = book,
                        progress = BookProgressEntity(
                            bookId = bookId,
                            globalPositionMs = globalPos,
                            bookFileId = bookFileId,
                            currentFileIndex = safeFileIndex,
                            positionInFileMs = safePositionInFile,
                            lastPlayedAt = System.currentTimeMillis()
                        ),
                        durationMs = files.sumOf { it.durationMs }
                    )
                }
            }
        }
    }
}
