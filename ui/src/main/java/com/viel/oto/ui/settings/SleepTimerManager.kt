package com.viel.oto.ui.settings

import android.content.Context
import com.viel.oto.application.playback.PlayerPlaybackController
import com.viel.oto.event.feedback.FeedbackFact
import com.viel.oto.event.feedback.PlaybackControlFeedbackFacts
import com.viel.oto.logger.SecureLog
import com.viel.oto.ui.player.BookMetadataState
import com.viel.oto.ui.player.PlaybackState
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
 * Orchestrates sleep countdown, fading, and movement tracking.
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
    private val isSleepFadeOutEnabled: () -> Boolean,
    private val isShakeToResetEnabled: () -> Boolean,
    private val sleepMode: () -> com.viel.oto.shared.model.SleepMode,
    private val onFeedback: (FeedbackFact) -> Unit,
    private val selectedSleepTimer: () -> Int,
    private val onTimerReset: () -> Unit,
    private val onTimerSelectedMinutesChanged: (Int) -> Unit
) {
    private val _sleepTimerMillis = MutableStateFlow(0L)
    val sleepTimerMillis: StateFlow<Long> = _sleepTimerMillis.asStateFlow()

    private var sleepTimerJob: Job? = null

    private var sensorManager: android.hardware.SensorManager? = null
    private var shakeListener: android.hardware.SensorEventListener? = null
    private var lastShakeTime = 0L

    @Volatile
    private var isDeviceMoving = false
    private var staticSampleCount = 0
    private var lastMovementTime = 0L
    private var lastActiveTriggerTime = 0L

    @Volatile
    private var hasUserFallenAsleep = false
    private var timeInStaticMs = 0L
    private var lastIsPlaying: Boolean? = null

    private var skippedChapterStartMs: Long? = null

    private fun registerShakeListener(
        currentPlayback: () -> PlaybackState,
        currentMetadata: () -> BookMetadataState
    ) {
        val ctx = contextProvider() ?: return
        if (!isShakeToResetEnabled() && sleepMode() == com.viel.oto.shared.model.SleepMode.Regular) return
        if (sensorManager == null) {
            sensorManager = ctx.getSystemService(Context.SENSOR_SERVICE) as? android.hardware.SensorManager
        }
        if (shakeListener == null) {
            shakeListener = object : android.hardware.SensorEventListener {
                override fun onSensorChanged(event: android.hardware.SensorEvent?) {
                    if (event == null || event.sensor.type != android.hardware.Sensor.TYPE_ACCELEROMETER) return

                    val playState = currentPlayback()
                    val isPlaying = playState.isPlaying
                    if (lastIsPlaying != null && lastIsPlaying != isPlaying) {
                        lastActiveTriggerTime = 0L
                    }
                    lastIsPlaying = isPlaying

                    if (System.currentTimeMillis() - lastActiveTriggerTime < 60000L) {
                        return
                    }

                    val gX = event.values[0] / android.hardware.SensorManager.GRAVITY_EARTH
                    val gY = event.values[1] / android.hardware.SensorManager.GRAVITY_EARTH
                    val gZ = event.values[2] / android.hardware.SensorManager.GRAVITY_EARTH
                    val gForce = kotlin.math.sqrt(gX * gX + gY * gY + gZ * gZ)

                    val gDeviation = kotlin.math.abs(gForce - 1.0f)

                    val movementThreshold = if (sleepMode() == com.viel.oto.shared.model.SleepMode.SleepTracking) 0.08f else 0.035f

                    if (gDeviation > movementThreshold) {
                        if (!isDeviceMoving) {
                            if (sleepMode() == com.viel.oto.shared.model.SleepMode.MotionTracking) {
                                emitFeedback(PlaybackControlFeedbackFacts.sleepMotionTrackingPaused())
                            }
                        }
                        isDeviceMoving = true
                        lastMovementTime = System.currentTimeMillis()
                        lastActiveTriggerTime = System.currentTimeMillis()
                        staticSampleCount = 0
                        timeInStaticMs = 0L
                        if (sleepMode() == com.viel.oto.shared.model.SleepMode.SleepTracking && hasUserFallenAsleep) {
                            hasUserFallenAsleep = false
                            emitFeedback(PlaybackControlFeedbackFacts.sleepTrackingPausedByActivity())
                        }
                    } else {
                        if (System.currentTimeMillis() - lastMovementTime >= 3000L) {
                            staticSampleCount++
                            if (staticSampleCount >= 8) {
                                if (isDeviceMoving) {
                                    if (sleepMode() == com.viel.oto.shared.model.SleepMode.MotionTracking) {
                                        emitFeedback(PlaybackControlFeedbackFacts.sleepMotionTrackingResumed())
                                    }
                                }
                                isDeviceMoving = false
                            }
                        }
                    }

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

    private fun unregisterShakeListener() {
        shakeListener?.let {
            sensorManager?.unregisterListener(it)
        }
        shakeListener = null
    }

    private fun triggerVibration() {
        val ctx = contextProvider() ?: return
        try {
            val vibratorManager = ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? android.os.VibratorManager
            val vibrator = vibratorManager?.defaultVibrator

            if (vibrator?.hasVibrator() == true) {
                vibrator.vibrate(android.os.VibrationEffect.createOneShot(100, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
            }
        } catch (e: Exception) {
            SecureLog.error("SleepTimerManager", "Vibration feedback failed", e)
        }
    }

    private fun emitFeedback(fact: FeedbackFact) {
        scope.launch(kotlinx.coroutines.Dispatchers.Main) {
            onFeedback(fact)
        }
    }

    private fun performShakeReset(
        currentPlayback: () -> PlaybackState,
        currentMetadata: () -> BookMetadataState
    ) {
        if (!isShakeToResetEnabled()) return
        val currentSelected = selectedSleepTimer()
        if (currentSelected == -2) {
            val meta = currentMetadata()
            val state = currentPlayback()
            val chapters = meta.chapters
            val currentChapter = chapters.findLast { state.currentPosition >= it.startPositionMs }
            val currentChapterIndex = if (currentChapter != null) chapters.indexOf(currentChapter) else -1
            val hasNextChapter = currentChapterIndex != -1 && currentChapterIndex < chapters.size - 1

            if (hasNextChapter && currentChapter != null) {
                triggerVibration()
                skippedChapterStartMs = currentChapter.startPositionMs
                emitFeedback(PlaybackControlFeedbackFacts.sleepShakeExtendedToNextChapter())
                setSleepTimer(-2, currentPlayback, currentMetadata, isShakeReset = true)
            } else {
                triggerVibration()
                emitFeedback(PlaybackControlFeedbackFacts.sleepShakeNoNextChapter())
            }
        } else if (currentSelected > 0) {
            triggerVibration()
            emitFeedback(PlaybackControlFeedbackFacts.sleepShakeCountdownReset())
            setSleepTimer(currentSelected, currentPlayback, currentMetadata, isShakeReset = true)
        } else if (currentSelected == -1) {
            triggerVibration()
            emitFeedback(PlaybackControlFeedbackFacts.sleepShakeTestCountdownReset())
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
        onTimerSelectedMinutesChanged(minutes)

        if (!isShakeReset) {
            skippedChapterStartMs = null
        }

        if (minutes == 0) {
            _sleepTimerMillis.value = 0L
            unregisterShakeListener()
            return
        }

        if (sleepMode() == com.viel.oto.shared.model.SleepMode.MotionTracking ||
            sleepMode() == com.viel.oto.shared.model.SleepMode.SleepTracking) {
            registerShakeListener(currentPlayback, currentMetadata)
        }

        if (minutes == -2) {
            sleepTimerJob = scope.launch {
                var isFading = false
                var originalVolume = 1.0f
                var registeredSensor = false
                try {
                    while (true) {
                        val state = currentPlayback()
                        val meta = currentMetadata()
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
                                val isSkipped = currentChapter != null && currentChapter.startPositionMs == skippedChapterStartMs

                                if (!isSkipped && isSleepFadeOutEnabled() && remainingMs <= 10000L) {
                                    if (!isFading) {
                                        isFading = true
                                        originalVolume = playbackController()?.playerVolume ?: 1.0f
                                        if (isShakeToResetEnabled() || sleepMode() != com.viel.oto.shared.model.SleepMode.Regular) {
                                            registerShakeListener(currentPlayback, currentMetadata)
                                            registeredSensor = true
                                        }
                                    }

                                    if (remainingMs <= 100L) {
                                        break
                                    }

                                    delay(100.milliseconds)
                                    val updatedState = currentPlayback()
                                    if (updatedState.isPlaying) {
                                        if (sleepMode() == com.viel.oto.shared.model.SleepMode.MotionTracking && isDeviceMoving) {
                                            playbackController()?.playerVolume = originalVolume
                                            continue
                                        }
                                        if (sleepMode() == com.viel.oto.shared.model.SleepMode.SleepTracking && !hasUserFallenAsleep) {
                                            playbackController()?.playerVolume = originalVolume
                                            continue
                                        }

                                        val updatedRemaining = endPos - updatedState.currentPosition
                                        val ratio = (updatedRemaining.coerceAtLeast(0L)).toFloat() / 10000f
                                        val factor = (ln(1.0 + 9.0 * ratio.toDouble()) / ln(10.0)).toFloat()
                                        playbackController()?.playerVolume = originalVolume * factor
                                    } else {
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
                                if (!isSkipped && state.currentPosition >= endPos - 1000) break
                            } else {
                                if (state.duration > 0 && state.currentPosition >= state.duration - 1000) break
                            }
                        }
                        delay(1000.milliseconds)
                    }
                    playbackController()?.pause()
                } finally {
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
                            if (isShakeToResetEnabled() || sleepMode() != com.viel.oto.shared.model.SleepMode.Regular) {
                                registerShakeListener(currentPlayback, currentMetadata)
                                registeredSensor = true
                            }
                        }

                        val steps = 10
                        for (i in 0 until steps) {
                            if (_sleepTimerMillis.value <= 0) break
                            delay(100.milliseconds)
                            if (currentPlayback().isPlaying) {
                                if (sleepMode() == com.viel.oto.shared.model.SleepMode.MotionTracking && isDeviceMoving) {
                                    playbackController()?.playerVolume = originalVolume
                                    continue
                                }
                                if (sleepMode() == com.viel.oto.shared.model.SleepMode.SleepTracking && !hasUserFallenAsleep) {
                                    playbackController()?.playerVolume = originalVolume
                                    continue
                                }

                                _sleepTimerMillis.value = (_sleepTimerMillis.value - 100).coerceAtLeast(0L)
                                val ratio = _sleepTimerMillis.value.toFloat() / 10000f
                                val factor = (ln(1.0 + 9.0 * ratio.toDouble()) / ln(10.0)).toFloat()
                                playbackController()?.playerVolume = originalVolume * factor
                            } else {
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
                        delay(1000.milliseconds)
                        if (currentPlayback().isPlaying) {
                            when (sleepMode()) {
                                com.viel.oto.shared.model.SleepMode.Regular -> {
                                    _sleepTimerMillis.value = (_sleepTimerMillis.value - 1000).coerceAtLeast(0L)
                                }
                                com.viel.oto.shared.model.SleepMode.MotionTracking -> {
                                    if (!isDeviceMoving) {
                                        _sleepTimerMillis.value = (_sleepTimerMillis.value - 1000).coerceAtLeast(0L)
                                    }
                                }
                                com.viel.oto.shared.model.SleepMode.SleepTracking -> {
                                    if (!hasUserFallenAsleep) {
                                        if (!isDeviceMoving) {
                                            timeInStaticMs += 1000L
                                            if (timeInStaticMs >= 600000L) {
                                                hasUserFallenAsleep = true
                                                emitFeedback(PlaybackControlFeedbackFacts.sleepTrackingCountdownStarted())
                                            }
                                        } else {
                                            timeInStaticMs = 0L
                                        }
                                    } else {
                                        _sleepTimerMillis.value = (_sleepTimerMillis.value - 1000).coerceAtLeast(0L)
                                    }
                                }
                            }
                        }
                    }
                }
                playbackController()?.pause()
            } finally {
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
