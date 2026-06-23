package com.viel.aplayer.ui.player

import android.content.Context
import android.media.AudioManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.viel.aplayer.application.library.settings.AppSettingsCommands
import com.viel.aplayer.application.library.settings.AppSettingsReadModel
import com.viel.aplayer.application.playback.PlayerPlaybackController
import com.viel.aplayer.event.AppEventSink
import com.viel.aplayer.event.feedback.PlaybackControlFeedbackFacts
import com.viel.aplayer.ui.settings.FullPlayerOpenSource
import com.viel.aplayer.ui.settings.PlayerSettingsManager
import com.viel.aplayer.ui.settings.PlayerSettingsState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class PlayerSettingsViewModel(
    application: android.app.Application,
    private val settingsReadModel: AppSettingsReadModel,
    private val settingsCommands: AppSettingsCommands,
    private val appEventSink: AppEventSink,
    private val playerPlaybackController: PlayerPlaybackController,
    rawExternalScope: CoroutineScope? = null
) : ViewModel() {

    private val externalScope = rawExternalScope ?: viewModelScope

    private val audioManager = application.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val settingsManager = PlayerSettingsManager(
        scope = externalScope,
        playbackController = { playerPlaybackController },
        audioManager = { audioManager },
        contextProvider = { application },
        onFeedback = { fact -> emitFeedback(fact) }
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

    fun setSleepTimer(
        minutes: Int,
        currentPlayback: () -> PlaybackState,
        currentMetadata: () -> BookMetadataState
    ) {
        settingsManager.setSleepTimer(minutes, currentPlayback, currentMetadata)
        appEventSink.emitFeedback(PlaybackControlFeedbackFacts.sleepTimerSelected(minutes))
    }

    fun cycleSleepTimer(
        currentPlayback: () -> PlaybackState,
        currentMetadata: () -> BookMetadataState
    ) {
        val options = listOf(0, -2, 15, 30, 60)
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

    private fun emitFeedback(fact: com.viel.aplayer.event.feedback.FeedbackFact) {
        appEventSink.emitFeedback(fact)
    }

    /**
     * Updates the seek undo banner visibility configuration.
     *
     * @param visible The desired visibility state of the undo seek banner.
     */
    fun setUndoSeekVisible(visible: Boolean) {
        settingsManager.setUndoSeekVisible(visible)
    }
}
