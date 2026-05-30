package com.viel.aplayer.ui.settings

import android.content.Context
import android.media.AudioManager
import com.viel.aplayer.media.PlaybackManager
import com.viel.aplayer.ui.player.BookMetadataState
import com.viel.aplayer.ui.player.PlaybackState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * 播放器设置与 UI 交互管理器。
 * 经过抽取重构，将睡眠定时器直接相关的倒计时控制、物理传感器防抖检测、渐隐衰减以及震动反馈等逻辑
 * 抽取到了专职的 SleepTimerManager 中，以遵循单一职责原则。
 * 本管理器作为外层控制器，继续保留设定、音量逻辑、界面显隐控制及 Tab 切换等逻辑，保持接口一致。
 */
class PlayerSettingsManager(
    private val scope: CoroutineScope,
    private val playbackManager: () -> PlaybackManager?,
    private val audioManager: () -> AudioManager?,
    // 动态 Context 提供者 lambda，用以避免直接持有 Activity/Fragment 等 Context 造成的内存泄漏隐患。
    private val contextProvider: () -> Context?
) {
    private val _settingsState = MutableStateFlow(PlayerSettingsState())
    val settingsState: StateFlow<PlayerSettingsState> = _settingsState.asStateFlow()

    private var volumeAccumulator = 0f

    // 睡眠定时器音量渐隐机制的全局开关，由外部 ViewModel 实时同步持久化配置。
    var isSleepFadeOutEnabled: Boolean = true

    // 摇晃手机重置睡眠定时器的全局控制开关，由外部 ViewModel 实时同步持久化配置。
    var isShakeToResetEnabled: Boolean = true

    // 睡眠模式状态变量，由外部 ViewModel 实时同步 DataStore。
    var sleepMode: com.viel.aplayer.data.store.SleepMode = com.viel.aplayer.data.store.SleepMode.Regular

    // 实例化底层的睡眠定时器引擎 SleepTimerManager，将其作为独立业务模块引入，实现单一职责。
    private val sleepTimerManager = SleepTimerManager(
        scope = scope,
        playbackManager = playbackManager,
        contextProvider = contextProvider,
        isSleepFadeOutEnabled = { isSleepFadeOutEnabled },
        isShakeToResetEnabled = { isShakeToResetEnabled },
        sleepMode = { sleepMode },
        selectedSleepTimer = { _settingsState.value.selectedSleepTimer },
        onTimerReset = {
            // 当定时器结束或被重置时，更新外层的 UI 状态 selectedSleepTimer 为 0
            _settingsState.update { it.copy(selectedSleepTimer = 0) }
        },
        onTimerSelectedMinutesChanged = { minutes ->
            // 当定时器触发摇晃重置等状态变更时，更新外层的设定分钟数
            _settingsState.update { it.copy(selectedSleepTimer = minutes) }
        }
    )

    // 睡眠倒计时剩余时间 StateFlow 流，直接委托给底层执行引擎。
    val sleepTimerMillis: StateFlow<Long> = sleepTimerManager.sleepTimerMillis

    /**
     * 设置睡眠定时器，委托给专职定时引擎 SleepTimerManager 执行。
     */
    fun setSleepTimer(
        minutes: Int,
        currentPlayback: () -> PlaybackState,
        currentMetadata: () -> BookMetadataState,
        isShakeReset: Boolean = false
    ) {
        _settingsState.update { it.copy(selectedSleepTimer = minutes) }
        sleepTimerManager.setSleepTimer(minutes, currentPlayback, currentMetadata, isShakeReset)
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