package com.viel.aplayer.media

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import com.viel.aplayer.data.AppSettingsRepository
import com.viel.aplayer.logger.PlaybackWorkflowLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Manage playback progress rewinds and state corrections.
 *
 * Coordinates state variables, parses configuration limits, applies rewinds upon pause,
 * and executes progress restoration at cold start. Designed for high cohesion to uncouple
 * secondary playback features from PlaybackManager.
 */
@OptIn(UnstableApi::class)
class AutoRewindManager internal constructor(
    context: Context,
    settingsRepository: AppSettingsRepository,
    private val bookCatalogGateway: com.viel.aplayer.data.book.BookCatalogGateway,
    private val progressGateway: com.viel.aplayer.data.progress.ProgressGateway
) {

    private val appContext = context.applicationContext

    private val settingsRepository = settingsRepository

    /**
     * Temporarily suppress next rewind action.
     *
     * When set to true, the subsequent pause trigger is bypassed. Helps avoid redundant rewinds
     * caused by transient audio focus loss, active user pause, or track switches.
     */
    var ignoreNextAutoRewind: Boolean = false

    /**
     * Handle progress rewinds upon state transition to pause.
     *
     * Invoked immediately when the player transitions from playing to paused state.
     *
     * @param controller The media controller interface used to seek time offsets.
     * @param currentPlan The active playback structure containing multi-file metrics for precision mapping.
     * @param scope The coroutine scope governing settings retrieval and progress persistence.
     * @param onProgressUpdated Notification callback dispatched when the seek operation finishes.
     * @param onSaveProgress Action callback triggering immediate database serialization.
     */
    fun handlePause(
        controller: MediaController?,
        currentPlan: BookPlaybackPlan?,
        scope: CoroutineScope,
        onProgressUpdated: (MediaController) -> Unit,
        onSaveProgress: () -> Unit
    ) {
        if (ignoreNextAutoRewind) {
            ignoreNextAutoRewind = false
            return
        }

        applyAutoRewind(controller, currentPlan, scope, onProgressUpdated, onSaveProgress)
    }

    /**
     * Perform calculations to adjust playback head backwards.
     *
     * Asynchronously retrieves the current rewind duration constraints, computes global timeline
     * offsets for multi-file configurations, and updates the media controller position.
     */
    private fun applyAutoRewind(
        controller: MediaController?,
        plan: BookPlaybackPlan?,
        scope: CoroutineScope,
        onProgressUpdated: (MediaController) -> Unit,
        onSaveProgress: () -> Unit
    ) {
        if (controller == null) return
        scope.launch {
            try {
                val settings = settingsRepository.settingsFlow.first()
                val rewindSeconds = settings.autoRewindSeconds
                if (rewindSeconds > 0) {
                    val rewindMs = rewindSeconds * 1000L

                    val seekTarget = AutoRewindPositionPolicy.playbackSeekTarget(
                        currentMediaItemIndex = controller.currentMediaItemIndex,
                        currentPositionMs = controller.currentPosition,
                        rewindMs = rewindMs,
                        files = plan?.files.orEmpty()
                    )
                    if (seekTarget.mediaItemIndex != null) {
                        controller.seekTo(seekTarget.mediaItemIndex, seekTarget.positionMs)
                    } else {
                        controller.seekTo(seekTarget.positionMs)
                    }

                    onProgressUpdated(controller)
                    onSaveProgress()
                }
            } catch (e: Exception) {
                PlaybackWorkflowLogger.error("autoRewind pause rewind failed", e)
            }
        }
    }

    /**
     * Perform progress self-healing for abnormally interrupted tracks.
     *
     * Triggered during application launch to offset positions if the previous session closed unexpectedly.
     * Computes target offsets and commits the corrected position to DB.
     */
     suspend fun performColdStartSelfHealing() {
        try {
            val settings = settingsRepository.settingsFlow.first()
            if (settings.isLastPlaybackInterrupted && settings.autoRewindSeconds > 0) {
                val lastProgress = progressGateway.getLastPlayedProgressSync()
                if (lastProgress != null) {
                    val rewindMs = settings.autoRewindSeconds * 1000L
                    val files = bookCatalogGateway.getFilesForBookSync(lastProgress.bookId)
                    val healedProgress = AutoRewindPositionPolicy.rewoundProgress(
                        progress = lastProgress,
                        rewindMs = rewindMs,
                        files = files,
                        now = System.currentTimeMillis()
                    )

                    progressGateway.saveProgress(healedProgress)
                    com.viel.aplayer.logger.AutoRewindLogger.logColdStartSelfHeal(
                        bookId = lastProgress.bookId,
                        rewindMs = rewindMs,
                        targetPositionMs = healedProgress.globalPositionMs
                    )
                }

                settingsRepository.updateLastPlaybackInterrupted(false)
            } else {
                settingsRepository.updateLastPlaybackInterrupted(false)
            }
        } catch (e: Exception) {
            PlaybackWorkflowLogger.error("autoRewind cold start self-heal failed", e)
        }
    }
}
