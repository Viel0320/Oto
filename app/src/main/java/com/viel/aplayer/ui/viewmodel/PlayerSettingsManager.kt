package com.viel.aplayer.ui.viewmodel

import android.media.AudioManager
import com.viel.aplayer.ui.state.PlayerSettingsState
import com.viel.aplayer.ui.state.PlaybackState
import com.viel.aplayer.ui.state.BookMetadataState
import com.viel.aplayer.playback.PlaybackManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 播放器设置与 UI 交互管理器。
 * 负责睡眠定时器、音量逻辑、界面显隐控制及 Tab 切换。
 */
class PlayerSettingsManager(
    private val scope: CoroutineScope,
    private val playbackManager: () -> PlaybackManager?,
    private val audioManager: () -> AudioManager?
) {
    private val _settingsState = MutableStateFlow(PlayerSettingsState())
    val settingsState: StateFlow<PlayerSettingsState> = _settingsState.asStateFlow()

    private val _sleepTimerMillis = MutableStateFlow(0L)
    val sleepTimerMillis: StateFlow<Long> = _sleepTimerMillis.asStateFlow()

    private var sleepTimerJob: Job? = null
    private var volumeAccumulator = 0f

    /**
     * 设置睡眠定时器。
     */
    fun setSleepTimer(minutes: Int, currentPlayback: () -> PlaybackState, currentMetadata: () -> BookMetadataState) {
        sleepTimerJob?.cancel()
        _settingsState.update { it.copy(selectedSleepTimer = minutes) }
        
        if (minutes == 0) {
            _sleepTimerMillis.value = 0L
            return
        }
        
        if (minutes == -2) { // 章节结束停止模式
            sleepTimerJob = scope.launch {
                while (true) {
                    delay(1000)
                    val state = currentPlayback()
                    val meta = currentMetadata()
                    if (state.isPlaying) {
                        val currentChapter = meta.chapters.findLast { state.currentPosition >= it.startPositionMs }
                        if (currentChapter != null) {
                            val endPos = currentChapter.startPositionMs + currentChapter.durationMs
                            if (state.currentPosition >= endPos - 1000) break
                        } else {
                            if (state.duration > 0 && state.currentPosition >= state.duration - 1000) break
                        }
                    }
                }
                playbackManager()?.pause()
                resetSleepTimer()
            }
            return
        }

        val millis = if (minutes < 0) 5000L else minutes * 60 * 1000L
        _sleepTimerMillis.value = millis
        
        sleepTimerJob = scope.launch {
            while (_sleepTimerMillis.value > 0) {
                delay(1000)
                if (currentPlayback().isPlaying) {
                    _sleepTimerMillis.value = (_sleepTimerMillis.value - 1000).coerceAtLeast(0L)
                }
            }
            playbackManager()?.pause()
            resetSleepTimer()
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
