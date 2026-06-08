package com.viel.aplayer.ui.player

import com.viel.aplayer.event.feedback.FeedbackMessage

data class PlaybackControlActions(
    val onPlayPauseClick: () -> Unit = {},
    val onSkipForward: () -> Unit = {},
    val onSkipBackward: () -> Unit = {},
    val onCyclePlaybackSpeed: () -> Unit = {},
    val onResetPlaybackSpeed: () -> Unit = {},
    val onCycleSleepTimer: () -> Unit = {},
    val onCancelSleepTimer: () -> Unit = {},
    val onSeek: (positionMs: Long, allowUndo: Boolean) -> Unit = { _, _ -> },
    val onUndoSeek: () -> Unit = {},
    val onAdjustVolume: (delta: Float) -> Unit = {},
    val onNextChapter: () -> Unit = {},
    val onPreviousChapter: () -> Unit = {},
    /**
     * Resource Feedback Callback (Dispatches player-control tips through localized message keys)
     *
     * Player controls produce feedback facts instead of raw copy so Toast rendering and localization stay
     * centralized in the app shell.
     */
    val onShowToast: (FeedbackMessage) -> Unit = {},
)
