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
    /** 详尽的中文注释：支持 Composable 子组件向 ViewModel 分发 Toast 一次性消息的桥接动作回调。 */
    val onShowToast: (String) -> Unit = {},
)