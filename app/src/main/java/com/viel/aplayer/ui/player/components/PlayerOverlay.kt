package com.viel.aplayer.ui.player.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import com.viel.aplayer.media.ChapterTimeline
import com.viel.aplayer.ui.navigation.PlayerNavigationActions
import com.viel.aplayer.ui.player.MiniPlayerActions
import com.viel.aplayer.ui.player.NewPlayerScreen
import com.viel.aplayer.ui.player.PlayerActions
import com.viel.aplayer.ui.player.PlayerViewModel

/**
 * 播放器悬浮层组件。
 * 负责协调全屏播放器 (Full Player) 和迷你播放器 (Mini Player) 的层级与切换。
 * 
 * 性能优化核心：
 * 1. 状态隔离：不直接收集全局 uiState，避免高频进度更新导致整个悬浮层重组。
 * 2. 信号过滤：使用 distinctUntilChanged 确保仅在显隐状态真正改变时才触发重组。
 * 3. 局部化更新：将高频状态收集下沉到 [MiniPlayerContent]，确保进度跳动不影响父布局。
 */
@Composable
fun PlayerOverlay(
    playerViewModel: PlayerViewModel,
    playerActions: PlayerActions,
    miniPlayerActions: MiniPlayerActions,
    playerNavigationActions: PlayerNavigationActions,
    currentRoute: String?,
    modifier: Modifier = Modifier
) {
    // 仅监听播放器可见性（低频信号）
    val isFullPlayerVisible by remember(playerViewModel) {
        playerViewModel.settingsState.map { it.isFullPlayerVisible }.distinctUntilChanged()
    }.collectAsStateWithLifecycle(initialValue = false)

    // 仅监听是否有活跃音轨（低频信号）
    val hasActiveTrack by remember(playerViewModel) {
        playerViewModel.metadataState.map { it.hasActiveTrack }.distinctUntilChanged()
    }.collectAsStateWithLifecycle(initialValue = false)
    
    // 仅监听迷你播放器手动隐藏状态
    val isMiniPlayerHidden by remember(playerViewModel) {
        playerViewModel.settingsState.map { it.isMiniPlayerHidden }.distinctUntilChanged()
    }.collectAsStateWithLifecycle(initialValue = false)

    Box(modifier = modifier.fillMaxSize()) {
        // 全屏播放器层
        AnimatedVisibility(
            visible = isFullPlayerVisible,
            enter = slideInVertically(initialOffsetY = { it }, animationSpec = tween(400)),
            exit = slideOutVertically(targetOffsetY = { it }, animationSpec = tween(400))
        ) {
            NewPlayerScreen(
                viewModel = playerViewModel,
                actions = playerActions,
                navigationActions = playerNavigationActions
            )
        }

        // 迷你播放器显示逻辑判断
        val showMiniPlayer = hasActiveTrack &&
                currentRoute != null &&
                !isFullPlayerVisible &&
                !currentRoute.startsWith("search")

        AnimatedVisibility(
            visible = showMiniPlayer && !isMiniPlayerHidden,
            enter = slideInVertically(
                initialOffsetY = { it },
                animationSpec = tween(400)
            ) + fadeIn(animationSpec = tween(400)),
            exit = slideOutVertically(
                targetOffsetY = { it },
                animationSpec = tween(400)
            ) + fadeOut(animationSpec = tween(400)),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            // 重要：将高频状态（进度、播放状态）隔离在此组件内部
            MiniPlayerContent(playerViewModel, miniPlayerActions)
        }
    }
}

/**
 * 迷你播放器内容容器。
 * 专门用于收集高频更新的状态（PlaybackState），从而将重组范围控制在最小范围内。
 */
@Composable
private fun MiniPlayerContent(
    viewModel: PlayerViewModel,
    actions: MiniPlayerActions
) {
    // 在此处收集高频状态，进度更新只会引起这个 Box 及其子项重组
    val playback by viewModel.playbackState.collectAsStateWithLifecycle()
    val metadata by viewModel.metadataState.collectAsStateWithLifecycle()
    val settings by viewModel.settingsState.collectAsStateWithLifecycle()
    val isMediaAvailable by remember(viewModel, metadata.id) {
        // Compact player checks the current book availability only while it is mounted.
        viewModel.currentBookAvailability(metadata.id)
    }.collectAsStateWithLifecycle(initialValue = true)

    Box(modifier = Modifier.clickable {
        viewModel.setFullPlayerVisible(true)
    }) {
        val displayProgress = if (settings.isChapterProgressMode && metadata.chapters.isNotEmpty()) {
            // Mini player progress mirrors the full player chapter boundary calculation.
            val currentChapter = ChapterTimeline.currentChapter(metadata.chapters, playback.currentPosition)
            val posInChapter = ChapterTimeline.positionInChapter(metadata.chapters, currentChapter, playback.currentPosition, playback.duration)
            val chapterDuration = ChapterTimeline.duration(metadata.chapters, currentChapter, playback.duration)
            posInChapter.toFloat() / chapterDuration.toFloat()
        } else {
            playback.progress
        }

        CompactMediaPlayer(
            isPlaying = playback.isPlaying,
            title = metadata.title,
            author = metadata.author,
            narrator = metadata.narrator,
            coverPath = metadata.thumbnailPath ?: metadata.coverPath,
            // 详尽的中文注释：桥接封面最后更新时间戳，用以打破 Coil 等的缓存，确保发生重组后强制渲染最新文件
            coverLastUpdated = metadata.coverLastUpdated,
            // 传递计算后的进度
            progress = { displayProgress },
            isMediaAvailable = isMediaAvailable,
            actions = actions
        )
    }
}