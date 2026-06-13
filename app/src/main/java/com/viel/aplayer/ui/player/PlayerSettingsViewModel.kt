package com.viel.aplayer.ui.player

import android.app.Application
import android.content.Context
import android.media.AudioManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.viel.aplayer.APlayerApplication
import com.viel.aplayer.event.feedback.FeedbackMessage
import com.viel.aplayer.ui.settings.FullPlayerOpenSource
import com.viel.aplayer.ui.settings.PlayerSettingsManager
import com.viel.aplayer.ui.settings.PlayerSettingsState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

// Create PlayerSettingsViewModel (Manages playback settings, sleep timer and overlay views)
// This ViewModel isolates all player preferences, system volume control, and full/mini player visibility states.
class PlayerSettingsViewModel(
    application: Application,
    rawExternalScope: CoroutineScope? = null
) : AndroidViewModel(application) {

    // Shift scope to viewModelScope to prevent lifecycle leaks and screen rotation freezes
    // Fall back to viewModelScope if rawExternalScope is null to ensure coroutines are correctly managed.
    private val externalScope = rawExternalScope ?: viewModelScope

    // Resolve dependencies (Fetches managers and system service controllers from application presentation di)
    private val playerDependencies = APlayerApplication.getPlayerScreenDependencies(application)
    // Title: Settings Abstractions Binding (Bind PlayerSettingsViewModel to read and command abstractions)
    // Decouples player UI preference logic from concrete DataStore storage classes.
    private val settingsReadModel = playerDependencies.settingsReadModel
    private val settingsCommands = playerDependencies.settingsCommands
    private val appEventSink = playerDependencies.appEventSink
    private val audioManager = application.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    // Settings manager delegate (Wraps system parameters and sleep scheduling logic)
    private val settingsManager = PlayerSettingsManager(
        scope = externalScope,
        playbackController = { playerDependencies.playerPlaybackController },
        audioManager = { audioManager },
        contextProvider = { application },
        onShowToast = { message -> showToast(message) }
    )

    val settingsState: StateFlow<PlayerSettingsState> = settingsManager.settingsState
    val sleepTimerMillis: StateFlow<Long> = settingsManager.sleepTimerMillis

    init {
        observeSettings()
    }

    private fun observeSettings() {
        externalScope.launch {
            settingsReadModel.settingsFlow.collect { settings ->
                if (settings.isChapterProgressMode != settingsState.value.isChapterProgressMode) {
                    settingsManager.setChapterProgressMode(settings.isChapterProgressMode)
                }
                settingsManager.isSleepFadeOutEnabled = settings.isSleepFadeOutEnabled
                settingsManager.isShakeToResetEnabled = settings.isShakeToResetEnabled
                settingsManager.sleepMode = settings.sleepMode
                settingsManager.setPlaybackSeekStepConfig(settings.playbackSeekStepConfig)
            }
        }
    }

    // Set sleep timer (Starts sleep timer countdown utilizing current playback parameters)
    fun setSleepTimer(
        minutes: Int,
        currentPlayback: () -> PlaybackState,
        currentMetadata: () -> BookMetadataState
    ) {
        settingsManager.setSleepTimer(minutes, currentPlayback, currentMetadata)
    }

    // Cycle sleep timer (Increments selected sleep minutes sequentially)
    fun cycleSleepTimer(
        currentPlayback: () -> PlaybackState,
        currentMetadata: () -> BookMetadataState
    ) {
        val options = listOf(0, -1, -2, 15, 30, 60)
        val nextIndex = (options.indexOf(settingsState.value.selectedSleepTimer).coerceAtLeast(0) + 1) % options.size
        setSleepTimer(options[nextIndex], currentPlayback, currentMetadata)
    }

    fun adjustVolume(delta: Float) {
        settingsManager.adjustVolume(delta)
    }

    fun showChapterList() = settingsManager.showChapterList()
    fun dismissChapterList() = settingsManager.dismissChapterList()

    fun showBookmarkDialog() = settingsManager.showBookmarkDialog()
    fun dismissBookmarkDialog() = settingsManager.dismissBookmarkDialog()

    fun updateBookmarkTitle(title: String) = settingsManager.updateBookmarkTitle(title)
    fun setSelectedContentTab(tab: Int) = settingsManager.setSelectedContentTab(tab)

    fun setFullPlayerVisible(visible: Boolean, openSource: FullPlayerOpenSource = FullPlayerOpenSource.Direct) {
        settingsManager.setFullPlayerVisible(visible, openSource)
        if (visible) {
            settingsManager.setMiniPlayerHidden(false)
        }
    }

    fun openFullPlayerFromDirect() {
        settingsManager.setSelectedContentTab(PlayerScreenMode.PLAYER.index)
        setFullPlayerVisible(visible = true, openSource = FullPlayerOpenSource.Direct)
    }

    fun openFullPlayerFromMini() {
        settingsManager.setSelectedContentTab(PlayerScreenMode.PLAYER.index)
        setFullPlayerVisible(visible = true, openSource = FullPlayerOpenSource.MiniPlayer)
    }

    fun setMiniPlayerHidden(hidden: Boolean) = settingsManager.setMiniPlayerHidden(hidden)
    fun onRouteChanged() = settingsManager.setMiniPlayerHidden(false)

    fun toggleProgressMode() {
        externalScope.launch {
            val nextMode = !settingsState.value.isChapterProgressMode
            settingsCommands.updateChapterProgressMode(nextMode)
        }
    }

    fun showToast(message: FeedbackMessage) {
        appEventSink.showToast(message)
    }
}
