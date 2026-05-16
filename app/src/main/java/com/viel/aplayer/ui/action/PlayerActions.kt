package com.viel.aplayer.ui.action

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.core.net.toUri
import com.viel.aplayer.ui.viewmodel.PlayerViewModel

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
 * 核心职责：
 * 1. 桥接：将 [PlayerViewModel] 中的复杂业务逻辑映射为 UI 组件可直接使用的简单回调。
 * 2. 缓存：利用 [remember] 确保 Actions 对象在 ViewModel 实例不变的情况下保持稳定，避免子组件产生无效重组。
 * 3. 解耦：使 UI 组件只需依赖 Action 接口，无需直接持有 ViewModel 引用。
 */
@Composable
fun PlayerViewModel.rememberActions(): PlayerActions {
    return remember(this) {
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
                onPreviousChapter = { viewModel.skipToPreviousChapter() }
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
                onLoadRelatedBook = { book ->
                    viewModel.loadMedia(
                        uri = book.uri.toUri(),
                        title = book.title,
                        author = book.author,
                        narrator = book.narrator,
                        startPositionMs = book.lastPosition,
                        playWhenReady = true
                    )
                }
            )
        )
    }
}
