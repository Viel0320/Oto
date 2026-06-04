package com.viel.aplayer.media.service

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

    // Interruption State Flag (Indicates if playback was paused passively due to transient audio focus loss)
    // Directs the system to restore the previous state silently when focus is regained.
    private var isPausedByLossOfFocus = false

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
                        // Permanent loss: reset states and pause player.
                        isPausedByLossOfFocus = false
                        player.pause()
                        com.viel.aplayer.logger.AudioFocusLogger.logPermanentLoss()
                    }
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                        // Transient loss: pause and flag recovery.
                        if (player.isPlaying) {
                            isPausedByLossOfFocus = true
                            // Critical Interaction: Instruct AutoRewindManager to bypass the upcoming automatic rewind trigger.
                            // Ensures smooth continuity without repeating audio segments after small notifications.
                            AutoRewindManager.getInstance(appContext).ignoreNextAutoRewind = true
                            player.pause()
                            com.viel.aplayer.logger.AudioFocusLogger.logTransientLoss()
                        }
                    }
                    AudioManager.AUDIOFOCUS_GAIN -> {
                        // Focus Regained: Auto-resume if paused passively.
                        if (isPausedByLossOfFocus) {
                            isPausedByLossOfFocus = false
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
                    // Playback started: clear transient flags and request audio focus.
                    isPausedByLossOfFocus = false
                    requestMyAudioFocus()
                } else {
                    // Playback paused: abandon focus if not passively interrupted.
                    if (!isPausedByLossOfFocus) {
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
        isPausedByLossOfFocus = false
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
