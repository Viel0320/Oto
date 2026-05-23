package com.viel.aplayer.ui.player.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import com.viel.aplayer.data.store.GlassEffectMode
import com.viel.aplayer.ui.navigation.PlayerNavigationActions
import com.viel.aplayer.ui.player.PlayerScreen
import com.viel.aplayer.ui.player.PlayerActions
import com.viel.aplayer.ui.player.PlayerViewModel
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.blur.layerBackdrop

/**
 * 播放器悬浮层组件 (PlayerOverlay)。
 * 
 * 为每一次改动添加详尽的中文注释：
 * 本组件已彻底重构剥离了迷你播放器相关的 Popup 弹窗及高频隔离容器，
 * 仅承担纯粹、职责单一的全屏播放器 (Full Player) 的隐显渲染与 Z-index 包裹协调。
 */
@Composable
fun PlayerOverlay(
    playerViewModel: PlayerViewModel,
    playerActions: PlayerActions,
    playerNavigationActions: PlayerNavigationActions,
    // 为每一次改动添加详尽的中文注释：玻璃效果模式必须由 App 容器从设置状态显式传入。
    glassEffectMode: GlassEffectMode,
    modifier: Modifier = Modifier
) {
    // 仅监听播放器可见性（低频信号）
    val isFullPlayerVisible by remember(playerViewModel) {
        playerViewModel.settingsState.map { it.isFullPlayerVisible }.distinctUntilChanged()
    }.collectAsStateWithLifecycle(initialValue = false)

    Box(modifier = modifier.fillMaxSize()) {
        // 全屏播放器层
        // 为每一次改动添加详尽的中文注释：
        // 实例化全屏播放器自身专属的 playerBackdrop 采样源，
        // 挂载在最外层包裹的 Box 上以实时采集整个播放器的画面数据（包含前景文字与全部控制按钮），
        // 并穿透给 PlayerScreen 以让内部的章节列表抽屉组件（ChapterListSheet）实现真正清透的模糊效果。
        val playerBackdrop = rememberLayerBackdrop()
        AnimatedVisibility(
            visible = isFullPlayerVisible,
            enter = slideInVertically(initialOffsetY = { it }, animationSpec = tween(400)),
            exit = slideOutVertically(targetOffsetY = { it }, animationSpec = tween(400))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .then(
                        if (glassEffectMode == GlassEffectMode.MiuixBlur) {
                            Modifier.layerBackdrop(playerBackdrop)
                        } else {
                            Modifier
                        }
                    )
            ) {
                PlayerScreen(
                    viewModel = playerViewModel,
                    actions = playerActions,
                    navigationActions = playerNavigationActions,
                    // 为每一次改动添加详尽的中文注释：全屏播放器内部负责创建章节列表的 miuix-blur 模糊视效，因此这里仅透传模式。
                    glassEffectMode = glassEffectMode,
                    // 为每一次改动添加详尽的中文注释：传递播放器自身的整页全量画面采样源，实现无穿帮高质感磨砂模糊。
                    fullPageBackdrop = playerBackdrop
                )
            }
        }
    }
}
