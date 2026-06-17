package com.viel.aplayer.ui.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.viel.aplayer.application.library.player.PlayerRelatedBook
import com.viel.aplayer.ui.player.components.bookmarks.BookmarkActions

// Player Content Actions (Player-scoped tab and related-book commands)
// Avoids reusing the home content action model so player recommendations can stay on player-scene projections.
data class PlayerContentActions(
    val onSelectedTabChange: (Int) -> Unit = {},
    val onShowChapterList: () -> Unit = {},
    val onDismissChapterList: () -> Unit = {},
    val onLoadRelatedBook: (PlayerRelatedBook) -> Unit = {},
    val onToggleProgressMode: () -> Unit = {},
    val onDeleteBook: () -> Unit = {},
)

// Player actions aggregator (Aggregator for playback, bookmark, and content operations)
// Groups area-specific Action payloads to facilitate routing parameter pass-throughs.
data class PlayerActions(
    val playback: PlaybackControlActions = PlaybackControlActions(),
    val bookmarks: BookmarkActions = BookmarkActions(),
    val content: PlayerContentActions = PlayerContentActions(),
)

// Composable actions builder (Generates the aggregate Actions wrapper from active ViewModel targets)
// Binds UI events directly to correct specialized ViewModels instead of a single god object.
@Composable
fun rememberActions(
    playbackViewModel: PlaybackViewModel,
    bookmarkViewModel: BookmarkViewModel,
    settingsViewModel: PlayerSettingsViewModel,
    onDeleteBook: (String) -> Unit = {}
): PlayerActions {
    return remember(playbackViewModel, bookmarkViewModel, settingsViewModel, onDeleteBook) {
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
                        currentPlayback = { playbackViewModel.playbackState.value },
                        currentMetadata = { playbackViewModel.metadataState.value }
                    )
                },
                onCancelSleepTimer = {
                    settingsViewModel.setSleepTimer(
                        minutes = 0,
                        currentPlayback = { playbackViewModel.playbackState.value },
                        currentMetadata = { playbackViewModel.metadataState.value }
                    )
                },
                onNextChapter = { playbackViewModel.skipToNextChapter() },
                onPreviousChapter = { playbackViewModel.skipToPreviousChapter() },
                onShowToast = { msg -> playbackViewModel.showToast(msg) }
            ),
            bookmarks = BookmarkActions(
                onDelete = { bookmark -> bookmarkViewModel.deleteBookmark(bookmark) },
                onUpdate = { bookmark, newTitle -> bookmarkViewModel.updateBookmark(bookmark, newTitle) },
                onShowDialog = { settingsViewModel.showBookmarkDialog() },
                onDismissDialog = { settingsViewModel.dismissBookmarkDialog() },
                onTitleChange = { settingsViewModel.updateBookmarkTitle(it) },
                onSave = {
                    val bookId = playbackViewModel.currentBookId.value ?: return@BookmarkActions
                    val position = playbackViewModel.playbackState.value.currentPosition
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
                onLoadRelatedBook = { book ->
                    playbackViewModel.loadBook(book.id)
                },
                onDeleteBook = { playbackViewModel.currentBookId.value?.let { onDeleteBook(it) } }
            )
        )
    }
}

// Mini player actions aggregate (Declarative triggers for minimized playback actions)
// Represents UI callbacks for the mini player components, relocated to PlayerActions.kt to achieve compact code packaging.
data class MiniPlayerActions(
    val onPlayPauseClick: () -> Unit = {},
    val onHide: () -> Unit = {},
    val onUnavailable: () -> Unit = {},
)
