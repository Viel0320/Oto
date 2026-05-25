package com.viel.aplayer.ui.settings

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import com.viel.aplayer.media.PlaybackManager
import com.viel.aplayer.ui.player.BookMetadataState
import com.viel.aplayer.ui.player.PlaybackState
import kotlin.math.ln

/**
 * 睡眠定时器执行管理器。
 * 从 PlayerSettingsManager 中抽离出 SleepTimer 直接相关的计时扣减、渐隐对数衰减算法、
 * 物理加速度传感器事件注册与去噪防抖、摇晃顺延重置以及马达震动反馈等逻辑。
 * 保持 PlayerSettingsManager 对设定等外层逻辑及外部接口不变，本类作为底层引擎独立执行。
 */
class SleepTimerManager(
    private val scope: CoroutineScope,
    private val playbackManager: () -> PlaybackManager?,
    private val contextProvider: () -> Context?,
    // 通过 lambda 动态获取最新配置
    private val isSleepFadeOutEnabled: () -> Boolean,
    private val isShakeToResetEnabled: () -> Boolean,
    private val sleepMode: () -> com.viel.aplayer.data.store.SleepMode,
    private val selectedSleepTimer: () -> Int,
    // 当定时器结束或被重置时的回调，用于更新外层 PlayerSettingsState 的 selectedSleepTimer 属性
    private val onTimerReset: () -> Unit,
    private val onTimerSelectedMinutesChanged: (Int) -> Unit
) {
    // 睡眠剩余时间（毫秒）的流，直接服务于界面展示
    private val _sleepTimerMillis = MutableStateFlow(0L)
    val sleepTimerMillis: StateFlow<Long> = _sleepTimerMillis.asStateFlow()

    // 控制倒计时与渐隐的核心协程 Job
    private var sleepTimerJob: Job? = null

    private var sensorManager: android.hardware.SensorManager? = null
    private var shakeListener: android.hardware.SensorEventListener? = null
    private var lastShakeTime = 0L

    // 动作检测相关变量，用于运动跟踪模式判断设备是否正在被移动。
    @Volatile
    private var isDeviceMoving = false
    private var staticSampleCount = 0
    // 上一次检测到有效物理运动的时间戳，用于实施动作状态的冷却/保持防抖，防止高频传感器置回。
    private var lastMovementTime = 0L
    // 动作被有效激活触发（即进入运动状态）时的系统时间戳，用于锁定 1 分钟不计时与不采样防抖。
    private var lastActiveTriggerTime = 0L

    // 睡眠状态检测相关变量，检测是否已模拟判定为进入熟睡。
    @Volatile
    private var hasUserFallenAsleep = false
    private var timeInStaticMs = 0L
    // 上一次记录的播放状态，用于捕捉用户是否手动切换了播放/暂停状态以实时重置一分钟锁定。
    private var lastIsPlaying: Boolean? = null

    // 记忆被摇晃手机所顺延屏蔽的章节的起始位置。如果章节开始时间与此一致，则在该章结尾不执行渐隐和暂停。
    private var skippedChapterStartMs: Long? = null

    // 注册加速度计传感器监听。
    // 在最后 10 秒音量渐隐期内，或者在开启“运动跟踪”与“睡眠跟踪”倒计时期间动态按需开启，既省电又能实时跟踪物理状态。
    private fun registerShakeListener(
        currentPlayback: () -> PlaybackState,
        currentMetadata: () -> BookMetadataState
    ) {
        val ctx = contextProvider() ?: return
        // 若没有启用摇晃重置，且当前不是运动/睡眠跟踪模式，则不需常态注册传感器以节省系统开销
        if (!isShakeToResetEnabled() && sleepMode() == com.viel.aplayer.data.store.SleepMode.Regular) return
        if (sensorManager == null) {
            sensorManager = ctx.getSystemService(Context.SENSOR_SERVICE) as? android.hardware.SensorManager
        }
        if (shakeListener == null) {
            shakeListener = object : android.hardware.SensorEventListener {
                override fun onSensorChanged(event: android.hardware.SensorEvent?) {
                    if (event == null || event.sensor.type != android.hardware.Sensor.TYPE_ACCELEROMETER) return
                    
                    // 捕捉当前播放状态以监听用户是否手动进行了播放/暂停切换
                    val playState = currentPlayback()
                    val isPlaying = playState.isPlaying
                    if (lastIsPlaying != null && lastIsPlaying != isPlaying) {
                        // 播放状态发生了切换！无条件解除一分钟防抖保护锁，恢复常态计时判定
                        lastActiveTriggerTime = 0L
                    }
                    lastIsPlaying = isPlaying

                    // 一分钟计时挂起保护锁拦截判定。若处于一分钟保护锁定内，则直接拦截并阻断后续的传感器采样与静止判定，达到绝对省电及不扣减计时的目的。
                    if (System.currentTimeMillis() - lastActiveTriggerTime < 60000L) {
                        return
                    }

                    // 计算重力加速度合成 G 力
                    val gX = event.values[0] / android.hardware.SensorManager.GRAVITY_EARTH
                    val gY = event.values[1] / android.hardware.SensorManager.GRAVITY_EARTH
                    val gZ = event.values[2] / android.hardware.SensorManager.GRAVITY_EARTH
                    val gForce = kotlin.math.sqrt(gX * gX + gY * gY + gZ * gZ)

                    // 计算当前 G 力偏离标准 1.0g 重力的绝对差值，以此作为衡量物理抖动的核心指标，彻底免疫倾斜和角度旋转变化。
                    val gDeviation = kotlin.math.abs(gForce - 1.0f)

                    // 在睡眠检测模式下，为防范翻身、微弱震动或呼吸导致的误判，特将判定运动的 G 力偏离阈值放宽至 0.08g，从而保留一定的容错空间；而常规运动跟踪仍采用 0.035g。
                    val movementThreshold = if (sleepMode() == com.viel.aplayer.data.store.SleepMode.SleepTracking) 0.08f else 0.035f

                    // 若 G力偏离度超过计算出的运动阈值，则判定设备正处于运动状态。
                    if (gDeviation > movementThreshold) {
                        // 进入运动瞬间发射 UI 弹窗通知以提醒听众倒计时已暂停
                        if (!isDeviceMoving) {
                            if (sleepMode() == com.viel.aplayer.data.store.SleepMode.MotionTracking) {
                                playbackManager()?.sendUiEvent(com.viel.aplayer.ui.common.UiEvent.ShowToast("动作跟踪：检测到运动，睡眠定时器已暂停计时，一分钟内保持暂停"))  //todo 正式上线要移除toast
                            }
                        }
                        isDeviceMoving = true
                        lastMovementTime = System.currentTimeMillis()
                        lastActiveTriggerTime = System.currentTimeMillis() // 记录运动生效并开始锁定的时间戳
                        staticSampleCount = 0
                        timeInStaticMs = 0L
                        // 如果处于睡眠跟踪模式且判定为已入睡，检测到运动则立刻提示重置入睡状态以实现智能流转
                        if (sleepMode() == com.viel.aplayer.data.store.SleepMode.SleepTracking && hasUserFallenAsleep) {
                            hasUserFallenAsleep = false
                            showToast("检测到身体活动，睡眠跟踪暂停，待您接近静止后继续")
                        }
                    } else {
                        // 只有当距离上一次检测到有效物理运动的时间超过了 3 秒（保护保持期），才允许静止计数递增并切回静止状态，实现科学防抖。
                        if (System.currentTimeMillis() - lastMovementTime >= 3000L) {
                            staticSampleCount++
                            if (staticSampleCount >= 8) { // 连续 8 帧（约 0.5-0.8 秒）都处于极平稳状态，才判定为静止
                                // 进入静止瞬间发射 UI 弹窗通知以提醒听众倒计时已恢复
                                if (isDeviceMoving) {
                                    if (sleepMode() == com.viel.aplayer.data.store.SleepMode.MotionTracking) {
                                        playbackManager()?.sendUiEvent(com.viel.aplayer.ui.common.UiEvent.ShowToast("动作跟踪：检测到静止，睡眠定时器恢复计时"))  //todo 正式上线要移除toast
                                    }
                                }
                                isDeviceMoving = false
                            }
                        }
                    }

                    // 设定 1.8g 轻轻摇晃即可响应，并以 2000ms 物理防抖避免二次误触发，且必须在“摇晃手机重置睡眠定时器”开关开启时才执行摇晃重置逻辑
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

    // 彻底注销传感器监听，防止资源泄漏。
    private fun unregisterShakeListener() {
        shakeListener?.let {
            sensorManager?.unregisterListener(it)
        }
        shakeListener = null
    }

    // 提供硬件马达轻微震动 100ms 反馈，为听众提供高可用夜间盲操确认。
    // 修复了原本 vibrator 变量未初始化的语法错误，直接通过 VibratorManager 获取 defaultVibrator 进行震动反馈。
    private fun triggerVibration() {
        val ctx = contextProvider() ?: return
        try {
            val vibratorManager = ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? android.os.VibratorManager
            val vibrator = vibratorManager?.defaultVibrator

            if (vibrator?.hasVibrator() == true) {
                vibrator.vibrate(android.os.VibrationEffect.createOneShot(100, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
            }
        } catch (e: Exception) {
            android.util.Log.e("SleepTimerManager", "震动反馈失败", e)
        }
    }

    // 在 UI 线程弹出轻量化 Toast 温馨告知听众。
    private fun showToast(message: String) {
        val ctx = contextProvider() ?: return
        scope.launch(kotlinx.coroutines.Dispatchers.Main) {
            android.widget.Toast.makeText(ctx, message, android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    // 摇晃重置的核心业务分支决策算法，支持“有下一章顺延到下一章结束，无下一章不顺延”。
    private fun performShakeReset(
        currentPlayback: () -> PlaybackState,
        currentMetadata: () -> BookMetadataState
    ) {
        if (!isShakeToResetEnabled()) return
        val currentSelected = selectedSleepTimer()
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
     * 设置睡眠定时器，支持三态睡眠模式 (常规、运动跟踪、睡眠跟踪) 核心判定及物理动作拦截。
     * 支持对数音量平滑渐隐 (Fade Out)，并在定时结束、重置或取消时，使用 try-finally 架构百分之百安全注销传感器并恢复播放器原有音量，防内存与功耗泄露。
     */
    fun setSleepTimer(
        minutes: Int,
        currentPlayback: () -> PlaybackState,
        currentMetadata: () -> BookMetadataState,
        isShakeReset: Boolean = false
    ) {
        sleepTimerJob?.cancel()
        // 通过回调通知外层管理器更新 selectedSleepTimer 属性
        onTimerSelectedMinutesChanged(minutes)

        // 如果不是通过摇晃重置触发的设置（即用户手动触发设置或关闭），必须将跳章顺延记忆重置为 null 从而避免越权跳过。
        if (!isShakeReset) {
            skippedChapterStartMs = null
        }

        if (minutes == 0) {
            _sleepTimerMillis.value = 0L
            unregisterShakeListener() // 定时器关闭时无条件注销传感器以省电
            return
        }

        // 如果开启了“运动跟踪”或“睡眠跟踪”模式，则在整个倒计时开启的瞬间就注册传感器监听
        if (sleepMode() == com.viel.aplayer.data.store.SleepMode.MotionTracking || 
            sleepMode() == com.viel.aplayer.data.store.SleepMode.SleepTracking) {
            registerShakeListener(currentPlayback, currentMetadata)
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
                                // 判断当前章是否是被摇晃手机所顺延屏蔽的章节。
                                val isSkipped = currentChapter != null && currentChapter.startPositionMs == skippedChapterStartMs

                                // 若当前章处于顺延屏蔽中，直接跳过其尾部的渐隐流程。
                                if (!isSkipped && isSleepFadeOutEnabled() && remainingMs <= 10000L) {
                                    if (!isFading) {
                                        isFading = true
                                        originalVolume = playbackManager()?.playerVolume ?: 1.0f
                                        if (isShakeToResetEnabled() || sleepMode() != com.viel.aplayer.data.store.SleepMode.Regular) {
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
                                        // 如果在章节倒数渐隐期内检测到设备处于运动跟踪且在移动、或睡眠未入睡状态，则立即复原满格音量并拦截章节结束逻辑
                                        if (sleepMode() == com.viel.aplayer.data.store.SleepMode.MotionTracking && isDeviceMoving) {
                                            playbackManager()?.playerVolume = originalVolume
                                            continue
                                        }
                                        if (sleepMode() == com.viel.aplayer.data.store.SleepMode.SleepTracking && !hasUserFallenAsleep) {
                                            playbackManager()?.playerVolume = originalVolume
                                            continue
                                        }

                                        val updatedRemaining = endPos - updatedState.currentPosition
                                        val ratio = (updatedRemaining.coerceAtLeast(0L)).toFloat() / 10000f
                                        // 对数衰减曲线：Volume = OriginalVolume * ln(1 + 9 * ratio) / ln(10)
                                        val factor = (ln(1.0 + 9.0 * ratio.toDouble()) / ln(10.0)).toFloat()
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

        // 常规倒计时模式：minutes < 0 代表测试模式(5秒)，用于开发调试；其余为正整数分钟。
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
                            originalVolume = playbackManager()?.playerVolume ?: 1.0f
                            if (isShakeToResetEnabled() || sleepMode() != com.viel.aplayer.data.store.SleepMode.Regular) {
                                registerShakeListener(currentPlayback, currentMetadata)
                                registeredSensor = true
                            }
                        }

                        // 在最后 10 秒内采用 100ms 级别的高频精细调节。使用下划线 '_' 代替未使用的循环形参 'i'，消除 IDE 警告。
                        val steps = 10
                        for (i in 0 until steps) {
                            if (_sleepTimerMillis.value <= 0) break
                            delay(100)
                            if (currentPlayback().isPlaying) {
                                // 如果处于运动跟踪模式且正在运动，则跳过倒计时扣减，并将音量恢复至原音量，实现绝对精准的动作暂停计时。
                                if (sleepMode() == com.viel.aplayer.data.store.SleepMode.MotionTracking && isDeviceMoving) {
                                    playbackManager()?.playerVolume = originalVolume
                                    continue
                                }
                                if (sleepMode() == com.viel.aplayer.data.store.SleepMode.SleepTracking && !hasUserFallenAsleep) {
                                    playbackManager()?.playerVolume = originalVolume
                                    continue
                                }

                                _sleepTimerMillis.value = (_sleepTimerMillis.value - 100).coerceAtLeast(0L)
                                val ratio = _sleepTimerMillis.value.toFloat() / 10000f
                                // 对数衰减曲线：Volume = OriginalVolume * ln(1 + 9 * ratio) / ln(10)
                                val factor = (ln(1.0 + 9.0 * ratio.toDouble()) / ln(10.0)).toFloat()
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
                            // 根据不同的睡眠模式执行不同的扣减或拦截算法
                            when (sleepMode()) {
                                com.viel.aplayer.data.store.SleepMode.Regular -> {
                                    // 常规模式：只要在播放，每秒无条件扣减 1000 毫秒
                                    _sleepTimerMillis.value = (_sleepTimerMillis.value - 1000).coerceAtLeast(0L)
                                }
                                com.viel.aplayer.data.store.SleepMode.MotionTracking -> {
                                    // 运动跟踪模式：只有检测到设备处于静止（!isDeviceMoving）时才计时；如果运动则停止/暂停倒计时
                                    if (!isDeviceMoving) {
                                        _sleepTimerMillis.value = (_sleepTimerMillis.value - 1000).coerceAtLeast(0L)
                                    }
                                }
                                com.viel.aplayer.data.store.SleepMode.SleepTracking -> {
                                    // 睡眠跟踪模式：只有检测到用户进入睡眠状态后才开始计时
                                    if (!hasUserFallenAsleep) {
                                        if (!isDeviceMoving) {
                                            timeInStaticMs += 1000L
                                            // 累计接近静止满 10 分钟（600000 毫秒），判定为已安稳入睡，开始倒计时
                                            if (timeInStaticMs >= 600000L) {
                                                hasUserFallenAsleep = true
                                                showToast("睡眠检测：检测到已安稳入睡，开始睡眠倒计时")
                                            }
                                        } else {
                                            timeInStaticMs = 0L
                                        }
                                    } else {
                                        // 已入睡状态：开始计时扣减
                                        _sleepTimerMillis.value = (_sleepTimerMillis.value - 1000).coerceAtLeast(0L)
                                    }
                                }
                            }
                        }
                    }
                }
                playbackManager()?.pause()
            } finally {
                // try-finally 黄金保护：定时器结束、中途取消、重置倒计时等场景下无条件确保原音量安全复原，传感器释放防泄露
                if (isFading) {
                    playbackManager()?.playerVolume = originalVolume
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
