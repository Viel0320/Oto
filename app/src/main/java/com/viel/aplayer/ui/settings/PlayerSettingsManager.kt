package com.viel.aplayer.ui.settings

import android.content.Context
import android.media.AudioManager
import com.viel.aplayer.application.playback.PlayerPlaybackController
import com.viel.aplayer.event.feedback.FeedbackFact
import com.viel.aplayer.shared.settings.PlaybackSeekStepConfig
import com.viel.aplayer.ui.player.BookMetadataState
import com.viel.aplayer.ui.player.PlaybackState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Controller for UI options and sleep scheduling delegation.
 * Organizes view-level parameters, system volume adjustment, and sleep countdowns.
 * Direct countdown logic, shake detection, fade-out behavior, and tactile feedback
 * are delegated to [SleepTimerManager] to enforce the single responsibility principle.
 */
class PlayerSettingsManager(
    private val scope: CoroutineScope,
    private val playbackController: () -> PlayerPlaybackController?,
    private val audioManager: () -> AudioManager?,
    private val contextProvider: () -> Context?,
    private val onFeedback: (FeedbackFact) -> Unit = {}
) {
    private val _settingsState = MutableStateFlow(PlayerSettingsState())
    val settingsState: StateFlow<PlayerSettingsState> = _settingsState.asStateFlow()

    private var volumeAccumulator = 0f

    var isSleepFadeOutEnabled: Boolean = true

    var isShakeToResetEnabled: Boolean = true

    var sleepMode: com.viel.aplayer.shared.settings.SleepMode = com.viel.aplayer.shared.settings.SleepMode.Regular

    private val sleepTimerManager = SleepTimerManager(
        scope = scope,
        playbackController = playbackController,
        contextProvider = contextProvider,
        isSleepFadeOutEnabled = { isSleepFadeOutEnabled },
        isShakeToResetEnabled = { isShakeToResetEnabled },
        sleepMode = { sleepMode },
        onFeedback = onFeedback,
        selectedSleepTimer = { _settingsState.value.selectedSleepTimer },
        onTimerReset = {
            _settingsState.update { it.copy(selectedSleepTimer = 0) }
        },
        onTimerSelectedMinutesChanged = { minutes ->
            _settingsState.update { it.copy(selectedSleepTimer = minutes) }
        }
    )

    val sleepTimerMillis: StateFlow<Long> = sleepTimerManager.sleepTimerMillis

    /**
     * To schedule sleep timer duration.
     * Forwards settings parameters to the dedicated SleepTimerManager engine.
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
     * To alter STREAM_MUSIC stream volume based on drag gesture delta.
     * Accumulates delta and increases/decreases volume when threshold is crossed.
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


    fun showChapterList() = _settingsState.update { it.copy(isChapterListVisible = true) }
    fun dismissChapterList() = _settingsState.update { it.copy(isChapterListVisible = false) }
    fun showBookmarkDialog() = _settingsState.update { it.copy(isBookmarkDialogVisible = true, bookmarkTitle = "") }
    fun dismissBookmarkDialog() = _settingsState.update { it.copy(isBookmarkDialogVisible = false, bookmarkTitle = "") }
    fun updateBookmarkTitle(title: String) = _settingsState.update { it.copy(bookmarkTitle = title) }
    fun setSelectedContentTab(tab: Int) = _settingsState.update { it.copy(selectedContentTab = tab) }
    /**
     * Keep motion origin and visibility in one state frame.
     * Updating the open source with the visibility flag prevents direct playback entries from
     * briefly inheriting stale mini-player transition eligibility during the same recomposition.
     */
    fun setFullPlayerVisible(
        visible: Boolean,
        openSource: FullPlayerOpenSource = FullPlayerOpenSource.Direct
    ) = _settingsState.update {
        it.copy(
            isFullPlayerVisible = visible,
            fullPlayerOpenSource = if (visible) openSource else it.fullPlayerOpenSource
        )
    }
    fun setMiniPlayerHidden(hidden: Boolean) = _settingsState.update { it.copy(isMiniPlayerHidden = hidden) }
    fun setChapterProgressMode(enabled: Boolean) = _settingsState.update { it.copy(isChapterProgressMode = enabled) }
    fun setPlaybackSeekStepConfig(config: PlaybackSeekStepConfig) = _settingsState.update { it.copy(playbackSeekStepConfig = config) }
    fun setUndoSeekVisible(visible: Boolean) = _settingsState.update { it.copy(showUndoSeek = visible) }
}
