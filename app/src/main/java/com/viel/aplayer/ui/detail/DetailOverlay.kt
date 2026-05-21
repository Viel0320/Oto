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
/**
 * 为每一次改动添加详尽的中文注释：
 * 书籍详情悬浮层组件。
 * 将详情页的显示逻辑与动画从主 App容器中解耦。
 *
 * 移除了对 NavController 的直接依赖，将点击搜索时导航到搜索页的行为解耦为 onNavigateToSearch 回调，
 * 使得 DetailOverlay 能够通过 Activity 级别拉起独立 SearchActivity，确保数据通信流一致。
 */
@Composable
fun DetailOverlay(
    detailViewModel: DetailViewModel,
    canStartNavigation: () -> Boolean,
    onPlayBook: (String) -> Unit,
    onNavigateToSearch: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val detailUiState by detailViewModel.uiState.collectAsStateWithLifecycle()

    AnimatedVisibility(
        visible = detailUiState.isVisible,
        enter = slideInVertically(initialOffsetY = { it }, animationSpec = tween(400)) + fadeIn(),
        exit = slideOutVertically(targetOffsetY = { it }, animationSpec = tween(400)) + fadeOut(),
        modifier = modifier
    ) {
        DetailScreen(
            // 详尽的中文注释：M-19 修复 — UI渲染层完全回归 100% 无状态直译渲染原则。
            // 彻底移除了在 Composable 内部对 PlayerViewModel 的高频订阅和对 uiState.copy 的强行越权覆写，
            // 从而保证了 DetailViewModel 内部通过 stateFlow 发射的 3 秒锁定保护期数据能够无损、精准地直达 UI 层，
            // 绝不在 UI 渲染层造成任何逻辑冲突。
            uiState = detailUiState,
            onBackClick = { detailViewModel.setVisible(false) },
            // 详尽中文注释：M-19 修复 — 点击播放时先通知 ViewModel 开启保护期，
            // 再触发真正的播放逻辑，Composable 层无需自持任何进度锁定状态。
            onPlayPressed = { detailViewModel.onPlayPressed() },
            onSearchClick = { query ->
                detailViewModel.setVisible(false)
                if (canStartNavigation()) {
                    // 为每一次改动添加详尽的中文注释：
                    // 点击搜索关键字时，通知外部组件执行搜索跳转，并且关闭当前的详情悬浮层。
                    onNavigateToSearch(query)
                }
            },
            onPlayClick = {
                detailUiState.book?.let { bookWithProgress ->
                    // 详尽中文注释：M-19 修复 — 遵循单向数据流与关注点分离原则，
                    // 点击播放时通过 lambda 往上传递书籍 ID，由宿主 App 容器中的 PlayerViewModel 统一处理，
                    // 从而完全清除了 DetailOverlay 内部对外部 ViewModel 的多余依赖。
                    onPlayBook(bookWithProgress.book.id)
                }
            }
        )
    }
}