package com.viel.oto.ui.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.viel.oto.application.library.player.PlayerRelatedBook
import com.viel.oto.ui.player.components.bookmarks.BookmarkActions

/**
 * Player-scoped tab and related-book commands.
 *
 * Separates recommendation row navigation from explicit playback so Related rows can open Detail
 * while their play affordance still starts the selected audiobook immediately.
 */
data class PlayerContentActions(
    val onSelectedTabChange: (Int) -> Unit = {},
    val onShowChapterList: () -> Unit = {},
    val onDismissChapterList: () -> Unit = {},
    val onOpenRelatedBookDetail: (PlayerRelatedBook) -> Unit = {},
    val onLoadRelatedBook: (PlayerRelatedBook) -> Unit = {},
    val onToggleProgressMode: () -> Unit = {},
    val onDeleteBook: () -> Unit = {},
)

/**
 * Playback, bookmark, and content operation groups.
 *
 * Keeps high-frequency transport controls, bookmark commands, and content navigation in separate
 * payloads so callers do not need a broad player facade to wire scene-specific behavior.
 */
data class PlayerActions(
    val playback: PlaybackControlActions = PlaybackControlActions(),
    val bookmarks: BookmarkActions = BookmarkActions(),
    val content: PlayerContentActions = PlayerContentActions(),
)

/**
 * Bind UI callbacks to the active player-scene owners.
 *
 * Direct playback commands stay on PlaybackViewModel, while Detail navigation is injected by the
 * app shell because only the shell owns DetailTransitionGate and full-player visibility.
 */
@Composable
fun rememberActions(
    playbackViewModel: PlaybackViewModel,
    bookmarkViewModel: BookmarkViewModel,
    settingsViewModel: PlayerSettingsViewModel,
    onDeleteBook: (String) -> Unit = {},
    onOpenRelatedBookDetail: (PlayerRelatedBook) -> Unit = {}
): PlayerActions {
    return remember(
        playbackViewModel,
        bookmarkViewModel,
        settingsViewModel,
        onDeleteBook,
        onOpenRelatedBookDetail
    ) {
        PlayerActions(
            playback = PlaybackControlActions(
                onSeek = { pos, allowUndo -> playbackViewModel.seekTo(pos, allowUndo) },
                onUndoSeek = { playbackViewModel.undoSeek() },
                onPlayPauseClick = { playbackViewModel.togglePlayPause() },
                onSkipForward = { playbackViewModel.skipForward() },
                onSkipBackward = { playbackViewModel.skipBackward() },
                onCyclePlaybackSpeed = { playbackViewModel.cyclePlaybackSpeed() },
                onResetPlaybackSpeed = { playbackViewModel.resetPlaybackSpeed() },
                onCycleSleepTimer = {
                    settingsViewModel.cycleSleepTimer(
                        currentPlayback = { playbackViewModel.currentPlaybackSnapshot() },
                        currentMetadata = { playbackViewModel.metadataState.value }
                    )
                },
                onCancelSleepTimer = {
                    settingsViewModel.setSleepTimer(
                        minutes = 0,
                        currentPlayback = { playbackViewModel.currentPlaybackSnapshot() },
                        currentMetadata = { playbackViewModel.metadataState.value }
                    )
                },
                onNextChapter = { playbackViewModel.skipToNextChapter() },
                onPreviousChapter = { playbackViewModel.skipToPreviousChapter() },
                onAdjustSubtitleSync = { deltaMs -> playbackViewModel.adjustSubtitleSyncOffset(deltaMs) },
                onResetSubtitleSync = { playbackViewModel.resetSubtitleSyncOffset() },
                onMissingChapterClick = { bookId -> playbackViewModel.reportMissingChapterFile(bookId) }
            ),
            bookmarks = BookmarkActions(
                onDelete = { bookmark -> bookmarkViewModel.deleteBookmark(bookmark) },
                onUpdate = { bookmark, newTitle -> bookmarkViewModel.updateBookmark(bookmark, newTitle) },
                onShowDialog = { settingsViewModel.showBookmarkDialog() },
                onDismissDialog = { settingsViewModel.dismissBookmarkDialog() },
                onTitleChange = { settingsViewModel.updateBookmarkTitle(it) },
                onSave = {
                    val bookId = playbackViewModel.currentBookId.value ?: return@BookmarkActions
                    val position = playbackViewModel.currentPlaybackSnapshot().currentPosition
                    val title = settingsViewModel.settingsState.value.bookmarkTitle
                    bookmarkViewModel.saveBookmarkFromDialog(bookId, position, title)
                    settingsViewModel.dismissBookmarkDialog()
                },
                onRequestDelete = { bookmark -> bookmarkViewModel.requestDeleteBookmark(bookmark) },
                onRequestEdit = { bookmark -> bookmarkViewModel.requestEditBookmark(bookmark) },
                onEditTitleChange = { title -> bookmarkViewModel.onBookmarkEditTitleChange(title) },
                onDismissDialogs = { bookmarkViewModel.dismissBookmarkDialogs() }
            ),
            content = PlayerContentActions(
                onSelectedTabChange = { settingsViewModel.setSelectedContentTab(it) },
                onShowChapterList = { settingsViewModel.showChapterList() },
                onDismissChapterList = { settingsViewModel.dismissChapterList() },
                onToggleProgressMode = { settingsViewModel.toggleProgressMode() },
                onOpenRelatedBookDetail = onOpenRelatedBookDetail,
                onLoadRelatedBook = { book ->
                    playbackViewModel.loadBook(book.id)
                },
                onDeleteBook = { playbackViewModel.currentBookId.value?.let { onDeleteBook(it) } }
            )
        )
    }
}

data class MiniPlayerActions(
    val onPlayPauseClick: () -> Unit = {},
    val onHide: () -> Unit = {},
    val onUnavailable: () -> Unit = {},
)
