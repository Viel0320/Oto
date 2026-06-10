package com.viel.aplayer.ui.settings

import android.content.Context
import com.viel.aplayer.application.playback.PlayerPlaybackController
import com.viel.aplayer.event.feedback.FeedbackMessage
import com.viel.aplayer.event.feedback.FeedbackMessages
import com.viel.aplayer.logger.SecureLog
import com.viel.aplayer.ui.player.BookMetadataState
import com.viel.aplayer.ui.player.PlaybackState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.math.ln
import kotlin.time.Duration.Companion.milliseconds

/**
 * Sleep Timer Manager: Orchestrates sleep countdown, fading, and movement tracking.
 *
 * Extracted from `PlayerSettingsManager` to encapsulate core sleep timer operations.
 * Handles the countdown ticker, logarithmic volume fade-out decay algorithm,
 * registration and denoising of physical accelerometer sensors, shake-to-extend logic,
 * and tactile motor feedback.
 * Operates as an independent engine, keeping the outer settings state and API interfaces unchanged.
 */
class SleepTimerManager(
    private val scope: CoroutineScope,
    private val playbackController: () -> PlayerPlaybackController?,
    private val contextProvider: () -> Context?,
    // Dynamic Config Lambdas (Access latest preferences on-demand)
    private val isSleepFadeOutEnabled: () -> Boolean,
    private val isShakeToResetEnabled: () -> Boolean,
    private val sleepMode: () -> com.viel.aplayer.data.store.SleepMode,
    // Application Feedback Callback (Reports timer tips without routing through playback-core events)
    // The timer engine still controls playback volume and pause, but user messages go through the app-level sink.
    private val onShowToast: (FeedbackMessage) -> Unit,
    private val selectedSleepTimer: () -> Int,
    // Timer Event Callbacks (Notify outer UI state coordinator)
    private val onTimerReset: () -> Unit,
    private val onTimerSelectedMinutesChanged: (Int) -> Unit
) {
    // State Stream (Expose remaining sleep duration)
    private val _sleepTimerMillis = MutableStateFlow(0L)
    val sleepTimerMillis: StateFlow<Long> = _sleepTimerMillis.asStateFlow()

    // Lifecycle Job (Manage countdown and fade execution scope)
    private var sleepTimerJob: Job? = null

    private var sensorManager: android.hardware.SensorManager? = null
    private var shakeListener: android.hardware.SensorEventListener? = null
    private var lastShakeTime = 0L

    // Motion Tracking State (Determine if the device is physically moving)
    @Volatile
    private var isDeviceMoving = false
    private var staticSampleCount = 0
    // Movement Cooldown Timestamp (Debounce high-frequency sensor updates)
    private var lastMovementTime = 0L
    // Pause Locking Timestamp (Enforce a one-minute temporary pause)
    private var lastActiveTriggerTime = 0L

    // Sleep Tracking State (Evaluate user sleep status)
    @Volatile
    private var hasUserFallenAsleep = false
    private var timeInStaticMs = 0L
    // Playback Transition Tracker (Invalidate sensor protection on manual playback changes)
    private var lastIsPlaying: Boolean? = null

    // Shake-to-Extend Exemption Memory (Record start position of skipped chapters)
    private var skippedChapterStartMs: Long? = null

    // Accelerometer Sensor Setup (On-demand physical activity detection)
    // Registers the sensor listener dynamically during the final 10-second fade-out window,
    // or during active motion/sleep tracking, minimizing battery drain while tracking physical state.
    private fun registerShakeListener(
        currentPlayback: () -> PlaybackState,
        currentMetadata: () -> BookMetadataState
    ) {
        val ctx = contextProvider() ?: return
        // Resource Conservation check (Skip registration if features are idle)
        if (!isShakeToResetEnabled() && sleepMode() == com.viel.aplayer.data.store.SleepMode.Regular) return
        if (sensorManager == null) {
            sensorManager = ctx.getSystemService(Context.SENSOR_SERVICE) as? android.hardware.SensorManager
        }
        if (shakeListener == null) {
            shakeListener = object : android.hardware.SensorEventListener {
                override fun onSensorChanged(event: android.hardware.SensorEvent?) {
                    if (event == null || event.sensor.type != android.hardware.Sensor.TYPE_ACCELEROMETER) return
                    
                    // Playback Change Detection (Identify manual play/pause toggle)
                    val playState = currentPlayback()
                    val isPlaying = playState.isPlaying
                    if (lastIsPlaying != null && lastIsPlaying != isPlaying) {
                        // Lock Release (Clear temporary sensor lock on playback change)
                        lastActiveTriggerTime = 0L
                    }
                    lastIsPlaying = isPlaying

                    // Sensor Intercept Evaluation (Suspend processing within lock duration)
                    if (System.currentTimeMillis() - lastActiveTriggerTime < 60000L) {
                        return
                    }

                    // G-Force Calculation (Compute total vector magnitude)
                    val gX = event.values[0] / android.hardware.SensorManager.GRAVITY_EARTH
                    val gY = event.values[1] / android.hardware.SensorManager.GRAVITY_EARTH
                    val gZ = event.values[2] / android.hardware.SensorManager.GRAVITY_EARTH
                    val gForce = kotlin.math.sqrt(gX * gX + gY * gY + gZ * gZ)

                    // Vibration Deviation Metric (Measure physical movement ignoring orientation)
                    val gDeviation = kotlin.math.abs(gForce - 1.0f)

                    // Mode-Specific Threshold (Adjust sensitivity based on sleep tracking vs motion tracking)
                    val movementThreshold = if (sleepMode() == com.viel.aplayer.data.store.SleepMode.SleepTracking) 0.08f else 0.035f

                    // Motion Determination (Declare active movement state)
                    if (gDeviation > movementThreshold) {
                        // Movement Toast Notification (Alert listener on movement detection)
                        if (!isDeviceMoving) {
                            if (sleepMode() == com.viel.aplayer.data.store.SleepMode.MotionTracking) {
                                showToast(FeedbackMessages.sleepMotionTrackingPaused())
                            }
                        }
                        isDeviceMoving = true
                        lastMovementTime = System.currentTimeMillis()
                        lastActiveTriggerTime = System.currentTimeMillis() // Timestamp lockout start
                        staticSampleCount = 0
                        timeInStaticMs = 0L
                        // Sleep Mode Reset (Reset sleep state on movement)
                        if (sleepMode() == com.viel.aplayer.data.store.SleepMode.SleepTracking && hasUserFallenAsleep) {
                            hasUserFallenAsleep = false
                            showToast(FeedbackMessages.sleepTrackingPausedByActivity())
                        }
                    } else {
                        // Cooldown Stabilization Period (Prevent premature static states)
                        if (System.currentTimeMillis() - lastMovementTime >= 3000L) {
                            staticSampleCount++
                            if (staticSampleCount >= 8) { // Require 8 consecutive static frames (~0.5s to 0.8s)
                                // Static Toast Notification (Alert listener on static state recovery)
                                if (isDeviceMoving) {
                                    if (sleepMode() == com.viel.aplayer.data.store.SleepMode.MotionTracking) {
                                        showToast(FeedbackMessages.sleepMotionTrackingResumed())
                                    }
                                }
                                isDeviceMoving = false
                            }
                        }
                    }

                    // Shake Detection Trigger (Acknowledge shake gesture with 2s cooldown)
                    if (gForce > 1.8f && isShakeToResetEnabled()) {
                        val now = System.currentTimeMillis()
                        if (now - lastShakeTime > 2000L) {
                            lastShakeTime = now
                            performShakeReset(currentPlayback, currentMetadata)
                        }
                    }
                }

                override fun onAccuracyChanged(sensor: android.hardware.Sensor?, accuracy: Int) {}
            }
        }

        val accel = sensorManager?.getDefaultSensor(android.hardware.Sensor.TYPE_ACCELEROMETER)
        accel?.let {
            sensorManager?.registerListener(shakeListener, it, android.hardware.SensorManager.SENSOR_DELAY_NORMAL)
        }
    }

    // Sensor Release (Unregister accelerometer listener)
    private fun unregisterShakeListener() {
        shakeListener?.let {
            sensorManager?.unregisterListener(it)
        }
        shakeListener = null
    }

    // Haptic Feedback Mechanism (Physical motor pulse confirmation)
    // Resolves unitialized vibrator issues by accessing the default vibrator through VibratorManager.
    private fun triggerVibration() {
        val ctx = contextProvider() ?: return
        try {
            val vibratorManager = ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? android.os.VibratorManager
            val vibrator = vibratorManager?.defaultVibrator

            if (vibrator?.hasVibrator() == true) {
                vibrator.vibrate(android.os.VibrationEffect.createOneShot(100, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
            }
        } catch (e: Exception) {
            // Vibration Feedback Failure Log (Fix lint rule violation by avoiding non-English hardcoded logs)
            // Changed the log error message from Chinese to English to pass UserVisibleStringResourceTest.
            SecureLog.error("SleepTimerManager", "Vibration feedback failed", e)
        }
    }

    // Timer Feedback Dispatch (Decouple toast UI actions via the app event sink callback)
    // Sends lightweight timer messages through PlayerViewModel so playback-core classes remain event-free.
    private fun showToast(message: FeedbackMessage) {
        scope.launch(kotlinx.coroutines.Dispatchers.Main) {
            onShowToast(message)
        }
    }

    // Decision Tree Algorithm (Evaluate shake extension targets)
    private fun performShakeReset(
        currentPlayback: () -> PlaybackState,
        currentMetadata: () -> BookMetadataState
    ) {
        if (!isShakeToResetEnabled()) return
        val currentSelected = selectedSleepTimer()
        if (currentSelected == -2) {
            // Chapter Boundary Mode (Pause playback at current chapter completion)
            val meta = currentMetadata()
            val state = currentPlayback()
            // Player Chapter Projection Use (Consume player-scene chapter items directly)
            // BookMetadataState already exposes Room-free timeline fields, so sleep timing no longer unwraps database relations.
            val chapters = meta.chapters
            // Track Current Segment (Locate active chapter)
            val currentChapter = chapters.findLast { state.currentPosition >= it.startPositionMs }
            val currentChapterIndex = if (currentChapter != null) chapters.indexOf(currentChapter) else -1
            // Boundary Feasibility Assessment (Verify next chapter presence)
            val hasNextChapter = currentChapterIndex != -1 && currentChapterIndex < chapters.size - 1

            if (hasNextChapter && currentChapter != null) {
                // Execute Chapter Skip (Apply shake reset to subsequent boundary)
                triggerVibration()
                skippedChapterStartMs = currentChapter.startPositionMs
                showToast(FeedbackMessages.sleepShakeExtendedToNextChapter())
                setSleepTimer(-2, currentPlayback, currentMetadata, isShakeReset = true)
            } else {
                // Fallback Terminal Boundary (Maintain standard pause at final track end)
                triggerVibration()
                showToast(FeedbackMessages.sleepShakeNoNextChapter())
            }
        } else if (currentSelected > 0) {
            // Regular Minute Mode (Reset countdown duration to initial configured length)
            triggerVibration()
            showToast(FeedbackMessages.sleepShakeCountdownReset())
            setSleepTimer(currentSelected, currentPlayback, currentMetadata, isShakeReset = true)
        } else if (currentSelected == -1) {
            // Diagnostics Test Mode (Reset debugging 5-second countdown)
            triggerVibration()
            showToast(FeedbackMessages.sleepShakeTestCountdownReset())
            setSleepTimer(currentSelected, currentPlayback, currentMetadata, isShakeReset = true)
        }
    }

    /**
     * Initialize Sleep Timer: Configures countdown behavior for three sleep tracking modes.
     *
     * Supports Regular, MotionTracking, and SleepTracking countdown modes. Handles logarithmic
     * audio volume fade-out. Implements a robust `try-finally` lifecycle wrapper to safely
     * unregister sensors and restore player volume on completion or cancellation, preventing battery leaks.
     */
    fun setSleepTimer(
        minutes: Int,
        currentPlayback: () -> PlaybackState,
        currentMetadata: () -> BookMetadataState,
        isShakeReset: Boolean = false
    ) {
        sleepTimerJob?.cancel()
        // State Synchronization callback (Update selected minutes settings)
        onTimerSelectedMinutesChanged(minutes)

        // Exemption Reset (Wipe skipped chapter cache on manual adjustment)
        if (!isShakeReset) {
            skippedChapterStartMs = null
        }

        if (minutes == 0) {
            _sleepTimerMillis.value = 0L
            unregisterShakeListener() // Unregister sensors when the timer is explicitly turned off.
            return
        }

        // Active Tracking Registration (Setup sensors immediately if in tracking mode)
        if (sleepMode() == com.viel.aplayer.data.store.SleepMode.MotionTracking || 
            sleepMode() == com.viel.aplayer.data.store.SleepMode.SleepTracking) {
            registerShakeListener(currentPlayback, currentMetadata)
        }

        if (minutes == -2) { // Chapter Boundary Mode
            sleepTimerJob = scope.launch {
                var isFading = false
                var originalVolume = 1.0f
                var registeredSensor = false
                try {
                    while (true) {
                        val state = currentPlayback()
                        val meta = currentMetadata()
                        // Player Chapter Projection Use (Consume player-scene chapter items directly)
                        // Chapter-boundary sleep timing now reads the same projection used by player controls.
                        val chapters = meta.chapters
                        if (state.isPlaying) {
                            val currentChapter = chapters.findLast { state.currentPosition >= it.startPositionMs }
                            val endPos = if (currentChapter != null) {
                                currentChapter.startPositionMs + currentChapter.durationMs
                            } else {
                                state.duration
                            }

                            if (endPos > 0) {
                                val remainingMs = endPos - state.currentPosition
                                // Check Chapter Exemption (Verify if current chapter is marked to skip pause)
                                val isSkipped = currentChapter != null && currentChapter.startPositionMs == skippedChapterStartMs

                                // Bypass Fade-out Check (Proceed if chapter is exempted from stopping)
                                if (!isSkipped && isSleepFadeOutEnabled() && remainingMs <= 10000L) {
                                    if (!isFading) {
                                        isFading = true
                                        originalVolume = playbackController()?.playerVolume ?: 1.0f
                                        if (isShakeToResetEnabled() || sleepMode() != com.viel.aplayer.data.store.SleepMode.Regular) {
                                            registerShakeListener(currentPlayback, currentMetadata)
                                            registeredSensor = true
                                        }
                                    }

                                    if (remainingMs <= 100L) {
                                        break
                                    }

                                    // High-Frequency Fade Loop (Update attenuation step at 100ms interval)
                                    delay(100.milliseconds)
                                    val updatedState = currentPlayback()
                                    if (updatedState.isPlaying) {
                                        // Volume Restoration Intercept (Revert volume to standard if user is active)
                                        if (sleepMode() == com.viel.aplayer.data.store.SleepMode.MotionTracking && isDeviceMoving) {
                                            playbackController()?.playerVolume = originalVolume
                                            continue
                                        }
                                        if (sleepMode() == com.viel.aplayer.data.store.SleepMode.SleepTracking && !hasUserFallenAsleep) {
                                            playbackController()?.playerVolume = originalVolume
                                            continue
                                        }

                                        val updatedRemaining = endPos - updatedState.currentPosition
                                        val ratio = (updatedRemaining.coerceAtLeast(0L)).toFloat() / 10000f
                                        // Logarithmic Volume Scale: Volume = OriginalVolume * ln(1 + 9 * ratio) / ln(10)
                                        val factor = (ln(1.0 + 9.0 * ratio.toDouble()) / ln(10.0)).toFloat()
                                        playbackController()?.playerVolume = originalVolume * factor
                                    } else {
                                        // Interrupt Cleanup Execution (Reset attenuation parameters on external pause)
                                        playbackController()?.playerVolume = originalVolume
                                        isFading = false
                                        if (registeredSensor) {
                                            unregisterShakeListener()
                                            registeredSensor = false
                                        }
                                    }
                                    continue
                                }
                            }

                            if (currentChapter != null) {
                                val endPos = currentChapter.startPositionMs + currentChapter.durationMs
                                val isSkipped = currentChapter.startPositionMs == skippedChapterStartMs
                                // Exemption Play-Through (Allow playback through boundary if chapter is skipped)
                                if (!isSkipped && state.currentPosition >= endPos - 1000) break
                            } else {
                                if (state.duration > 0 && state.currentPosition >= state.duration - 1000) break
                            }
                        }
                        delay(1000.milliseconds)
                    }
                    playbackController()?.pause()
                } finally {
                    // Try-Finally Safety Wrap (Ensure volume and sensor recovery on job termination)
                    if (isFading) {
                        playbackController()?.playerVolume = originalVolume
                    }
                    unregisterShakeListener()
                    hasUserFallenAsleep = false
                    isDeviceMoving = false
                    staticSampleCount = 0
                    timeInStaticMs = 0L
                    resetSleepTimer()
                }
            }
            return
        }

        // Regular countdown mode: minutes < 0 represents test mode (5 seconds); positive integer otherwise.
        val millis = if (minutes < 0) 5000L else minutes * 60 * 1000L
        _sleepTimerMillis.value = millis

        sleepTimerJob = scope.launch {
            var isFading = false
            var originalVolume = 1.0f
            var registeredSensor = false
            try {
                while (_sleepTimerMillis.value > 0) {
                    if (isSleepFadeOutEnabled() && _sleepTimerMillis.value <= 10000L) {
                        if (!isFading) {
                            isFading = true
                            originalVolume = playbackController()?.playerVolume ?: 1.0f
                            if (isShakeToResetEnabled() || sleepMode() != com.viel.aplayer.data.store.SleepMode.Regular) {
                                registerShakeListener(currentPlayback, currentMetadata)
                                registeredSensor = true
                            }
                        }

                        // High-Frequency Fine-Tuning (Perform 100ms step adjustments for the final 10 seconds)
                        val steps = 10
                        for (i in 0 until steps) {
                            if (_sleepTimerMillis.value <= 0) break
                            delay(100.milliseconds)
                            if (currentPlayback().isPlaying) {
                                // Countdown Suspend Execution (Hold duration and volume levels if active motion is detected)
                                if (sleepMode() == com.viel.aplayer.data.store.SleepMode.MotionTracking && isDeviceMoving) {
                                    playbackController()?.playerVolume = originalVolume
                                    continue
                                }
                                if (sleepMode() == com.viel.aplayer.data.store.SleepMode.SleepTracking && !hasUserFallenAsleep) {
                                    playbackController()?.playerVolume = originalVolume
                                    continue
                                }

                                _sleepTimerMillis.value = (_sleepTimerMillis.value - 100).coerceAtLeast(0L)
                                val ratio = _sleepTimerMillis.value.toFloat() / 10000f
                                // Logarithmic Volume Scale: Volume = OriginalVolume * ln(1 + 9 * ratio) / ln(10)
                                val factor = (ln(1.0 + 9.0 * ratio.toDouble()) / ln(10.0)).toFloat()
                                playbackController()?.playerVolume = originalVolume * factor
                            } else {
                                // Interrupt Cleanup Execution (Reset attenuation parameters on external pause)
                                playbackController()?.playerVolume = originalVolume
                                isFading = false
                                if (registeredSensor) {
                                    unregisterShakeListener()
                                    registeredSensor = false
                                }
                                break
                            }
                        }
                    } else {
                        // Standard Countdown Loop (Perform 1-second interval checks)
                        delay(1000.milliseconds)
                        if (currentPlayback().isPlaying) {
                            // Mode Dispatcher Branching (Evaluate current sleep mode algorithm)
                            when (sleepMode()) {
                                com.viel.aplayer.data.store.SleepMode.Regular -> {
                                    // Regular Mode: Deduct 1 second unconditionally per interval while playing.
                                    _sleepTimerMillis.value = (_sleepTimerMillis.value - 1000).coerceAtLeast(0L)
                                }
                                com.viel.aplayer.data.store.SleepMode.MotionTracking -> {
                                    // Motion Tracking Mode: Ticker counts down only when device is static, suspending on motion.
                                    if (!isDeviceMoving) {
                                        _sleepTimerMillis.value = (_sleepTimerMillis.value - 1000).coerceAtLeast(0L)
                                    }
                                }
                                com.viel.aplayer.data.store.SleepMode.SleepTracking -> {
                                    // Sleep Tracking Mode: Ticker initiates only after user is determined to be asleep.
                                    if (!hasUserFallenAsleep) {
                                        if (!isDeviceMoving) {
                                            timeInStaticMs += 1000L
                                            // Static Duration Threshold: Accumulate 10 minutes of static frames to confirm sleep state.
                                            if (timeInStaticMs >= 600000L) {
                                                hasUserFallenAsleep = true
                                                showToast(FeedbackMessages.sleepTrackingCountdownStarted())
                                            }
                                        } else {
                                            timeInStaticMs = 0L
                                        }
                                    } else {
                                        // Confirmed Sleep countdown (Process ticker decrement after sleep confirmation)
                                        _sleepTimerMillis.value = (_sleepTimerMillis.value - 1000).coerceAtLeast(0L)
                                    }
                                }
                            }
                        }
                    }
                }
                playbackController()?.pause()
            } finally {
                // Try-Finally Safety Wrap (Ensure volume and sensor cleanup on timer termination)
                if (isFading) {
                    playbackController()?.playerVolume = originalVolume
                }
                unregisterShakeListener()
                hasUserFallenAsleep = false
                isDeviceMoving = false
                staticSampleCount = 0
                timeInStaticMs = 0L
                resetSleepTimer()
            }
        }
    }

    private fun resetSleepTimer() {
        _sleepTimerMillis.value = 0
        onTimerReset()
    }
}
