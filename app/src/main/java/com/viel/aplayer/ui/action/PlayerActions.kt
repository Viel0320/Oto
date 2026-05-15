package com.viel.aplayer.ui.action

import com.viel.aplayer.data.AudiobookEntity
import com.viel.aplayer.data.BookmarkEntity

data class PlaybackControlActions(
    val onPlayPauseClick: () -> Unit = {},
    val onSkipForward: () -> Unit = {},
    val onSkipBackward: () -> Unit = {},
    val onCyclePlaybackSpeed: () -> Unit = {},
    val onResetPlaybackSpeed: () -> Unit = {},
    val onCycleSleepTimer: () -> Unit = {},
    val onCancelSleepTimer: () -> Unit = {},
)

data class MiniPlayerActions(
    val onPlayPauseClick: () -> Unit = {},
    val onHide: () -> Unit = {},
)

data class PlayerActions(
    val onSeek: (positionMs: Long, allowUndo: Boolean) -> Unit = { _, _ -> },
    val onUndoSeek: () -> Unit = {},
    val onDeleteBookmark: (BookmarkEntity) -> Unit = {},
    val onUpdateBookmark: (BookmarkEntity, String) -> Unit = { _, _ -> },
    val onPlayPauseClick: () -> Unit = {},
    val onSkipForward: () -> Unit = {},
    val onSkipBackward: () -> Unit = {},
    val onCyclePlaybackSpeed: () -> Unit = {},
    val onResetPlaybackSpeed: () -> Unit = {},
    val onCycleSleepTimer: () -> Unit = {},
    val onCancelSleepTimer: () -> Unit = {},
    val onSelectedContentTabChange: (Int) -> Unit = {},
    val onShowChapterList: () -> Unit = {},
    val onDismissChapterList: () -> Unit = {},
    val onShowBookmarkDialog: () -> Unit = {},
    val onDismissBookmarkDialog: () -> Unit = {},
    val onBookmarkTitleChange: (String) -> Unit = {},
    val onSaveBookmark: () -> Unit = {},
    val onToggleProgressMode: () -> Unit = {},
    val onAdjustVolume: (delta: Float) -> Unit = {},
    val onNextChapter: () -> Unit = {},
    val onPreviousChapter: () -> Unit = {},
    val onLoadRelatedBook: (AudiobookEntity) -> Unit = {},
) {
    val playbackControls: PlaybackControlActions = PlaybackControlActions(
        onPlayPauseClick = onPlayPauseClick,
        onSkipForward = onSkipForward,
        onSkipBackward = onSkipBackward,
        onCyclePlaybackSpeed = onCyclePlaybackSpeed,
        onResetPlaybackSpeed = onResetPlaybackSpeed,
        onCycleSleepTimer = onCycleSleepTimer,
        onCancelSleepTimer = onCancelSleepTimer
    )

}

data class PlayerNavigationActions(
    val onMinimize: () -> Unit = {},
    val onClose: () -> Unit = {},
    val onBookmarksClick: () -> Unit = {},
    val onSubtitlesClick: () -> Unit = {},
    val onRelatedClick: () -> Unit = {},
    val onNavigateToNewPlayer: () -> Unit = {},
)
