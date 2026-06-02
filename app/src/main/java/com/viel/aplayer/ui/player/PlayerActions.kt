package com.viel.aplayer.ui.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.viel.aplayer.ui.home.ContentActions
import com.viel.aplayer.ui.player.components.bookmarks.BookmarkActions

/**
 * 播放器操作聚合类。
 * 将不同领域的 Action（播放、书签、内容交互）组合在一起，方便在组件间传递。
 */
data class PlayerActions(
    /** 核心播放控制（播放/暂停、快进/快退、进度跳转、章节切换等） */
    val playback: PlaybackControlActions = PlaybackControlActions(),
    /** 书签相关操作（添加、删除、重命名及对话框控制） */
    val bookmarks: BookmarkActions = BookmarkActions(),
    /** 界面内容操作（标签切换、章节列表控制、加载推荐书籍等） */
    val content: ContentActions = ContentActions(),
)

/**
 * [PlayerActions] 的工厂扩展函数。
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
                // 详尽的中文注释：路由桥接。将 Composable 层防抖后触发的轻量 Toast 反馈映射为 ViewModel 的 sendUiEvent，从而在底层统一流入 MVI UiEvent 处理环中。
                onShowToast = { msg -> viewModel.sendUiEvent(com.viel.aplayer.ui.common.UiEvent.ShowToast(msg)) }
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