package com.viel.aplayer.ui.player.components

import android.content.res.Configuration
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
// 为每一次改动添加详尽的中文注释：导入 fillMaxSize 以支持全屏 Box 容器的排版，修复编译报错，保证迷你播放器悬浮位置的精确控制
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.viel.aplayer.data.store.GlassEffectMode
import com.viel.aplayer.ui.player.MiniPlayerActions
import com.viel.aplayer.ui.player.PlayerViewModel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import top.yukonga.miuix.kmp.blur.LayerBackdrop

/**
 * 迷你播放器专属的独立子窗口悬浮层组件 (MiniPlayerOverlay)。
 * 
 * 为每一次改动添加详尽的中文注释：
 * 1. 物理窗口剥离设计：将迷你播放器渲染在系统独立的 Popup 窗口中，彻底脱离主页面渲染树。
 * 2. 100% 安全采样前景：由于窗口与主页面物理剥离，迷你播放器在启用毛玻璃材质时，可以毫无闪退（Vulkan Feedback Loop）隐患地安全采样包含了列表文字、多彩卡片的 appBackdrop / detailBackdrop 视口。
 * 3. 沉浸式边缘延伸：在 Popup 属性上开启 clippingEnabled = false，使毛玻璃背景能完整填充、延伸至系统导航栏下方，实现全面透光沉浸。
 * 4. 极致顺滑的转场动画：内部嵌套 AnimatedVisibility 以获得极致顺滑的飞入飞出物理退场入场动效。
 */
@Composable
fun MiniPlayerOverlay(
    playerViewModel: PlayerViewModel,
    miniPlayerActions: MiniPlayerActions,
    currentRoute: String?,
    glassEffectMode: GlassEffectMode,
    modifier: Modifier = Modifier,
    backdrop: LayerBackdrop? = null
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

    // 为每一次改动添加详尽的中文注释：获取设备配置，决策自适应对齐位置
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val isLargeScreen = configuration.screenWidthDp >= 600 || configuration.smallestScreenWidthDp >= 600
    val usePillPlayer = isLandscape || isLargeScreen

    // 为每一次改动添加详尽的中文注释：药丸播放器悬浮在右下角，条状播放器贴在底部中央
    val playerAlignment = if (usePillPlayer) Alignment.BottomEnd else Alignment.BottomCenter

    // 迷你播放器 Popup 浮层挂载逻辑判断
    val isPopupNeeded = hasActiveTrack &&
            currentRoute != null &&
            !currentRoute.startsWith("search")

    if (isPopupNeeded) {
        // 为每一次改动添加详尽的中文注释：
        // 彻底移除独立的 Popup 物理窗口包装，改用主 Window 内部的 Box 全屏容器对齐绘制。
        // 这解决了跨 Android Window 导致的 LayerBackdrop 模糊采样源像素隔离失效问题，使磨砂玻璃背景能够真实完美地透光并高斯折射底层页面。
        // 同时，由于其在 APlayerApp 的 root Box 中以同级兄弟节点形式挂载在 appBackdrop/detailBackdrop 采样源外部，
        // 从而完美杜绝了“子采样父”的图形渲染树死锁闪退（Vulkan Feedback Loop）隐患。
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = playerAlignment
        ) {
            // 在主窗口内部放置 AnimatedVisibility 转场，精准捕捉迷你播放器在全屏显示切换或隐藏时的入场/退场滑入滑出极致动效
            AnimatedVisibility(
                visible = !isFullPlayerVisible && !isMiniPlayerHidden,
                enter = slideInVertically(
                    initialOffsetY = { it },
                    animationSpec = tween(400)
                ) + fadeIn(animationSpec = tween(400)),
                exit = slideOutVertically(
                    targetOffsetY = { it },
                    animationSpec = tween(400)
                ) + fadeOut(animationSpec = tween(400))
            ) {
                // 重要：将高频状态（进度、播放状态）隔离在此组件内部
                // 详尽的中文注释：向下游透传模糊背景状态与玻璃模式状态，隔离刷新源
                MiniPlayerContent(
                    viewModel = playerViewModel,
                    actions = miniPlayerActions,
                    backdrop = backdrop,
                    glassEffectMode = glassEffectMode
                )
            }
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
    actions: MiniPlayerActions,
    backdrop: LayerBackdrop?,
    glassEffectMode: GlassEffectMode
) {
    val playback by viewModel.playbackState.collectAsStateWithLifecycle()
    val metadata by viewModel.metadataState.collectAsStateWithLifecycle()
    val displayProgress by viewModel.miniPlayerProgress.collectAsStateWithLifecycle()
    val isMediaAvailable by remember(viewModel, metadata.id) {
        viewModel.currentBookAvailability(metadata.id)
    }.collectAsStateWithLifecycle(initialValue = true)

    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val isLargeScreen = configuration.screenWidthDp >= 600 || configuration.smallestScreenWidthDp >= 600
    val usePillPlayer = isLandscape || isLargeScreen

    Box {
        if (usePillPlayer) {
            PillCompactMediaPlayer(
                isPlaying = playback.isPlaying,
                coverPath = metadata.thumbnailPath,
                coverLastUpdated = metadata.coverLastUpdated,
                isMediaAvailable = isMediaAvailable,
                actions = actions,
                backdrop = backdrop,
                onClick = { viewModel.setFullPlayerVisible(true) },
                glassEffectMode = glassEffectMode
            )
        } else {
            CompactMediaPlayer(
                isPlaying = playback.isPlaying,
                title = metadata.title,
                author = metadata.author,
                narrator = metadata.narrator,
                coverPath = metadata.thumbnailPath,
                coverLastUpdated = metadata.coverLastUpdated,
                progress = { displayProgress },
                isMediaAvailable = isMediaAvailable,
                actions = actions,
                backdrop = backdrop,
                onClick = { viewModel.setFullPlayerVisible(true) },
                glassEffectMode = glassEffectMode
            )
        }
    }
}
