package com.viel.aplayer.ui.detail

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.viel.aplayer.ui.player.PlayerViewModel

/**
 * 书籍详情悬浮层组件。
 * 将详情页的显示逻辑与动画从主 App容器中解耦。
 */
@Composable
fun DetailOverlay(
    detailViewModel: DetailViewModel,
    playerViewModel: PlayerViewModel,
    navController: NavController,
    canStartNavigation: () -> Boolean,
    modifier: Modifier = Modifier
) {
    val detailUiState by detailViewModel.uiState.collectAsStateWithLifecycle()
    // 详尽的中文注释：仅订阅 ViewModel 预计算好的进度百分比和当前书籍 ID，
    // 不再在 Composable 中内联 ceil/division 数学运算，遵循 UI 层纯渲染原则。
    val currentBookId by playerViewModel.currentBookId.collectAsStateWithLifecycle()
    val playbackPercent by playerViewModel.currentPlaybackProgressPercent.collectAsStateWithLifecycle()

    AnimatedVisibility(
        visible = detailUiState.isVisible,
        enter = slideInVertically(initialOffsetY = { it }, animationSpec = tween(400)) + fadeIn(),
        exit = slideOutVertically(targetOffsetY = { it }, animationSpec = tween(400)) + fadeOut(),
        modifier = modifier
    ) {
        DetailScreen(
            // 详尽的中文注释：当详情页展示的书籍与当前正在播放的书籍一致，且 ViewModel 已产出有效进度时，
            // 使用 ViewModel 预计算的实时百分比覆盖数据库中的静态进度值，实现实时同步。
            uiState = if (currentBookId == detailUiState.book?.book?.id && playbackPercent > 0) {
                detailUiState.copy(progressPercent = playbackPercent)
            } else {
                detailUiState
            },
            onBackClick = { detailViewModel.setVisible(false) },
            onSearchClick = { query ->
                detailViewModel.setVisible(false)
                if (canStartNavigation()) {
                    // 详尽中文注释：移除对当前路由是否为 search 的判断，允许在搜索结果中打开详情后再点关键词跳回/更新搜索
                    navController.navigate("search?q=${android.net.Uri.encode(query)}") {
                        launchSingleTop = true
                    }
                }
            },
            onPlayClick = {
                detailUiState.book?.let { bookWithProgress ->
                    playerViewModel.loadBook(bookWithProgress.book.id)
                }
                playerViewModel.setFullPlayerVisible(true)
            }
        )
    }
}