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
import com.viel.aplayer.data.store.GlassEffectMode
import com.viel.aplayer.ui.navigation.PlayerNavigationActions
import com.viel.aplayer.ui.player.MiniPlayerActions
import com.viel.aplayer.ui.player.NewPlayerScreen
import com.viel.aplayer.ui.player.PlayerActions
import com.viel.aplayer.ui.player.PlayerViewModel
import com.viel.aplayer.ui.player.components.CompactMediaPlayer
// 为每一次改动添加详尽的中文注释：引入 HazeState 类型
import dev.chrisbanes.haze.HazeState

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
    // 为每一次改动添加详尽的中文注释：新增接收 appHazeState 的参数，用于透传到迷你播放器
    hazeState: HazeState? = null
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
                navigationActions = playerNavigationActions,
                // 为每一次改动添加详尽的中文注释：全屏播放器内部负责创建章节列表的 Haze source/effect，因此这里仅透传模式。
                glassEffectMode = glassEffectMode
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
            // 详尽的中文注释：向下游透传模糊背景状态与玻璃模式状态，隔离刷新源
            MiniPlayerContent(
                viewModel = playerViewModel,
                actions = miniPlayerActions,
                hazeState = hazeState,
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
    // 详尽的中文注释：接收透传的 HazeState 实例
    hazeState: HazeState?,
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

    Box(modifier = Modifier.clickable {
        viewModel.setFullPlayerVisible(true)
    }) {
        // 中文注释：已在此处取消了迷你播放器进度条的封面颜色（metadata.backgroundColorArgb）绑定，不再向 CompactMediaPlayer 传入自定义 color 属性
        // 详尽的中文注释：向迷你播放器透传 hazeState 和 glassEffectMode 以实现背景的高斯模糊效果
        CompactMediaPlayer(
            isPlaying = playback.isPlaying,
            title = metadata.title,
            author = metadata.author,
            narrator = metadata.narrator,
            coverPath = metadata.thumbnailPath,
            // 详尽的中文注释：桥接封面最后更新时间戳，用以打破 Coil 等的缓存，确保发生重组后强制渲染最新文件
            coverLastUpdated = metadata.coverLastUpdated,
            // 详尽的中文注释：传递 ViewModel 预计算的进度值，UI 层不再包含任何业务计算
            progress = { displayProgress },
            isMediaAvailable = isMediaAvailable,
            actions = actions,
            // 详尽的中文注释：透传磨砂背景参数
            hazeState = hazeState,
            glassEffectMode = glassEffectMode
        )
    }
}
