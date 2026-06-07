package com.viel.aplayer.ui.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.viel.aplayer.ui.home.ContentActions
import com.viel.aplayer.ui.player.components.bookmarks.BookmarkActions

/**
 * Player actions aggregator (Aggregator for playback, bookmark, and content operations)
 * Groups area-specific Action payloads to facilitate routing parameter pass-throughs.
 */
data class PlayerActions(
    /** Core playback controls (To trigger play, pause, skip, seek, and chapter navigation) */
    val playback: PlaybackControlActions = PlaybackControlActions(),
    /** Bookmark operations (To dispatch bookmark addition, deletion, and rename updates) */
    val bookmarks: BookmarkActions = BookmarkActions(),
    /** Content layout actions (To handle tab navigation and chapter list toggles) */
    val content: ContentActions = ContentActions(),
)

/**
 * PlayerActions factory extension (Composable wrapper to remember actions instance)
 */
@Composable
fun PlayerViewModel.rememberActions(onDeleteBook: (String) -> Unit = {}): PlayerActions {
    return remember(this, onDeleteBook) {
        val viewModel = this
        PlayerActions(
            playback = PlaybackControlActions(
                onSeek = { pos, allowUndo -> viewModel.seekTo(pos, allowUndo) },
                onUndoSeek = { viewModel.undoSeek() },
                onPlayPauseClick = { viewModel.togglePlayPause() },
                onSkipForward = { viewModel.skipForward() },
                onSkipBackward = { viewModel.skipBackward() },
                onCyclePlaybackSpeed = { viewModel.cyclePlaybackSpeed() },
                onResetPlaybackSpeed = { viewModel.resetPlaybackSpeed() },
                onCycleSleepTimer = { viewModel.cycleSleepTimer() },
                onCancelSleepTimer = { viewModel.setSleepTimer(0) },
                onAdjustVolume = { delta -> viewModel.adjustVolume(delta) },
                onNextChapter = { viewModel.skipToNextChapter() },
                onPreviousChapter = { viewModel.skipToPreviousChapter() },
                // Map Local Visual Toast Actions (Route player-control tips into the app event sink)
                // Player controls report plain messages while the app shell remains the only Toast renderer.
                onShowToast = { msg -> viewModel.showToast(msg) }
            ),
            bookmarks = BookmarkActions(
                onDelete = { bookmark -> viewModel.deleteBookmark(bookmark) },
                onUpdate = { bookmark, newTitle -> viewModel.updateBookmark(bookmark, newTitle) },
                onShowDialog = { viewModel.showBookmarkDialog() },
                onDismissDialog = { viewModel.dismissBookmarkDialog() },
                onTitleChange = { viewModel.updateBookmarkTitle(it) },
                onSave = { viewModel.saveBookmarkFromDialog() }
            ),
            content = ContentActions(
                onSelectedTabChange = { viewModel.setSelectedContentTab(it) },
                onShowChapterList = { viewModel.showChapterList() },
                onDismissChapterList = { viewModel.dismissChapterList() },
                onToggleProgressMode = { viewModel.toggleProgressMode() },
                onLoadRelatedBook = { bookWithProgress ->
                    viewModel.loadBook(bookWithProgress.book.id)
                },
                onDeleteBook = { viewModel.currentBookId.value?.let { onDeleteBook(it) } }
            )
        )
    }
}
