package com.viel.aplayer.media.service

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.util.Log
import androidx.media3.common.Player
import com.viel.aplayer.data.AppSettingsRepository
import com.viel.aplayer.media.AutoRewindManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 播放音频焦点与通知避让状态管理器。
 * 专门负责管理系统级的音频焦点（Audio Focus）申请、释放，以及在开启“通知避让”机制下，
 * 由焦点临时变化（如收到微信通知、短信、来电响铃等）引起的“自主静音暂停”与“重获焦点自动恢复”状态机。
 * 实现了底层音频硬件监听行为与前台 Service 生命周期的物理隔离，提升核心业务系统的测试性与稳定性。
 */
class PlaybackAudioFocusManager(
    private val context: Context,
    private val serviceScope: CoroutineScope,
    private val settingsRepository: AppSettingsRepository,
    private val playerProvider: () -> Player?
) {
    // 使用应用上下文规避 Activity/Service 销毁时的内存泄露
    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    // 标志：是否因为临时被动失去音频焦点（如来电/短信通知等）而被迫暂停了播放
    // 用于在重获音频焦点后，执行精确的自主无感起播恢复
    private var isPausedByLossOfFocus = false

    // 缓存 API 26 (Android 8.0) 及以上版本的高级 AudioFocusRequest 物理请求描述实体，方便动态跨生命周期解绑与绑定
    private var audioFocusRequest: AudioFocusRequest? = null

    // 音频焦点监听器，处理系统多媒体环境的并发抢占事件
    private val audioFocusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        val player = playerProvider() ?: return@OnAudioFocusChangeListener
        serviceScope.launch {
            // 获取最新的用户避让机制设置
            val settings = settingsRepository.settingsFlow.first()
            if (settings.isNotificationAvoidanceEnabled) {
                when (focusChange) {
                    AudioManager.AUDIOFOCUS_LOSS -> {
                        // 永久失去音频焦点（如用户打开了别的音乐播放器），重置避让恢复标志并暂停当前播放
                        isPausedByLossOfFocus = false
                        player.pause()
                        Log.d("AudioFocusManager", "永久失去焦点，执行暂停")
                    }
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                        // 临时失去音频焦点（如通知、来电铃声），如果当前正在播放，则执行临时避让
                        if (player.isPlaying) {
                            isPausedByLossOfFocus = true
                            // 极关键交互：在暂停播放前，通知 AutoRewindManager 强行忽略下一次由于状态变动触发的“自动回退”逻辑
                            // 保障用户在通知结束后自动续播时的绝对连续性，杜绝高频卡顿重播现象
                            AutoRewindManager.getInstance(appContext).ignoreNextAutoRewind = true
                            player.pause()
                            Log.d("AudioFocusManager", "临时失去焦点，执行避让暂停并拦截回退")
                        }
                    }
                    AudioManager.AUDIOFOCUS_GAIN -> {
                        // 重新赢回了系统完整的音频焦点，若属于之前的被动避让暂停，则立即自主唤醒起播并重置标记
                        if (isPausedByLossOfFocus) {
                            isPausedByLossOfFocus = false
                            player.play()
                            Log.d("AudioFocusManager", "重获焦点，执行自动续播自愈")
                        }
                    }
                }
            }
        }
    }

    /**
     * 当底层播放器实际的播放状态发生改变时触发（如用户点击暂停/播放按钮）。
     * 用于在“通知避让”开启时，实时使系统焦点持有状态与实际播放状态保持高强一致性。
     *
     * @param isPlaying 播放器当前是否处于实际起播/播放状态
     */
    fun handlePlayerPlayingStateChanged(isPlaying: Boolean) {
        serviceScope.launch {
            val settings = settingsRepository.settingsFlow.first()
            if (settings.isNotificationAvoidanceEnabled) {
                if (isPlaying) {
                    // 主发起播时，清理旧避让历史标志，并立刻向系统注册申请音频焦点
                    isPausedByLossOfFocus = false
                    requestMyAudioFocus()
                } else {
                    // 暂停时，只有在非临时被动焦点失去造成的暂停情况下，才主动放弃占用的焦点
                    if (!isPausedByLossOfFocus) {
                        abandonMyAudioFocus()
                    }
                }
            }
        }
    }

    /**
     * 接管并强制重置当前的避让状态，主动向系统注销占用的焦点描述符。
     * 通常在设置页开关切换、退出播放或服务销毁（onDestroy）时使用。
     */
    fun reset() {
        isPausedByLossOfFocus = false
        abandonMyAudioFocus()
    }

    /**
     * 使用现代 Android 8.0+ 的 AudioFocusRequest 物理请求机制申请焦点。
     */
    private fun requestMyAudioFocus(): Boolean {
        val manager = audioManager ?: return false
        val request = audioFocusRequest ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH) // 标志为有声书朗读类型
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .build()
            )
            .setAcceptsDelayedFocusGain(false) // 拒绝延迟焦点，保障响应的确定性
            .setOnAudioFocusChangeListener(audioFocusChangeListener)
            .build().also { audioFocusRequest = it }
        
        val result = manager.requestAudioFocus(request)
        val success = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        Log.d("AudioFocusManager", "向系统申请音频焦点结果: $success (code: $result)")
        return success
    }

    /**
     * 使用现代 Android 8.0+ 的 abandonAudioFocusRequest 物理请求机制放弃并注销当前持有的焦点。
     */
    private fun abandonMyAudioFocus() {
        val manager = audioManager ?: return
        audioFocusRequest?.let {
            val result = manager.abandonAudioFocusRequest(it)
            Log.d("AudioFocusManager", "向系统释放音频焦点结果: $result")
            audioFocusRequest = null
        }
    }
}
