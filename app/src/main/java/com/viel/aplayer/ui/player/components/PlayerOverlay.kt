package com.viel.aplayer.ui.player.components

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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import com.viel.aplayer.data.store.GlassEffectMode
import com.viel.aplayer.ui.navigation.PlayerNavigationActions
import com.viel.aplayer.ui.player.MiniPlayerActions
import com.viel.aplayer.ui.player.PlayerScreen
import com.viel.aplayer.ui.player.PlayerActions
import com.viel.aplayer.ui.player.PlayerViewModel
// 为每一次改动添加详尽的中文注释：引入 LocalConfiguration 与 Configuration，用于在迷你播放器组件内实时识别屏幕状态
import androidx.compose.ui.platform.LocalConfiguration
import android.content.res.Configuration
// 为每一次改动添加详尽的中文注释：引入 LayerBackdrop 及相关模糊挂载工具类
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.blur.layerBackdrop

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
    // 为每一次改动添加详尽的中文注释：玻璃效果模式必须由 App 容器从设置状态显式传入，播放器悬浮层不再声明默认值。
    glassEffectMode: GlassEffectMode,
    modifier: Modifier = Modifier,
    // 为每一次改动添加详尽的中文注释：新增接收 backdrop 的参数，用于透传到迷你播放器
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

    // 为每一次改动添加详尽的中文注释：获取设备配置，以在播放器悬浮层最外层决策自适应对齐位置
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val isLargeScreen = configuration.screenWidthDp >= 600 || configuration.smallestScreenWidthDp >= 600
    val usePillPlayer = isLandscape || isLargeScreen

    // 为每一次改动添加详尽的中文注释：根据是否为药丸播放器自适应对齐。若为药丸播放器，依附在右下角 (Alignment.BottomEnd)；普通底栏播放器居中依附在底部 (Alignment.BottomCenter)
    val playerAlignment = if (usePillPlayer) Alignment.BottomEnd else Alignment.BottomCenter

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
            // 为每一次改动添加详尽的中文注释：根据设备屏幕状态，横屏/大屏下对齐右下角以达成精致非对称排版，竖屏手机对齐底部中心
            modifier = Modifier.align(playerAlignment)
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

/**
 * 迷你播放器内容容器。
 * 专门用于收集高频更新的状态（PlaybackState），从而将重组范围控制在最小范围内。
 * 详尽的中文注释：章节进度 / 全局进度的选择计算已移至 PlayerViewModel.miniPlayerProgress，
 * 此处只做纯渲染订阅，不再直接调用 ChapterTimeline 等 media 层工具类。
 */
@Composable
private fun MiniPlayerContent(
    viewModel: PlayerViewModel,
    actions: MiniPlayerActions,
    // 详尽的中文注释：接收透传的 LayerBackdrop 实例
    backdrop: LayerBackdrop?,
    // 详尽的中文注释：接收透传的 GlassEffectMode 模式
    glassEffectMode: GlassEffectMode
) {
    // 详尽的中文注释：在此处收集 high-frequency 更新的状态（PlaybackState），从而将重组范围控制在最小范围内。
    val playback by viewModel.playbackState.collectAsStateWithLifecycle()
    val metadata by viewModel.metadataState.collectAsStateWithLifecycle()
    // 详尽的中文注释：订阅 ViewModel 预计算好的迷你播放器进度，
    // 已根据章节模式 / 全局模式自动切换，无需 UI 层再做 ChapterTimeline 计算。
    val displayProgress by viewModel.miniPlayerProgress.collectAsStateWithLifecycle()
    val isMediaAvailable by remember(viewModel, metadata.id) {
        // Compact player checks the current book availability only while it is mounted.
        viewModel.currentBookAvailability(metadata.id)
    }.collectAsStateWithLifecycle(initialValue = true)

    // 为每一次改动添加详尽的中文注释：获取设备配置，用于动态识别屏幕方向和分辨率大小
    val configuration = LocalConfiguration.current
    // 为每一次改动添加详尽的中文注释：判断是否处于横屏方向
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    // 为每一次改动添加详尽的中文注释：判断屏幕宽度或最小宽度是否达到 600dp（平板、折叠屏大屏设备的通用阈值）
    val isLargeScreen = configuration.screenWidthDp >= 600 || configuration.smallestScreenWidthDp >= 600
    // 为每一次改动添加详尽的中文注释：若处于横屏或大屏模式，则决策切换到药丸悬浮样式的迷你播放器以优化整体的视觉宽高比例
    val usePillPlayer = isLandscape || isLargeScreen

    // 为每一次改动添加详尽的中文注释：
    // 移除原本包裹在外层的 Box 的 Modifier.clickable { ... }。
    // 因为这会把药丸卡片的外边距（padding）与底部避让区（navigationBarsPadding）也计入点击区，且导致水波纹呈矩形溢出。
    // 我们改由各自的内部 Surface 通过 onClick 回调独立处理点击事件，从而让点击区域与水波纹能够完美贴合圆角形状。
    Box {
        if (usePillPlayer) {
            // 为每一次改动添加详尽的中文注释：在横屏和大屏模式下展示药丸悬浮样式的 PillCompactMediaPlayer 组件以提升 premium 质感
            PillCompactMediaPlayer(
                isPlaying = playback.isPlaying,
                coverPath = metadata.thumbnailPath,
                // 详尽的中文注释：桥接封面最后更新时间戳，用以打破 Coil 等的缓存，确保发生重组后强制渲染最新文件
                coverLastUpdated = metadata.coverLastUpdated,
                // 详尽的中文注释：传递 ViewModel 预计算的进度值，UI 层设计中不再包含业务逻辑计算
                isMediaAvailable = isMediaAvailable,
                actions = actions,
                // 详尽的中文注释：透传磨砂玻璃背景采样状态，以便在 miuix-blur 模糊玻璃模式下折射背景色彩
                backdrop = backdrop,
                // 为每一次改动添加详尽的中文注释：向药丸播放器组件传入点击回调，令其内部 Surface 触发精确圆角的点击波纹
                onClick = { viewModel.setFullPlayerVisible(true) },
                glassEffectMode = glassEffectMode
            )
        } else {
            // 中文注释：已在此处取消了迷你播放器进度条的封面颜色（metadata.backgroundColorArgb）绑定，不再向 CompactMediaPlayer 传入自定义 color 属性
            // 详尽的中文注释：向迷你播放器透传 backdrop 和 glassEffectMode 以实现背景的高斯模糊效果
            CompactMediaPlayer(
                isPlaying = playback.isPlaying,
                title = metadata.title,
                author = metadata.author,
                narrator = metadata.narrator,
                coverPath = metadata.thumbnailPath,
                // 详尽的中文注释：桥接封面最后更新时间戳，用以打破 Coil 等的缓存，确保发生重组后强制渲染最新文件
                coverLastUpdated = metadata.coverLastUpdated,
                // 详尽的中文注释：传递 ViewModel 预计算的进度值，UI 层不再包含任何 business 计算
                progress = { displayProgress },
                isMediaAvailable = isMediaAvailable,
                actions = actions,
                // 详尽的中文注释：透传磨砂背景参数
                backdrop = backdrop,
                // 为每一次改动添加详尽的中文注释：传入点击回调，令 CompactMediaPlayer 内部 Surface 自主管理点击事件以呈现正常水波纹
                onClick = { viewModel.setFullPlayerVisible(true) },
                glassEffectMode = glassEffectMode
            )
        }
    }
}
