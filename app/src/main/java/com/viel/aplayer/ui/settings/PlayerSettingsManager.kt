package com.viel.aplayer.ui.settings

import android.content.Context
import android.media.AudioManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import com.viel.aplayer.media.PlaybackManager
import com.viel.aplayer.ui.player.BookMetadataState
import com.viel.aplayer.ui.player.PlaybackState

/**
 * 播放器设置与 UI 交互管理器。
 * 负责睡眠定时器、音量逻辑、界面显隐控制及 Tab 切换。
 */
class PlayerSettingsManager(
    private val scope: CoroutineScope,
    private val playbackManager: () -> PlaybackManager?,
    private val audioManager: () -> AudioManager?,
    // 为每一次改动添加详尽的中文注释：新增动态 Context 获取提供者 lambda，通过动态桥接避免 ViewModel 持有 Activity/Fragment 等 Context 造成的内存泄漏隐患。
    private val contextProvider: () -> Context?
) {
    private val _settingsState = MutableStateFlow(PlayerSettingsState())
    val settingsState: StateFlow<PlayerSettingsState> = _settingsState.asStateFlow()

    private val _sleepTimerMillis = MutableStateFlow(0L)
    val sleepTimerMillis: StateFlow<Long> = _sleepTimerMillis.asStateFlow()

    private var sleepTimerJob: Job? = null
    private var volumeAccumulator = 0f

    // 为每一次改动添加详尽的中文注释：睡眠定时器音量渐隐机制的全局开关，由外部 ViewModel 实时同步持久化配置。
    var isSleepFadeOutEnabled: Boolean = true

    // 为每一次改动添加详尽的中文注释：摇晃手机重置睡眠定时器的全局控制开关，由外部 ViewModel 实时同步持久化配置。
    var isShakeToResetEnabled: Boolean = true

    private var sensorManager: android.hardware.SensorManager? = null
    private var shakeListener: android.hardware.SensorEventListener? = null
    private var lastShakeTime = 0L

    // 为每一次改动添加详尽的中文注释：记忆被摇晃手机所顺延屏蔽的章节的起始位置。如果章节开始时间与此一致，则在该章结尾不执行渐隐和暂停。
    private var skippedChapterStartMs: Long? = null

    // 为每一次改动添加详尽的中文注释：注册加速度计传感器监听。仅在最后 10 秒音量渐隐期内按需动态注册，绝对不浪费后台常态功耗。
    private fun registerShakeListener(
        currentPlayback: () -> PlaybackState,
        currentMetadata: () -> BookMetadataState
    ) {
        val ctx = contextProvider() ?: return
        if (!isShakeToResetEnabled) return
        if (sensorManager == null) {
            sensorManager = ctx.getSystemService(Context.SENSOR_SERVICE) as? android.hardware.SensorManager
        }
        if (shakeListener == null) {
            shakeListener = object : android.hardware.SensorEventListener {
                override fun onSensorChanged(event: android.hardware.SensorEvent?) {
                    if (event == null || event.sensor.type != android.hardware.Sensor.TYPE_ACCELEROMETER) return
                    val x = event.values[0]
                    val y = event.values[1]
                    val z = event.values[2]

                    // 计算重力加速度合成 G 力
                    val gX = x / android.hardware.SensorManager.GRAVITY_EARTH
                    val gY = y / android.hardware.SensorManager.GRAVITY_EARTH
                    val gZ = z / android.hardware.SensorManager.GRAVITY_EARTH
                    val gForce = kotlin.math.sqrt(gX * gX + gY * gY + gZ * gZ)

                    // 设定 1.8g 轻轻摇晃即可响应，并以 2000ms 物理防抖避免二次误触发
                    if (gForce > 1.8f) {
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

    // 为每一次改动添加详尽的中文注释：彻底注销传感器监听，并在 finally 块中黄金保障，防止泄露。
    private fun unregisterShakeListener() {
        shakeListener?.let {
            sensorManager?.unregisterListener(it)
        }
        shakeListener = null
    }

    // 为每一次改动添加详尽的中文注释：提供硬件马达轻微震动 100ms 反馈，为听众提供高可用夜间盲操确认。
    private fun triggerVibration() {
        val ctx = contextProvider() ?: return
        try {
            val vibrator = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                val vibratorManager = ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? android.os.VibratorManager
                vibratorManager?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                ctx.getSystemService(Context.VIBRATOR_SERVICE) as? android.os.Vibrator
            }

            if (vibrator?.hasVibrator() == true) {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    vibrator.vibrate(android.os.VibrationEffect.createOneShot(100, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(100)
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("PlayerSettingsManager", "震动反馈失败", e)
        }
    }

    // 为每一次改动添加详尽的中文注释：在 UI 线程弹出轻量化 Toast 温馨告知听众。
    private fun showToast(message: String) {
        val ctx = contextProvider() ?: return
        scope.launch(kotlinx.coroutines.Dispatchers.Main) {
            android.widget.Toast.makeText(ctx, message, android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    // 为每一次改动添加详尽的中文注释：摇晃重置的核心业务分支决策算法，支持“有下一章顺延到下一章结束，无下一章不顺延”。
    private fun performShakeReset(
        currentPlayback: () -> PlaybackState,
        currentMetadata: () -> BookMetadataState
    ) {
        val currentSelected = settingsState.value.selectedSleepTimer
        if (currentSelected == -2) {
            // 章节结束停止模式
            val meta = currentMetadata()
            val state = currentPlayback()
            // 查找当前所处章节
            val currentChapter = meta.chapters.findLast { state.currentPosition >= it.startPositionMs }
            val currentChapterIndex = if (currentChapter != null) meta.chapters.indexOf(currentChapter) else -1
            // 判断是否拥有下一章节以满足“无下一章节不顺延”的原则
            val hasNextChapter = currentChapterIndex != -1 && currentChapterIndex < meta.chapters.size - 1

            if (hasNextChapter && currentChapter != null) {
                // 有下一章：触发震动反馈，记录当前章的起始位置至 skippedChapterStartMs，并重启章节结束 Job 且标记 isShakeReset = true
                triggerVibration()
                skippedChapterStartMs = currentChapter.startPositionMs
                showToast("已摇晃顺延至下一章结束")
                setSleepTimer(-2, currentPlayback, currentMetadata, isShakeReset = true)
            } else {
                // 无下一章（最后一章或单轨）：保持不顺延，在当前章节末尾正常平滑暂停，提供轻微震动并温馨提示已是最后一章
                triggerVibration()
                showToast("已是最后一章，无法顺延")
            }
        } else if (currentSelected > 0) {
            // 常规分钟倒计时模式：重置回最初的设定时长重新开始
            triggerVibration()
            showToast("已摇晃重置睡眠倒计时")
            setSleepTimer(currentSelected, currentPlayback, currentMetadata, isShakeReset = true)
        } else if (currentSelected == -1) {
            // 调试测试模式（5s）：重新倒计时
            triggerVibration()
            showToast("已摇晃重置测试倒计时")
            setSleepTimer(currentSelected, currentPlayback, currentMetadata, isShakeReset = true)
        }
    }

    /**
     * 为每一次改动添加详尽的中文注释：设置睡眠定时器，支持对数音量平滑渐隐 (Fade Out) 及按需动态摇晃重置监听 (Shake to Reset)。
     * 在倒计时归零或章节结束前 10 秒内，若开启渐隐，将启动 100ms 高阶微秒定时协程平滑降低播放器内部音量，
     * 同时动态按需注册加速度传感器摇晃监听，提供免亮屏晃动恢复/顺延的体贴盲操；
     * 并在暂停后利用 try-finally 架构百分之百安全自愈复原初始音量与注销传感器，避免破坏下次播音体验及泄漏电量。
     */
    fun setSleepTimer(
        minutes: Int,
        currentPlayback: () -> PlaybackState,
        currentMetadata: () -> BookMetadataState,
        isShakeReset: Boolean = false
    ) {
        sleepTimerJob?.cancel()
        _settingsState.update { it.copy(selectedSleepTimer = minutes) }

        // 为每一次改动添加详尽的中文注释：如果不是通过摇晃重置触发的设置（即用户手动触发设置或关闭），必须将跳章顺延记忆重置为 null 从而避免越权跳过。
        if (!isShakeReset) {
            skippedChapterStartMs = null
        }

        if (minutes == 0) {
            _sleepTimerMillis.value = 0L
            return
        }

        if (minutes == -2) { // 章节结束停止模式
            sleepTimerJob = scope.launch {
                var isFading = false
                var originalVolume = 1.0f
                var registeredSensor = false
                try {
                    while (true) {
                        val state = currentPlayback()
                        val meta = currentMetadata()
                        if (state.isPlaying) {
                            val currentChapter = meta.chapters.findLast { state.currentPosition >= it.startPositionMs }
                            val endPos = if (currentChapter != null) {
                                currentChapter.startPositionMs + currentChapter.durationMs
                            } else {
                                state.duration
                            }

                            if (endPos > 0) {
                                val remainingMs = endPos - state.currentPosition
                                // 为每一次改动添加详尽的中文注释：判断当前章是否是被摇晃手机所顺延屏蔽的章节。
                                val isSkipped = currentChapter != null && currentChapter.startPositionMs == skippedChapterStartMs

                                // 若当前章处于顺延屏蔽中，直接跳过其尾部的渐隐流程。
                                if (!isSkipped && isSleepFadeOutEnabled && remainingMs <= 10000L) {
                                    if (!isFading) {
                                        isFading = true
                                        originalVolume = playbackManager()?.playerVolume ?: 1.0f
                                        if (isShakeToResetEnabled) {
                                            registerShakeListener(currentPlayback, currentMetadata)
                                            registeredSensor = true
                                        }
                                    }

                                    if (remainingMs <= 100L) {
                                        break
                                    }

                                    // 100ms 高频更新内部音量
                                    delay(100)
                                    val updatedState = currentPlayback()
                                    if (updatedState.isPlaying) {
                                        val updatedRemaining = endPos - updatedState.currentPosition
                                        val ratio = (updatedRemaining.coerceAtLeast(0L)).toFloat() / 10000f
                                        // 对数衰减曲线：Volume = OriginalVolume * ln(1 + 9 * ratio) / ln(10)
                                        val factor = (Math.log(1.0 + 9.0 * ratio.toDouble()) / Math.log(10.0)).toFloat()
                                        playbackManager()?.playerVolume = originalVolume * factor
                                    } else {
                                        // 中途被动暂停，恢复基准音量并退出本次渐隐且安全注销传感器
                                        playbackManager()?.playerVolume = originalVolume
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
                                // 若当前章处于顺延屏蔽中，不执行 1 秒前的 break 暂停，任其播入下一章。
                                if (!isSkipped && state.currentPosition >= endPos - 1000) break
                            } else {
                                if (state.duration > 0 && state.currentPosition >= state.duration - 1000) break
                            }
                        }
                        delay(1000)
                    }
                    playbackManager()?.pause()
                } finally {
                    // try-finally 黄金保护：如果中途发生 cancel() 重置、切歌或异常，百分百安全复原原有音量并安全释放传感器，防静音与资源泄漏
                    if (isFading) {
                        playbackManager()?.playerVolume = originalVolume
                    }
                    if (registeredSensor) {
                        unregisterShakeListener()
                    }
                    resetSleepTimer()
                }
            }
            return
        }

        // 常规倒计时模式：minutes < 0 代表测试模式(5秒)，用于开发调试；其余为正整数分钟。
        val millis = if (minutes < 0) 5000L else minutes * 60 * 1000L
        _sleepTimerMillis.value = millis

        sleepTimerJob = scope.launch {
            var isFading = false
            var originalVolume = 1.0f
            var registeredSensor = false
            try {
                while (_sleepTimerMillis.value > 0) {
                    if (isSleepFadeOutEnabled && _sleepTimerMillis.value <= 10000L) {
                        if (!isFading) {
                            isFading = true
                            originalVolume = playbackManager()?.playerVolume ?: 1.0f
                            if (isShakeToResetEnabled) {
                                registerShakeListener(currentPlayback, currentMetadata)
                                registeredSensor = true
                            }
                        }

                        // 在最后 10 秒内采用 100ms 级别的高频精细调节
                        val steps = 10
                        for (i in 0 until steps) {
                            if (_sleepTimerMillis.value <= 0) break
                            delay(100)
                            if (currentPlayback().isPlaying) {
                                _sleepTimerMillis.value = (_sleepTimerMillis.value - 100).coerceAtLeast(0L)
                                val ratio = _sleepTimerMillis.value.toFloat() / 10000f
                                // 对数衰减曲线：Volume = OriginalVolume * ln(1 + 9 * ratio) / ln(10)
                                val factor = (Math.log(1.0 + 9.0 * ratio.toDouble()) / Math.log(10.0)).toFloat()
                                playbackManager()?.playerVolume = originalVolume * factor
                            } else {
                                // 播音中途用户暂停，原音量复原并重置渐隐状态且安全注销传感器
                                playbackManager()?.playerVolume = originalVolume
                                isFading = false
                                if (registeredSensor) {
                                    unregisterShakeListener()
                                    registeredSensor = false
                                }
                                break
                            }
                        }
                    } else {
                        // 常规扣减阶段，每秒循环监测一次
                        delay(1000)
                        if (currentPlayback().isPlaying) {
                            _sleepTimerMillis.value = (_sleepTimerMillis.value - 1000).coerceAtLeast(0L)
                        }
                    }
                }
                playbackManager()?.pause()
            } finally {
                // try-finally 黄金保护：定时器结束、中途取消、重置倒计时等场景下无条件确保原音量安全复原，传感器释放防泄露
                if (isFading) {
                    playbackManager()?.playerVolume = originalVolume
                }
                if (registeredSensor) {
                    unregisterShakeListener()
                }
                resetSleepTimer()
            }
        }
    }

    private fun resetSleepTimer() {
        _settingsState.update { it.copy(selectedSleepTimer = 0) }
        _sleepTimerMillis.value = 0
    }

    /**
     * 滑动调节系统音量。
     */
    fun adjustVolume(delta: Float) {
        val am = audioManager() ?: return
        volumeAccumulator += delta
        val threshold = 0.05f
        if (kotlin.math.abs(volumeAccumulator) >= threshold) {
            val direction = if (volumeAccumulator > 0) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER
            am.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, AudioManager.FLAG_SHOW_UI)
            volumeAccumulator = 0f
        }
    }

    // --- UI 显隐与 Tab 控制 ---

    fun showChapterList() = _settingsState.update { it.copy(isChapterListVisible = true) }
    fun dismissChapterList() = _settingsState.update { it.copy(isChapterListVisible = false) }
    fun showBookmarkDialog() = _settingsState.update { it.copy(isBookmarkDialogVisible = true, bookmarkTitle = "") }
    fun dismissBookmarkDialog() = _settingsState.update { it.copy(isBookmarkDialogVisible = false, bookmarkTitle = "") }
    fun updateBookmarkTitle(title: String) = _settingsState.update { it.copy(bookmarkTitle = title) }
    fun setSelectedContentTab(tab: Int) = _settingsState.update { it.copy(selectedContentTab = tab) }
    fun setFullPlayerVisible(visible: Boolean) = _settingsState.update { it.copy(isFullPlayerVisible = visible) }
    fun setMiniPlayerHidden(hidden: Boolean) = _settingsState.update { it.copy(isMiniPlayerHidden = hidden) }
    fun toggleProgressMode() = _settingsState.update { it.copy(isChapterProgressMode = !it.isChapterProgressMode) }
    fun setChapterProgressMode(enabled: Boolean) = _settingsState.update { it.copy(isChapterProgressMode = enabled) }
    fun setUndoSeekVisible(visible: Boolean) = _settingsState.update { it.copy(showUndoSeek = visible) }
}