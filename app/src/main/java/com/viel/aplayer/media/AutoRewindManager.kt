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
 * Automatic Rewind Coordinator (Manage playback progress rewinds and state corrections)
 *
 * Coordinates state variables, parses configuration limits, applies rewinds upon pause,
 * and executes progress restoration at cold start. Designed for high cohesion to uncouple
 * secondary playback features from PlaybackManager.
 */
@OptIn(UnstableApi::class)
class AutoRewindManager private constructor(context: Context) {

    // Application Environment Context (Prevent memory leaks by capturing applicationContext)
    private val appContext = context.applicationContext

    // Configuration Storage Reference (Access settings flow and toggle interruption state flags)
    private val settingsRepository = AppSettingsRepository.getInstance(appContext)

    /**
     * Bypassing Flag Control (Temporarily suppress next rewind action)
     *
     * When set to true, the subsequent pause trigger is bypassed. Helps avoid redundant rewinds
     * caused by transient audio focus loss, active user pause, or track switches.
     */
    var ignoreNextAutoRewind: Boolean = false

    /**
     * Pause Lifecycle Ingestion (Handle progress rewinds upon state transition to pause)
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
        // Suppression Verification (Check if the rewind should be bypassed)
        // If ignoreNextAutoRewind is active, this pause was triggered transiently. Reset the flag and abort.
        if (ignoreNextAutoRewind) {
            ignoreNextAutoRewind = false
            return
        }

        // Trigger Core Action (Execute seek and database synchronization)
        applyAutoRewind(controller, currentPlan, scope, onProgressUpdated, onSaveProgress)
    }

    /**
     * Execute Auto-Rewind Offset (Perform calculations to adjust playback head backwards)
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
                // Settings Synchronization (Ensure configuration values are fresh)
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
                        // Multi-Track Seek Dispatch (Applies the policy-mapped file coordinate to Media3)
                        // Position math lives in AutoRewindPositionPolicy, leaving the manager responsible only for controller side effects.
                        controller.seekTo(seekTarget.mediaItemIndex, seekTarget.positionMs)
                    } else {
                        // Single-Track Seek Dispatch (Uses current-item seek when no multi-file plan exists)
                        // This preserves the legacy fallback for ad-hoc playback states that do not have a BookPlaybackPlan.
                        controller.seekTo(seekTarget.positionMs)
                    }
                    
                    // Pipeline State Notification (Force client components to re-collect position state)
                    onProgressUpdated(controller)
                    // Persistence Dispatch (Commit positions to database immediately to avoid data loss)
                    onSaveProgress()
                }
            } catch (e: Exception) {
                PlaybackWorkflowLogger.error("autoRewind pause rewind failed", e)
            }
        }
    }

    /**
     * Cold Start Restoration (Perform progress self-healing for abnormally interrupted tracks)
     *
     * Triggered during application launch to offset positions if the previous session closed unexpectedly.
     * Computes target offsets and commits the corrected position to DB.
     */
     suspend fun performColdStartSelfHealing() {
        try {
            // Read Interrupted State (Acquire settings configuration)
            val settings = settingsRepository.settingsFlow.first()
            if (settings.isLastPlaybackInterrupted && settings.autoRewindSeconds > 0) {
                // Playback Recovery Dependency Ingestion (Resolve only timeline lookup and progress persistence)
                // Cold-start self-healing does not need playback session, scanner, or screen-facing dependencies.
                val recoveryDependencies = com.viel.aplayer.APlayerApplication.getPlaybackRecoveryDependencies(appContext)
                // Recovery Catalog Gateway Resolution (Use the recovery dependency's catalog-only seam)
                // Cold-start self-healing only needs file inventory for coordinate repair, not chapter or bookmark gateways.
                val bookCatalogGateway = recoveryDependencies.bookCatalogGateway
                val progressGateway = recoveryDependencies.progressGateway

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
                
                // State Rejection Toggle (Clear interrupted flag upon successful correction)
                settingsRepository.updateLastPlaybackInterrupted(false)
            } else {
                // Clear Stale States (Reset flag to prevent legacy contamination)
                settingsRepository.updateLastPlaybackInterrupted(false)
            }
        } catch (e: Exception) {
            PlaybackWorkflowLogger.error("autoRewind cold start self-heal failed", e)
        }
    }

    companion object {
        @Volatile
        private var INSTANCE: AutoRewindManager? = null

        /**
         * Singleton Provider (Ensure thread-safe double-checked instantiation)
         */
        fun getInstance(context: Context): AutoRewindManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: AutoRewindManager(context).also { INSTANCE = it }
            }
        }
    }
}
