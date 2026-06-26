package com.viel.oto.media.service

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import androidx.media3.common.Player
import com.viel.oto.data.AppSettingsRepository
import com.viel.oto.media.AutoRewindManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Represents side effects selected by the focus policy.
 * Keeps policy tests independent from Android Player instances while the manager still owns real playback calls.
 */
internal enum class AudioFocusPlaybackAction {
    None,
    Pause,
    Play
}

/**
 * Stores passive pause intent until Android grants focus again.
 * Separates focus-state decisions from service, player, and AudioManager side effects so recovery rules are directly unit-testable.
 */
internal class AudioFocusRecoveryPolicy {
    private var isPausedByLossOfFocus = false

    val isHoldingFocusLossPause: Boolean
        get() = isPausedByLossOfFocus

    fun onPlaybackStarted() {
        isPausedByLossOfFocus = false
    }

    fun onTransientLoss(isPlayerPlaying: Boolean): AudioFocusPlaybackAction {
        isPausedByLossOfFocus = isPlayerPlaying || isPausedByLossOfFocus
        return AudioFocusPlaybackAction.Pause
    }

    fun onPermanentLoss(): AudioFocusPlaybackAction {
        isPausedByLossOfFocus = false
        return AudioFocusPlaybackAction.Pause
    }

    fun onFocusGain(requestFocus: () -> Boolean): AudioFocusPlaybackAction {
        if (!isPausedByLossOfFocus) return AudioFocusPlaybackAction.None
        if (!requestFocus()) return AudioFocusPlaybackAction.None
        isPausedByLossOfFocus = false
        return AudioFocusPlaybackAction.Play
    }

    fun reset() {
        isPausedByLossOfFocus = false
    }
}

/**
 * Manages system-level audio focus states and handles ducking dynamics.
 * e.g. notifications, transient rings. occur.
 * Isolates low-level system broadcast events from the foreground service to secure overall stability.
 */
class PlaybackAudioFocusManager(
    context: Context,
    private val serviceScope: CoroutineScope,
    private val settingsRepository: AppSettingsRepository,
    private val autoRewindManager: AutoRewindManager,
    private val playerProvider: () -> Player?
) {
    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    private val focusRecoveryPolicy = AudioFocusRecoveryPolicy()

    private var audioFocusRequest: AudioFocusRequest? = null

    private val audioFocusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        val player = playerProvider() ?: return@OnAudioFocusChangeListener
        serviceScope.launch {
            val settings = settingsRepository.settingsFlow.first()
            if (settings.isNotificationAvoidanceEnabled) {
                when (focusChange) {
                    AudioManager.AUDIOFOCUS_LOSS -> {
                        if (focusRecoveryPolicy.onPermanentLoss() == AudioFocusPlaybackAction.Pause) {
                            player.pause()
                        }
                        com.viel.oto.logger.AudioFocusLogger.logPermanentLoss()
                    }
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                        val wasPlaying = player.isPlaying
                        if (focusRecoveryPolicy.onTransientLoss(wasPlaying) == AudioFocusPlaybackAction.Pause) {
                            if (wasPlaying) {
                                autoRewindManager.ignoreNextAutoRewind = true
                                com.viel.oto.logger.AudioFocusLogger.logTransientLoss()
                            }
                            player.pause()
                        }
                    }
                    AudioManager.AUDIOFOCUS_GAIN -> {
                        if (focusRecoveryPolicy.onFocusGain(::requestMyAudioFocus) == AudioFocusPlaybackAction.Play) {
                            player.play()
                            com.viel.oto.logger.AudioFocusLogger.logFocusRegained()
                        }
                    }
                }
            }
        }
    }

    /**
     * Aligns system focus parameters with the actual physical playback changes.
     */
    fun handlePlayerPlayingStateChanged(isPlaying: Boolean) {
        serviceScope.launch {
            val settings = settingsRepository.settingsFlow.first()
            if (settings.isNotificationAvoidanceEnabled) {
                if (isPlaying) {
                    focusRecoveryPolicy.onPlaybackStarted()
                    requestMyAudioFocus()
                } else {
                    if (!focusRecoveryPolicy.isHoldingFocusLossPause) {
                        abandonMyAudioFocus()
                    }
                }
            }
        }
    }

    /**
     * Clears local flags and abandons system audio focus request.
     */
    fun reset() {
        focusRecoveryPolicy.reset()
        abandonMyAudioFocus()
    }

    /**
     * Requests system focus using modern Android 8.0+ AudioFocusRequest APIs.
     */
    private fun requestMyAudioFocus(): Boolean {
        val manager = audioManager ?: return false
        val request = audioFocusRequest ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .build()
            )
            .setAcceptsDelayedFocusGain(false)
            .setOnAudioFocusChangeListener(audioFocusChangeListener)
            .build().also { audioFocusRequest = it }

        val result = manager.requestAudioFocus(request)
        val success = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        com.viel.oto.logger.AudioFocusLogger.logFocusRequested(success, result)
        return success
    }

    /**
     * Abandons the focus request using modern Android 8.0+ APIs.
     */
    private fun abandonMyAudioFocus() {
        val manager = audioManager ?: return
        audioFocusRequest?.let {
            val result = manager.abandonAudioFocusRequest(it)
            com.viel.oto.logger.AudioFocusLogger.logFocusAbandoned(result)
            audioFocusRequest = null
        }
    }
}
