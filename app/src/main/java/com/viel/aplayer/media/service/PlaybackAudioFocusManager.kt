package com.viel.aplayer.media.service

// Import Alignment (Keep focus recovery free from timer dependencies)
// Playback recovery now waits for Android focus gain instead of scheduling delayed resume jobs.
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import androidx.media3.common.Player
import com.viel.aplayer.data.AppSettingsRepository
import com.viel.aplayer.media.AutoRewindManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Audio Focus Playback Action (Represents side effects selected by the focus policy)
 * Keeps policy tests independent from Android Player instances while the manager still owns real playback calls.
 */
internal enum class AudioFocusPlaybackAction {
    None,
    Pause,
    Play
}

/**
 * Audio Focus Recovery Policy (Stores passive pause intent until Android grants focus again)
 * Separates focus-state decisions from service, player, and AudioManager side effects so recovery rules are directly unit-testable.
 */
internal class AudioFocusRecoveryPolicy {
    private var isPausedByLossOfFocus = false

    // Passive Pause Retention (Prevents player pause callbacks from abandoning focus during system-caused interruptions)
    // The manager reads this flag when playback changes to distinguish user pauses from focus-loss pauses.
    val isHoldingFocusLossPause: Boolean
        get() = isPausedByLossOfFocus

    // Playback Start Reset (Clears stale passive-pause state when playback begins from a user or controller action)
    // A fresh playback start must own a new focus request instead of inheriting an old transient-loss recovery.
    fun onPlaybackStarted() {
        isPausedByLossOfFocus = false
    }

    // Focus Loss Hold (Pause until Android explicitly grants focus again)
    // A transient loss records that playback should resume only if the player was active or already paused by focus loss.
    fun onTransientLoss(isPlayerPlaying: Boolean): AudioFocusPlaybackAction {
        isPausedByLossOfFocus = isPlayerPlaying || isPausedByLossOfFocus
        return AudioFocusPlaybackAction.Pause
    }

    // Permanent Focus Loss Reset (Forget passive resume intent on unrecoverable focus loss)
    // Permanent loss means another owner has taken playback priority, so automatic resume must be blocked.
    fun onPermanentLoss(): AudioFocusPlaybackAction {
        isPausedByLossOfFocus = false
        return AudioFocusPlaybackAction.Pause
    }

    // Focus Gain Gate (Resume only after Android gain and a successful focus request)
    // A denied request keeps the passive-pause flag intact and prevents playback from restarting without system focus.
    fun onFocusGain(requestFocus: () -> Boolean): AudioFocusPlaybackAction {
        if (!isPausedByLossOfFocus) return AudioFocusPlaybackAction.None
        if (!requestFocus()) return AudioFocusPlaybackAction.None
        isPausedByLossOfFocus = false
        return AudioFocusPlaybackAction.Play
    }

    // Policy Reset (Clears passive interruption state when the service lifecycle resets)
    // This mirrors focus abandonment so a stopped service cannot resume playback from stale focus events.
    fun reset() {
        isPausedByLossOfFocus = false
    }
}

/**
 * Audio Focus & Notification Ducking Coordinator (Manages system-level audio focus states and handles ducking dynamics)
 * Coordinates pauses and automatic recoveries when temporary interruptions (e.g. notifications, transient rings) occur.
 * Isolates low-level system broadcast events from the foreground service to secure overall stability.
 */
class PlaybackAudioFocusManager(
    context: Context,
    private val serviceScope: CoroutineScope,
    private val settingsRepository: AppSettingsRepository,
    private val playerProvider: () -> Player?
) {
    // Safe Context Referencing (Utilizes application context to avoid service or activity memory leaks)
    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    // Focus Recovery Policy (Owns passive pause state and focus-gated resume decisions)
    // The manager delegates state transitions here so Android callbacks cannot restart playback on a timer.
    private val focusRecoveryPolicy = AudioFocusRecoveryPolicy()

    // AudioFocusRequest Cache (Caches request parameters for API 26+ to coordinate dynamic binding)
    private var audioFocusRequest: AudioFocusRequest? = null

    // Audio Focus Listener (Executes state transitions in response to concurrent system media preemptions)
    private val audioFocusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        val player = playerProvider() ?: return@OnAudioFocusChangeListener
        serviceScope.launch {
            // Dynamic settings load
            val settings = settingsRepository.settingsFlow.first()
            if (settings.isNotificationAvoidanceEnabled) {
                when (focusChange) {
                    AudioManager.AUDIOFOCUS_LOSS -> {
                        // Permanent Focus Loss Action (Pause and clear passive resume intent)
                        // The policy rejects future automatic recovery until a new playback start establishes focus again.
                        if (focusRecoveryPolicy.onPermanentLoss() == AudioFocusPlaybackAction.Pause) {
                            player.pause()
                        }
                        com.viel.aplayer.logger.AudioFocusLogger.logPermanentLoss()
                    }
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                        val wasPlaying = player.isPlaying
                        if (focusRecoveryPolicy.onTransientLoss(wasPlaying) == AudioFocusPlaybackAction.Pause) {
                            if (wasPlaying) {
                                // Auto-Rewind Suppression (Mark the system-caused pause before notifying the player)
                                // This avoids treating a temporary focus interruption like a user pause that should rewind on resume.
                                AutoRewindManager.getInstance(appContext).ignoreNextAutoRewind = true
                                com.viel.aplayer.logger.AudioFocusLogger.logTransientLoss()
                            }
                            player.pause()
                        }
                    }
                    AudioManager.AUDIOFOCUS_GAIN -> {
                        // Focus Gain Recovery (Resume only after Android gain and explicit focus request success)
                        // This blocks timer-based recovery and keeps playback paused when the system rejects focus.
                        if (focusRecoveryPolicy.onFocusGain(::requestMyAudioFocus) == AudioFocusPlaybackAction.Play) {
                            player.play()
                            com.viel.aplayer.logger.AudioFocusLogger.logFocusRegained()
                        }
                    }
                }
            }
        }
    }

    /**
     * Playing State Synchronization (Aligns system focus parameters with the actual physical playback changes)
     */
    fun handlePlayerPlayingStateChanged(isPlaying: Boolean) {
        serviceScope.launch {
            val settings = settingsRepository.settingsFlow.first()
            if (settings.isNotificationAvoidanceEnabled) {
                if (isPlaying) {
                    // Playback Start Focus Sync (Reset passive interruption state before requesting active focus)
                    // A user-initiated start should not be mistaken for recovery from an old transient loss.
                    focusRecoveryPolicy.onPlaybackStarted()
                    requestMyAudioFocus()
                } else {
                    // Playback Pause Focus Sync (Abandon focus only for non-passive pauses)
                    // Focus-loss pauses keep ownership state until Android sends gain or permanent loss.
                    if (!focusRecoveryPolicy.isHoldingFocusLossPause) {
                        abandonMyAudioFocus()
                    }
                }
            }
        }
    }

    /**
     * Focus Manager Reset (Clears local flags and abandons system audio focus request)
     */
    fun reset() {
        // Focus Policy Reset (Clear passive resume state before releasing the system focus request)
        // This prevents late focus callbacks from replaying audio after the service resets.
        focusRecoveryPolicy.reset()
        abandonMyAudioFocus()
    }

    /**
     * Audio Focus Acquisition (Requests system focus using modern Android 8.0+ AudioFocusRequest APIs)
     */
    private fun requestMyAudioFocus(): Boolean {
        val manager = audioManager ?: return false
        val request = audioFocusRequest ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH) // Tag as speech for optimized rendering
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .build()
            )
            .setAcceptsDelayedFocusGain(false) // Decline delayed focus to secure immediate response
            .setOnAudioFocusChangeListener(audioFocusChangeListener)
            .build().also { audioFocusRequest = it }
        
        val result = manager.requestAudioFocus(request)
        val success = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        com.viel.aplayer.logger.AudioFocusLogger.logFocusRequested(success, result)
        return success
    }

    /**
     * Audio Focus Release (Abandons the focus request using modern Android 8.0+ APIs)
     */
    private fun abandonMyAudioFocus() {
        val manager = audioManager ?: return
        audioFocusRequest?.let {
            val result = manager.abandonAudioFocusRequest(it)
            com.viel.aplayer.logger.AudioFocusLogger.logFocusAbandoned(result)
            audioFocusRequest = null
        }
    }
}
