package com.viel.aplayer.ui.player.layouts

// 导入 Jetpack Compose 动画、手势和布局 API
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.viel.aplayer.data.store.GlassEffectMode
import com.viel.aplayer.ui.bookmarks.BookmarkListViewStateful
import com.viel.aplayer.ui.common.BottomNavTabs
import com.viel.aplayer.ui.common.PlayerCover
import com.viel.aplayer.ui.player.BookMetadataState
import com.viel.aplayer.ui.player.PlayerActions
import com.viel.aplayer.ui.player.PlayerScreenMode
import com.viel.aplayer.ui.player.PlayerUiState
import com.viel.aplayer.ui.player.PlayerViewModel
import com.viel.aplayer.ui.player.components.PlayerControlPanel
import com.viel.aplayer.ui.player.components.PlayerVerticalAppBar
import com.viel.aplayer.ui.player.components.RelatedBooksView
import com.viel.aplayer.ui.player.components.SubtitlesViewStateful
import top.yukonga.miuix.kmp.blur.LayerBackdrop

/**
 * 竖屏自适应播放器布局组件 (PlayerPortrait)。
 * 完美移植原 PlayerScreen 中的竖屏单列 Column 组件编排，将复杂的具体渲染分流至本模块，保证视觉的完美还原。
 */
@Composable
fun PlayerPortrait(
    viewModel: PlayerViewModel,
    metadata: BookMetadataState,
    controls: PlayerViewModel.PlaybackControlState,
    settings: com.viel.aplayer.ui.settings.PlayerSettingsState,
    actions: PlayerActions,
    fullUiState: PlayerUiState,
    currentMode: PlayerScreenMode,
    onModeChange: (PlayerScreenMode) -> Unit,
    animatedBgColor: androidx.compose.ui.graphics.Color,
    glassEffectMode: GlassEffectMode,
    chapterSheetBackdrop: LayerBackdrop,
    // 显式约束动画参数的泛型类型为 AnimationVector1D，修复参数类型擦除引起的匹配失败 (H-11)
    offsetY: Animatable<Float, AnimationVector1D>,
    scope: kotlinx.coroutines.CoroutineScope,
    dismissThreshold: Float,
    // 修正 FocusManager 为正确的 focus 包级类型，去除 platform 包残留引起未解析符号
    focusManager: FocusManager,
    navigationActions: com.viel.aplayer.ui.navigation.PlayerNavigationActions,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current

    Column(
        modifier = modifier.fillMaxSize()
    ) {
        // 使用新抽离的 PlayerVerticalAppBar 组件，封装顶部标题栏、下拉返回手势以及睡眠定时等物理交互
        PlayerVerticalAppBar(
            metadata = metadata,
            settings = settings,
            actions = actions,
            navigationActions = navigationActions,
            focusManager = focusManager,
            glassEffectMode = glassEffectMode,
            backdrop = chapterSheetBackdrop,
            offsetY = offsetY,
            scope = scope,
            dismissThreshold = dismissThreshold
        )

        // 计算水平滑动手势换页的阈值，默认为 80.dp 对应的像素值
        val swipeThresholdPx = with(density) { 80.dp.toPx() }
        val tabModes = remember {
            listOf(PlayerScreenMode.BOOKMARKS, PlayerScreenMode.SUBTITLES, PlayerScreenMode.RELATED)
        }
        
        // 根据当前激活的 Tab 映射到最外层的共享动画过渡外壳
        val contentShell = remember(currentMode) {
            when (currentMode) {
                PlayerScreenMode.BOOKMARKS -> PlayerContentShell.Bookmarks
                PlayerScreenMode.RELATED -> PlayerContentShell.Related
                PlayerScreenMode.PLAYER,
                PlayerScreenMode.SUBTITLES -> PlayerContentShell.PlaybackShell
            }
        }

        Box(
            modifier = Modifier
                .weight(1f)
                .pointerInput(currentMode) {
                    // 仅在非主播放页面（如书签、歌词、推荐面板）才拦截水平拖拽手势用于页签快速流转
                    if (currentMode == PlayerScreenMode.PLAYER) return@pointerInput
                    var accumulatedX = 0f
                    var hasSwipeTriggered = false
                    detectHorizontalDragGestures(
                        onDragStart = {
                            accumulatedX = 0f
                            hasSwipeTriggered = false
                        },
                        onDragEnd = {
                            accumulatedX = 0f
                            hasSwipeTriggered = false
                        },
                        onDragCancel = {
                            accumulatedX = 0f
                            hasSwipeTriggered = false
                        },
                        onHorizontalDrag = { change, dragAmount ->
                            if (!hasSwipeTriggered) {
                                accumulatedX += dragAmount
                                if (kotlin.math.abs(accumulatedX) > swipeThresholdPx) {
                                    val currentIndex = tabModes.indexOf(currentMode)
                                    val nextMode = if (accumulatedX < 0) {
                                        if (currentIndex < tabModes.lastIndex) tabModes[currentIndex + 1]
                                        else PlayerScreenMode.PLAYER
                                    } else {
                                        if (currentIndex > 0) tabModes[currentIndex - 1]
                                        else PlayerScreenMode.PLAYER
                                    }
                                    onModeChange(nextMode)
                                    hasSwipeTriggered = true
                                }
                            }
                            change.consume()
                        }
                    )
                }
        ) {
            // 外层横向滑入滑出切换动画，管理主控制/书签/推荐大面板的顺畅流转
            AnimatedContent(
                targetState = contentShell,
                modifier = Modifier.fillMaxSize(),
                transitionSpec = {
                    if (targetState.index > initialState.index) {
                        (slideInHorizontally(animationSpec = tween(300)) { it } + fadeIn(animationSpec = tween(300)))
                            .togetherWith(slideOutHorizontally(animationSpec = tween(300)) { -it } + fadeOut(animationSpec = tween(300)))
                    } else {
                        (slideInHorizontally(animationSpec = tween(300)) { -it } + fadeIn(animationSpec = tween(300)))
                            .togetherWith(slideOutHorizontally(animationSpec = tween(300)) { it } + fadeOut(animationSpec = tween(300)))
                    }.using(SizeTransform(clip = false))
                },
                label = "player_mode_transition"
            ) { shell ->
                Column(modifier = Modifier.fillMaxSize()) {
                    when (shell) {
                        PlayerContentShell.PlaybackShell -> {
                            val playbackTopMode = if (currentMode == PlayerScreenMode.SUBTITLES) {
                                PlayerScreenMode.SUBTITLES
                            } else {
                                PlayerScreenMode.PLAYER
                            }
                            // 内层淡入淡出动画，顺畅切换封面层与歌词字幕渲染面板
                            AnimatedContent(
                                targetState = playbackTopMode,
                                modifier = Modifier.weight(1f),
                                transitionSpec = {
                                    (fadeIn(animationSpec = tween(300)) togetherWith fadeOut(animationSpec = tween(300)))
                                        .using(SizeTransform(clip = false))
                                },
                                label = "player_playback_top_transition"
                            ) { topMode ->
                                when (topMode) {
                                    PlayerScreenMode.SUBTITLES -> {
                                        Box(modifier = Modifier.fillMaxSize()) {
                                            SubtitlesViewStateful(
                                                viewModel = viewModel,
                                                metadata = metadata,
                                                actions = actions
                                            )
                                        }
                                    }
                                    else -> {
                                        // 使用封装良好的手势声音及双击切歌封面组件
                                        PlayerCover(
                                            coverPath = metadata.coverPath,
                                            isPlaying = controls.isPlaying,
                                            coverLastUpdated = metadata.coverLastUpdated,
                                            onAdjustVolume = { actions.playback.onAdjustVolume(it) },
                                            onNextChapter = { actions.playback.onNextChapter() },
                                            onPreviousChapter = { actions.playback.onPreviousChapter() }
                                        )
                                    }
                                }
                            }
                            // 控制面板区，包含播放按钮、进度滑条、章节切换、速度调节，并完美避让 24.dp 舒适内缩边距
                            PlayerControlPanel(
                                viewModel = viewModel,
                                metadata = metadata,
                                controls = controls,
                                settings = settings,
                                actions = actions,
                                buttonColor = animatedBgColor,
                                glassEffectMode = glassEffectMode,
                                backdrop = chapterSheetBackdrop,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 24.dp)
                            )
                        }
                        PlayerContentShell.Bookmarks -> {
                            // 书签记录面板，可长按跳转或重命名书签点位
                            Box(modifier = Modifier.weight(1f)) {
                                BookmarkListViewStateful(
                                    viewModel = viewModel,
                                    metadata = metadata,
                                    actions = actions
                                )
                            }
                        }
                        PlayerContentShell.Related -> {
                            // 推荐书籍面板，展示作者及朗读者关联的书籍列表，无缝拉起加载
                            Box(modifier = Modifier.weight(1f)) {
                                RelatedBooksView(
                                    currentBookId = metadata.id,
                                    heuristicBooks = fullUiState.heuristicRecommendedBooks,
                                    authorSections = fullUiState.relatedAuthorSections,
                                    narratorSections = fullUiState.relatedNarratorSections,
                                    recentBooks = fullUiState.recentlyAddedBooks,
                                    onBookClick = actions.content.onLoadRelatedBook
                                )
                            }
                        }
                    }
                }
            }
        }

        // 底部页签导航栏，负责播放器四大基本维度的状态快速流转
        BottomNavTabs(
            selectedTab = currentMode,
            onTabSelected = {
                val nextMode = if (currentMode == it) PlayerScreenMode.PLAYER else it
                onModeChange(nextMode)
            }
        )
    }
}
