package com.viel.aplayer.ui.settings

import android.content.Context
import android.media.AudioManager
import com.viel.aplayer.event.feedback.FeedbackMessage
import com.viel.aplayer.media.PlaybackManager
import com.viel.aplayer.ui.player.BookMetadataState
import com.viel.aplayer.ui.player.PlaybackState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * UI and playback settings manager (Controller for UI options and sleep scheduling delegation)
 * Organizes view-level parameters, system volume adjustment, and sleep countdowns.
 * Direct countdown logic, shake detection, fade-out behavior, and tactile feedback
 * are delegated to [SleepTimerManager] to enforce the single responsibility principle.
 */
class PlayerSettingsManager(
    private val scope: CoroutineScope,
    private val playbackManager: () -> PlaybackManager?,
    private val audioManager: () -> AudioManager?,
    // Dynamic Context provider (To prevent memory leaks from capturing Activity/Fragment contexts)
    // Invokes a lambda context provider rather than caching a strong context reference.
    private val contextProvider: () -> Context?,
    // Player Feedback Callback (Routes helper-level tips to the app event sink through PlayerViewModel)
    // This prevents SleepTimerManager from using PlaybackManager as a toast transport.
    private val onShowToast: (FeedbackMessage) -> Unit = {}
) {
    private val _settingsState = MutableStateFlow(PlayerSettingsState())
    val settingsState: StateFlow<PlayerSettingsState> = _settingsState.asStateFlow()

    private var volumeAccumulator = 0f

    // Sleep fade-out toggle (To cache persistence state for volume decay behavior)
    // Synchronized with DataStore by external ViewModels.
    var isSleepFadeOutEnabled: Boolean = true

    // Shake-to-reset toggle (To enable/disable motion-triggered sleep resets)
    // Synchronized with DataStore by external ViewModels.
    var isShakeToResetEnabled: Boolean = true

    // Sleep mode state (To differentiate timer target modes like regular or end-of-chapter)
    // Synchronized with DataStore by external ViewModels.
    var sleepMode: com.viel.aplayer.data.store.SleepMode = com.viel.aplayer.data.store.SleepMode.Regular

    // Sleep timer engine (To execute core sleep timer scheduling and sensor tracking)
    // Instantiates SleepTimerManager as a separate cohesive service layer.
    private val sleepTimerManager = SleepTimerManager(
        scope = scope,
        playbackManager = playbackManager,
        contextProvider = contextProvider,
        isSleepFadeOutEnabled = { isSleepFadeOutEnabled },
        isShakeToResetEnabled = { isShakeToResetEnabled },
        sleepMode = { sleepMode },
        onShowToast = onShowToast,
        selectedSleepTimer = { _settingsState.value.selectedSleepTimer },
        onTimerReset = {
            // Reset selection state (To reset the selected timer value to zero when timer stops)
            // Updates selectedSleepTimer in the outer state flow.
            _settingsState.update { it.copy(selectedSleepTimer = 0) }
        },
        onTimerSelectedMinutesChanged = { minutes ->
            // Synchronize selection state (To align outer selection state with active timer value during reset)
            // Updates the outer selected sleep timer minutes.
            _settingsState.update { it.copy(selectedSleepTimer = minutes) }
        }
    )

    // Delegate remaining duration (To expose sleep timer progress to observing UI layers)
    // Delegates the sleep countdown Flow directly to SleepTimerManager.
    val sleepTimerMillis: StateFlow<Long> = sleepTimerManager.sleepTimerMillis

    /**
     * Start sleep timer (To schedule sleep timer duration)
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
     * Adjust system volume (To alter STREAM_MUSIC stream volume based on drag gesture delta)
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

    // --- UI visibility and Tab controls ---

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
