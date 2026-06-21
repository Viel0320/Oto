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
    val onNextChapter: () -> Unit = {},
    val onPreviousChapter: () -> Unit = {},
    /**
     * Reports a tapped chapter whose backing file is gone.
     *
     * Leaf chapter rows raise this intent with the book id; the command owner publishes the recovery
     * feedback fact, keeping feedback classification out of the composable.
     */
    val onMissingChapterClick: (bookId: String) -> Unit = {},
)
