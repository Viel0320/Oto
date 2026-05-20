package com.viel.aplayer.ui.player


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
)