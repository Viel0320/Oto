package com.viel.aplayer.ui.detail

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.viel.aplayer.data.store.GlassEffectMode
import top.yukonga.miuix.kmp.blur.LayerBackdrop

/**
 * 详情页 L2 容器层组件 DetailScreen。
 * 纯粹作为状态解包与事件桥接的控制器，内部无任何直接的视觉渲染逻辑。
 * 它从传入的 DetailUiState 中拆解出基本类型与原始对象，传递给 L3 层的纯渲染组件 DetailContent。
 * 这样达成了 Compose 官方三层架构分层规范（L1 Overlay -> L2 Screen -> L3 Layout/Content），
 * 并且解耦了界面渲染与领域状态逻辑。
 */
@Composable
fun DetailScreen(
    uiState: DetailUiState, // 输入的详情页 UI 状态模型
    onBackClick: () -> Unit, // 点击返回键或下滑退场触发的回调
    modifier: Modifier = Modifier,
    // M-19 修复 — 增加 onPlayPressed 参数，在点击播放前执行，将 3 秒播放保护期的状态下沉至 ViewModel 处理
    onPlayPressed: () -> Unit = {},
    onPlayClick: () -> Unit = {}, // 确认触发播放音频的回调
    onMoreClick: () -> Unit = {}, // 点击右上角更多控制
    onSearchClick: (String) -> Unit = {}, // 标签跳转搜索回调
    // 玻璃效果模式由外部传入
    glassEffectMode: GlassEffectMode,
    // 共用的 miuix-blur 背景采样源
    backdrop: LayerBackdrop? = null,
    // 全前景采样的模糊层采样源
    fullPageBackdrop: LayerBackdrop? = null,
    // 编辑元数据悬浮层拉起的回调
    onEditClick: (String) -> Unit = {},
) {
    val bookWithProgress = uiState.book
    val book = bookWithProgress?.book

    // 将解包后的扁平数据状态完全透传给纯渲染的 L3 无状态组件 DetailContent，保证展示与控制器解耦剥离。
    DetailContent(
        isVisible = uiState.isVisible,
        book = book,
        bookWithProgress = bookWithProgress,
        isAvailable = uiState.isAvailable,
        progressPercent = uiState.progressPercent,
        displayProgressPercent = uiState.displayProgressPercent,
        backgroundColorArgb = uiState.backgroundColorArgb,
        fullSourcePath = uiState.fullSourcePath,
        onBackClick = onBackClick,
        modifier = modifier,
        onPlayPressed = onPlayPressed,
        onPlayClick = onPlayClick,
        onMoreClick = onMoreClick,
        onSearchClick = onSearchClick,
        glassEffectMode = glassEffectMode,
        backdrop = backdrop,
        fullPageBackdrop = fullPageBackdrop,
        onEditClick = onEditClick
    )
}
