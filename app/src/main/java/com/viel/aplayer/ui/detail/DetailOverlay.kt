package com.viel.aplayer.ui.detail

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.viel.aplayer.data.store.GlassEffectMode
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.layerBackdrop


/**
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
    // 玻璃效果模式必须由 App 容器从设置状态显式传入，详情悬浮层不再声明默认值。
    glassEffectMode: GlassEffectMode,
    modifier: Modifier = Modifier,
    // 添加可选的 backdrop 参数，接收来自于 Activity 共享的 Backdrop 采样状态（主页采样源）。
    backdrop: LayerBackdrop? = null,
    // 添加专门用于采集详情页本身画面的专属 detailBackdrop 采样源。
    detailBackdrop: LayerBackdrop? = null,
    // 增加编辑书籍点击的回调，向上层 Activity 传递
    onEditClick: (String) -> Unit = {},
) {
    val detailUiState by detailViewModel.uiState.collectAsStateWithLifecycle()

    AnimatedVisibility(
        visible = detailUiState.isVisible,
        enter = slideInVertically(initialOffsetY = { it }, animationSpec = tween(400)) + fadeIn(),
        exit = slideOutVertically(targetOffsetY = { it }, animationSpec = tween(400)) + fadeOut(),
        modifier = modifier
    ) {
        // 
        // 恢复在此处外层 Box 对全局 detailBackdrop 的 layerBackdrop 挂载。
        // 这将允许全局采样源捕获包括文字、按钮在内的整个详情页的前景和背景全量画面。
        // 配合 APlayerApp 中的 450ms 延迟切换，能完美解决转场期间画面捕捉不全的问题。
        Box(
            modifier = Modifier
                .fillMaxSize()
                .then(
                    if (glassEffectMode == GlassEffectMode.MiuixBlur && detailBackdrop != null) {
                        Modifier.layerBackdrop(detailBackdrop)
                    } else {
                        Modifier
                    }
                )
        ) {
            DetailScreen(
                // M-19 修复 — UI渲染层完全回归 100% 无状态直译渲染原则。
                // 彻底移除了在 Composable 内部对 PlayerViewModel 的高频订阅 and 对 uiState.copy 的强行越权覆写，
                // 从而保证了 DetailViewModel 内部通过 stateFlow 发射的 3 秒锁定保护期数据能够无损、精准地直达 UI 层，
                // 绝不在 UI 渲染层造成任何逻辑冲突。
                uiState = detailUiState,
                onBackClick = { detailViewModel.setVisible(false) },
                // M-19 修复 — 点击播放时先通知 ViewModel 开启保护期，
                // 再触发真正的播放逻辑，Composable 层无需自持任何进度锁定状态。
                onPlayPressed = { detailViewModel.onPlayPressed() },
                onSearchClick = { query ->
                    detailViewModel.setVisible(false)
                    if (canStartNavigation()) {
                        // 
                        // 点击搜索关键字时，通知外部组件执行搜索跳转，并且关闭当前的详情悬浮层。
                        onNavigateToSearch(query)
                    }
                },
                onPlayClick = {
                    detailUiState.book?.let { bookWithProgress ->
                        // M-19 修复 — 遵循单向数据流与关注点分离原则，
                        // 点击播放时通过 lambda 往上传递书籍 ID，由宿主 App 容器中的 PlayerViewModel 统一处理，
                        // 从而完全清除了 DetailOverlay 内部对外部 ViewModel 的多余依赖。
                        onPlayBook(bookWithProgress.book.id)
                    }
                },
                // 详情页下拉菜单与其他浮层统一遵循 Material/miuix-blur 设置。
                glassEffectMode = glassEffectMode,
                // 将 Backdrop 进一步向下透传给 DetailScreen，用以渲染自身封面高斯背景。
                backdrop = backdrop,
                // 向 DetailScreen 传递包含前景文字与按钮的详情页整页全量采样源，以供子弹窗与下拉菜单高真模糊采样。
                fullPageBackdrop = detailBackdrop,
                // 向下透传编辑书籍元数据内存 lambda 回调
                onEditClick = onEditClick
            )
        }
    }
}
