package com.viel.aplayer.ui.player

import android.view.RoundedCorner
import android.content.res.Configuration
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.animation.AnimatedContent
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MoreVert
import com.viel.aplayer.ui.common.BlurDropdownMenu
import com.viel.aplayer.ui.player.components.PlaybackControls
import dev.chrisbanes.haze.hazeEffect
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.asPaddingValues
// 为每一次改动添加详尽的中文注释：导入用于计算 PaddingValues 水平安全边距的扩展函数，解决横屏下防裁切的安全边距叠加计算编译依赖
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.viel.aplayer.data.store.GlassEffectMode
import com.viel.aplayer.ui.bookmarks.BookmarkDialog
import com.viel.aplayer.ui.bookmarks.BookmarkListView
import com.viel.aplayer.ui.common.BottomNavTabs
import com.viel.aplayer.ui.common.BlurSnackbar
import com.viel.aplayer.ui.navigation.PlayerNavigationActions
import com.viel.aplayer.ui.player.components.ChapterDisplay
import com.viel.aplayer.ui.player.components.ChapterListSheet
import com.viel.aplayer.ui.player.components.PlayerCover
import com.viel.aplayer.ui.player.components.PlaybackProgress
import com.viel.aplayer.ui.player.components.PlayerAppBar
import com.viel.aplayer.ui.player.components.PlayerControlPanel
import com.viel.aplayer.ui.player.components.RelatedBooksView
import com.viel.aplayer.ui.player.components.SubtitlesView
import com.viel.aplayer.ui.settings.PlayerSettingsState
import com.viel.aplayer.ui.theme.APlayerTheme
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import coil.compose.AsyncImage
import java.io.File
import kotlinx.coroutines.launch
import kotlin.math.roundToInt


enum class PlayerScreenMode(val index: Int) {
    PLAYER(-1),
    BOOKMARKS(0),
    SUBTITLES(1),
    RELATED(2)
}

// 为每一次改动添加详尽的中文注释：外层 AnimatedContent 的动画目标壳；PLAYER 与 SUBTITLES 共用 PlaybackShell，避免控制面板在二者之间切换时被卸载重挂。
private enum class PlayerContentShell(val index: Int) {
    Bookmarks(0),
    PlaybackShell(1),
    Related(2)
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun NewPlayerScreen(
    viewModel: PlayerViewModel,
    actions: PlayerActions,
    navigationActions: PlayerNavigationActions,
    // 为每一次改动添加详尽的中文注释：玻璃效果模式必须由播放器 Overlay 从设置状态显式传入，播放页内部不再声明 Material 默认值。
    glassEffectMode: GlassEffectMode,
    modifier: Modifier = Modifier,
) {
    val isPreview = androidx.compose.ui.platform.LocalInspectionMode.current

    // 为每一次改动添加详尽的中文注释：如果处于 IDE 预览环境，则注入精美的 Mock 数据，避免底层的 Flow 订阅和 ViewModel 的初始化依赖
    val metadata = if (isPreview) {
        BookMetadataState(
            id = "book_1",
            title = "三体：黑暗森林",
            author = "刘慈欣",
            narrator = "王明",
            coverPath = null,
            thumbnailPath = null,
            coverLastUpdated = 0L,
            backgroundColorArgb = android.graphics.Color.parseColor("#FF1E293B"), // 深色灰蓝色背景
            chapters = listOf(
                com.viel.aplayer.data.entity.ChapterEntity("ch_1", "book_1", "file_1", 1, "引子", 0L, 180000L, 0L, "EMBEDDED"),
                com.viel.aplayer.data.entity.ChapterEntity("ch_2", "book_1", "file_1", 2, "第一章：危机纪元", 180000L, 360000L, 180000L, "EMBEDDED")
            )
        )
    } else {
        viewModel.metadataState.collectAsStateWithLifecycle().value
    }

    val settings = if (isPreview) {
        com.viel.aplayer.ui.settings.PlayerSettingsState(
            isFullPlayerVisible = true,
            selectedContentTab = -1, // PLAYER 模式
            isChapterProgressMode = false,
            showUndoSeek = false,
            selectedSleepTimer = 0
        )
    } else {
        viewModel.settingsState.collectAsStateWithLifecycle().value
    }

    val controls = if (isPreview) {
        PlayerViewModel.PlaybackControlState(
            isPlaying = true,
            playbackSpeed = 1.0f,
            isSpeedManualMode = false
        )
    } else {
        viewModel.playbackControlState.collectAsStateWithLifecycle().value
    }
    
    val fullUiState = if (isPreview) {
        PlayerUiState()
    } else {
        viewModel.uiState.collectAsStateWithLifecycle().value
    }

    val targetMode = remember(settings.selectedContentTab) {
        when(settings.selectedContentTab) {
            0 -> PlayerScreenMode.BOOKMARKS
            1 -> PlayerScreenMode.SUBTITLES
            2 -> PlayerScreenMode.RELATED
            else -> PlayerScreenMode.PLAYER
        }
    }

    var currentMode by remember { mutableStateOf(targetMode) }

    // 详尽中文注释：定义全屏播放器预测性返回手势的激活状态和手势百分比进度值（0f 到 1f 之间）
    var isPlayerBackActive by remember { mutableStateOf(false) }
    var playerBackProgress by remember { mutableFloatStateOf(0f) }

    val scope = rememberCoroutineScope()
    val offsetY = remember { Animatable(0f) }
    val density = LocalDensity.current
    val dismissThreshold = with(density) { 50.dp.toPx() }

    val view = LocalView.current
    val systemCornerRadius = remember(view) {
        val insets = view.rootWindowInsets
        val corner = insets?.getRoundedCorner(RoundedCorner.POSITION_TOP_LEFT)
        corner?.radius ?: 0
    }
    val cornerRadiusDp = with(density) { systemCornerRadius.toDp().coerceAtLeast(24.dp) }

    // 为每一次改动添加详尽的中文注释：使用 LocalConfiguration 检测屏幕方向，动态分流横竖屏自适应布局与圆角设计
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    LaunchedEffect(targetMode) {
        currentMode = targetMode
    }

    // 详尽中文注释：移除原先硬编码的 darkTheme = true，使全屏播放器跟随系统/应用主题设置
    APlayerTheme {
        // 为每一次改动添加详尽的中文注释：播放器封面背景同样使用 Coil，本地 context 用于构建带缓存戳的 ImageRequest。
        val context = LocalContext.current
        val focusManager = LocalFocusManager.current
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
        // 为每一次改动添加详尽的中文注释：为章节列表 BottomSheet 创建 HazeState；播放器 Surface 作为 source，章节面板作为 effect。
        val chapterSheetHazeState = rememberHazeState()
        // 为每一次改动添加详尽的中文注释：只有 Haze 模式才把播放器完整界面注册为采样源；Material 模式不启用 Haze 渲染管线。
        val chapterSheetHazeSourceModifier = if (glassEffectMode == GlassEffectMode.Haze) {
            Modifier.hazeSource(state = chapterSheetHazeState)
        } else {
            Modifier
        }

        // 详尽的中文注释：当处于书签/歌词/推荐等面板时，使用 PredictiveBackHandler 平滑返回主播放页面
        androidx.activity.compose.PredictiveBackHandler(enabled = currentMode != PlayerScreenMode.PLAYER) { progressFlow ->
            try {
                progressFlow.collect { }
                currentMode = PlayerScreenMode.PLAYER
            } catch (_: kotlin.coroutines.cancellation.CancellationException) {
                // 详尽的中文注释：用户中途滑回取消，不做状态改变
            }
        }

        // 详尽的中文注释：当处于主播放页面且全屏播放器可见时，使用 PredictiveBackHandler 拦截并支持手势平滑最小化
        androidx.activity.compose.PredictiveBackHandler(
            enabled = currentMode == PlayerScreenMode.PLAYER && settings.isFullPlayerVisible
        ) { progressFlow ->
            try {
                // 收集预测性返回拖拽进度流，动态调节播放器向下滑动的过渡程度
                progressFlow.collect { backEvent ->
                    isPlayerBackActive = true
                    playerBackProgress = backEvent.progress
                }
                actions.content.onSelectedTabChange(PlayerScreenMode.PLAYER.index)
                navigationActions.onMinimize()
            } catch (_: kotlin.coroutines.cancellation.CancellationException) {
                // 详尽的中文注释：用户在手势拖拽时滑回以取消最小化返回，恢复原状态
            } finally {
                // 详尽的中文注释：手势执行完成或取消时，及时清空手势激活状态和进度值为 0f
                isPlayerBackActive = false
                playerBackProgress = 0f
            }
        }

        val animatedBgColor by animateColorAsState(
            targetValue = Color(metadata.backgroundColorArgb),
            animationSpec = tween(300),
            label = "bg_color"
        )

        val bgColor = MaterialTheme.colorScheme.background
        // 详尽中文注释：根据当前主题模式动态调整封面主色渐变的不透明度。
        // 暗色模式使用 0.5f 保持沉浸感；亮色模式降至 0.15f 使渐变清淡通透，避免深色主色在浅背景上显得浑浊。
        val isDarkTheme = isSystemInDarkTheme()
        val gradientAlpha = if (isDarkTheme) 0.5f else 0.15f
        val backgroundBrush by remember(animatedBgColor, bgColor, gradientAlpha) {
            derivedStateOf {
                Brush.verticalGradient(
                    colors = listOf(
                        animatedBgColor.copy(alpha = gradientAlpha),
                        bgColor
                    )
                )
            }
        }
        // 为每一次改动添加详尽的中文注释：Haze 模式优先使用原始封面，缺失时退回缩略图，作为真正模糊背景的图片来源。
        val playerBackgroundCoverFile = remember(
            metadata.coverPath,
            metadata.thumbnailPath,
            metadata.coverLastUpdated
        ) {
            (metadata.coverPath ?: metadata.thumbnailPath)
                ?.let(::File)
                ?.takeIf { it.exists() }
        }
        // 为每一次改动添加详尽的中文注释：给 Coil 请求加入 coverLastUpdated 缓存戳，保证封面自愈重建后模糊背景也会即时刷新。
        val playerBackgroundCoverRequest = remember(playerBackgroundCoverFile, metadata.coverLastUpdated, context) {
            playerBackgroundCoverFile?.let { coverFile ->
                coil.request.ImageRequest.Builder(context)
                    .data(coverFile)
                    .memoryCacheKey("${coverFile.absolutePath}_player_bg_${metadata.coverLastUpdated}")
                    .diskCacheKey("${coverFile.absolutePath}_player_bg_${metadata.coverLastUpdated}")
                    .build()
            }
        }

        // 详尽的中文注释：计算最大的向下位移像素值，顺应全屏播放器“向下滑动收起”的最小化退出特征
        val maxPredictiveTranslationY = with(density) { 120.dp.toPx() }

        // 详尽的中文注释：在横屏模式下，全屏播放器通常是左右双栏平铺排版，外层不需要竖屏抽屉的顶部大圆角，
        // 设为直角（RectangleShape）既符合大屏沉浸式视觉，也能在物理上彻底杜绝左上角和右上角内容被外层圆角裁切的隐患。
        val playerSurfaceShape = if (isLandscape) {
            androidx.compose.ui.graphics.RectangleShape
        } else {
            RoundedCornerShape(topStart = cornerRadiusDp, topEnd = cornerRadiusDp)
        }

        Surface(
            modifier = modifier
                .fillMaxSize()
                .offset { IntOffset(0, offsetY.value.roundToInt()) }
                .graphicsLayer {
                    // 详尽的中文注释：当全屏播放器拖拽最小化预测性返回手势处于激活状态时，
                    // 让卡片整体随手势的百分比进度向下平移（最多 120.dp），并伴随轻微等比缩放（1.0f -> 0.95f）与淡出（1.0f -> 0.7f），
                    // 在力导向和视觉上与最终向下滑动收起为迷你播放器的退出动画无缝融合。
                    if (isPlayerBackActive) {
                        translationY = playerBackProgress * maxPredictiveTranslationY
                        val scale = 1f - playerBackProgress * 0.05f
                        scaleX = scale
                        scaleY = scale
                        alpha = 1f - playerBackProgress * 0.3f
                    }
                }
                .clip(playerSurfaceShape),
            // 为每一次改动添加详尽的中文注释：将 bgColor 设置为 Surface 的 container color，充当最牢固的主题底层背景，杜绝任何透明漏光发生
            color = bgColor
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                // 为每一次改动添加详尽的中文注释：使用内层容器包裹背景封面、主体 UI Column 及底部 tabs，并在此应用 hazeSource。
                // 这样，外部 Box 中的 Snackbar 将作为兄弟节点运行在 hazeSource 之外，从而彻底规避 Haze 1.x 中由于父子生命周期导致的采样失效与绘制冲突。
                // 我们将 backgroundBrush 渐变背景移至此处 Box 挂载，并将其置于 chapterSheetHazeSourceModifier 之前，
                // 确保渐变层像素能够先于采样器绘制出来并被 hazeSource 100% 完整捕获，从而为兄弟节点 BlurSnackbar 提供饱满绚丽的磨砂高斯模糊效果。
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(backgroundBrush)
                        .then(chapterSheetHazeSourceModifier)
                ) {
                    // 为每一次改动添加详尽的中文注释：只有 Haze 模式渲染真实封面模糊背景，Material 模式保持原有主色渐变背景不变。
                    if (glassEffectMode == GlassEffectMode.Haze && playerBackgroundCoverRequest != null) {
                        PlayerCoverBlurredBackground(
                            imageRequest = playerBackgroundCoverRequest,
                            backgroundColor = bgColor,
                            isDarkTheme = isDarkTheme
                        )
                    }

                    if (isLandscape) {
                        // 为每一次改动添加详尽的中文注释：
                        // 为每一次改动添加详尽的中文注释：
                        // 适当降低了两侧的空白留白占位与双栏间距。
                        // 两侧占位（sidePadding）由屏幕总宽的 8% 减小至 3%（0.03f），双栏间距由 6% 减小至 4%（0.04f）。
                        // 这样既能合理规避边缘过于紧凑的生硬感，又能极大地拓宽有效的内容操作面域，使大封面和控制按钮更清晰舒展。
                        val screenWidthDp = configuration.screenWidthDp.dp
                        val screenHeightDp = configuration.screenHeightDp.dp
                        val sidePadding = screenWidthDp * 0.04f
                        val middleSpacing = screenWidthDp * 0.06f

                        // 为每一次改动添加详尽的中文注释：
                        // 平板或大折叠屏（最小屏幕宽度 smallestScreenWidthDp >= 600dp）保持原有的 10% 屏幕高度边距（0.1f）以提供高级呼吸感；
                        // 手机横屏时为了避免系统状态栏及底部虚拟导航栏的重叠遮挡，上下 padding 分别精准避让对应的状态栏（topPadding）与导航栏（bottomPadding）高度。
                        val isTabletOrLargeScreen = configuration.smallestScreenWidthDp >= 600
                        val systemBarsPadding = WindowInsets.systemBars.asPaddingValues()
                        
                        val topPadding = if (isTabletOrLargeScreen) {
                            screenHeightDp * 0.1f
                        } else {
                            systemBarsPadding.calculateTopPadding()
                        }
                        
                        val bottomPadding = if (isTabletOrLargeScreen) {
                            screenHeightDp * 0.1f
                        } else {
                            systemBarsPadding.calculateBottomPadding()
                        }

                        // 为每一次改动添加详尽的中文注释：
                        // 考虑不同设备的物理缺口（刘海屏/前置挖孔摄像头）及虚拟导航栏在横屏下的位置（位于左侧或右侧），
                        // 我们需要使用系统当前的 LayoutDirection 来动态计算出最左侧和最右侧的真实系统栏避让间距。
                        // 将其与我们设定的 sidePadding 基础外边距进行叠加，这样既保留了呼吸感，又确保左侧与右侧的任何内容绝对不被遮挡裁切。
                        val layoutDirection = androidx.compose.ui.platform.LocalLayoutDirection.current
                        val startPadding = sidePadding + systemBarsPadding.calculateStartPadding(layoutDirection)
                        val endPadding = sidePadding + systemBarsPadding.calculateEndPadding(layoutDirection)

                        Row(
                            modifier = Modifier
                                .fillMaxSize()
                                // 为每一次改动添加详尽的中文注释：
                                // 横跨手机和平板的横屏双栏布局边距自适应，平板上应用 10% 屏高边距，
                                // 手机上则上下紧贴安全区域避让状态栏与导航栏高度，左右侧叠加计算刘海和系统栏安全边距，
                                // 确保左侧和右侧的播放内容、控制面板绝对不被前置挖孔刘海或侧边系统栏遮挡裁切。
                                .padding(
                                    start = startPadding,
                                    top = topPadding,
                                    end = endPadding,
                                    bottom = bottomPadding
                                ),
                            horizontalArrangement = Arrangement.spacedBy(middleSpacing)
                        ) {
                            val swipeThresholdPx = with(density) { 80.dp.toPx() }
                            val tabModes = remember {
                                listOf(PlayerScreenMode.BOOKMARKS, PlayerScreenMode.SUBTITLES, PlayerScreenMode.RELATED)
                            }
                            val contentShell = remember(currentMode) {
                                when (currentMode) {
                                    PlayerScreenMode.BOOKMARKS -> PlayerContentShell.Bookmarks
                                    PlayerScreenMode.RELATED -> PlayerContentShell.Related
                                    PlayerScreenMode.PLAYER,
                                    PlayerScreenMode.SUBTITLES -> PlayerContentShell.PlaybackShell
                                }
                            }

                            // 为每一次改动添加详尽的中文注释：左侧栏自适应 Tab 状态内容区域 (占比约 40%)，完美复用滑动手势与内容切换机制
                            Column(
                                modifier = Modifier
                                    // 为每一次改动添加详尽的中文注释：横屏大屏自适应双栏等宽分配，左侧播放 Tab 状态内容区域分配比重为 1f
                                    .weight(1f)
                                    .fillMaxHeight()
                                    // 为每一次改动添加详尽的中文注释：按照用户最新视觉反馈，将内边距横向移动到最外层 Column 上，且占比降为 4% (0.04f)，
                                    // 这能让左侧顶满宽度的大封面与下方的导航栏 BottomNavTabs 保持完全整齐的垂直中轴对齐，呈现极其和谐的一致性美感。
                                    .padding(horizontal = screenWidthDp * 0.04f)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxWidth()
                                        .pointerInput(currentMode) {
                                            // 仅在非 PLAYER 模式下拦截水平拖拽手势
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
                                                            currentMode = nextMode
                                                            actions.content.onSelectedTabChange(nextMode.index)
                                                            hasSwipeTriggered = true
                                                        }
                                                    }
                                                    change.consume()
                                                }
                                            )
                                        }
                                ) {
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
                                        when (shell) {
                                            PlayerContentShell.PlaybackShell -> {
                                                val playbackTopMode = if (currentMode == PlayerScreenMode.SUBTITLES) {
                                                    PlayerScreenMode.SUBTITLES
                                                } else {
                                                    PlayerScreenMode.PLAYER
                                                }
                                                AnimatedContent(
                                                    targetState = playbackTopMode,
                                                    modifier = Modifier.fillMaxSize(),
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
                                                                    actions = actions,
                                                                    modifier = Modifier.fillMaxSize()
                                                                )
                                                            }
                                                        }
                                                        else -> {
                                                            // 为每一次改动添加详尽的中文注释：使用独立封装的自适应手势封面组件 PlayerCover，解耦布局层级
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
                                            }
                                            PlayerContentShell.Bookmarks -> {
                                                Box(modifier = Modifier.fillMaxSize()) {
                                                    BookmarkListViewStateful(
                                                        viewModel = viewModel,
                                                        metadata = metadata,
                                                        actions = actions,
                                                        modifier = Modifier.fillMaxSize()
                                                    )
                                                }
                                            }
                                            PlayerContentShell.Related -> {
                                                Box(modifier = Modifier.fillMaxSize()) {
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

                                BottomNavTabs(
                                    selectedTab = currentMode,
                                    onTabSelected = {
                                        val nextMode = if (currentMode == it) PlayerScreenMode.PLAYER else it
                                        currentMode = nextMode
                                        actions.content.onSelectedTabChange(nextMode.index)
                                    }
                                )
                            }

                            // 为每一次改动添加详尽的中文注释：右侧固定控制区域。
                            // 根据用户要求，在横屏/大屏模式下，已彻底去掉了原本的 Haze 模糊卡片背景（hazeEffect）、半透明底色以及边框描边，
                            // 让书名、进度条、播放按钮等控制项直接悬浮在全屏播放器精美大气的流光封面渐变背景之上，实现更通透扁平的高保真视觉质感。
                            Surface(
                                modifier = Modifier
                                    // 为每一次改动添加详尽的中文注释：横屏大屏自适应双栏等宽分配，右侧固定控制区域分配比重为 1f
                                    .weight(1f)
                                    .fillMaxHeight(),
                                // 为每一次改动添加详尽的中文注释：将圆角形状改为 RectangleShape（直角矩形），从而避免标题文字在大屏/横屏模式下被圆角无情裁切
                                shape = androidx.compose.ui.graphics.RectangleShape,
                                color = Color.Transparent,
                                border = null
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        // 为每一次改动添加详尽的中文注释：
                                        // 为了支持子项 Spacer(Modifier.weight(1f)) 自动拉伸占满空余以实现控制面板底对齐设计，
                                        // 我们在此处移除了 .verticalScroll(rememberScrollState()) 修饰符，从而避免 Compose 发生测量冲突。
//                                        .padding(20.dp)
                                ) {
                                    // 为每一次改动添加详尽的中文注释：大字号书籍标题行，在最右侧放置折叠菜单 MoreVert 图标入口以彻底隐藏左侧最小化按钮
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = metadata.title.takeIf { it.isNotBlank() } ?: "Unknown Title",
                                                style = MaterialTheme.typography.titleLarge,
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.onSurface,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Text(
                                                text = com.viel.aplayer.ui.common.formatPeopleSubtitle(metadata.author, metadata.narrator),
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }

                                        var showLandscapeMenu by remember { mutableStateOf(false) }
                                        Box {
                                            IconButton(onClick = { showLandscapeMenu = true }) {
                                                Icon(
                                                    imageVector = androidx.compose.material.icons.Icons.Rounded.MoreVert,
                                                    contentDescription = "More",
                                                    tint = MaterialTheme.colorScheme.onSurface
                                                )
                                            }
                                            BlurDropdownMenu(
                                                expanded = showLandscapeMenu,
                                                onDismissRequest = { showLandscapeMenu = false },
                                                hazeState = chapterSheetHazeState,
                                                glassEffectMode = glassEffectMode
                                            ) {
                                                DropdownMenuItem(
                                                    text = {
                                                        Text(if (settings.isChapterProgressMode) "Show Total Progress" else "Show Chapter Progress")
                                                    },
                                                    onClick = {
                                                        actions.content.onToggleProgressMode()
                                                        showLandscapeMenu = false
                                                    }
                                                )
                                                DropdownMenuItem(
                                                    text = {
                                                        Text("Delete from Library", color = MaterialTheme.colorScheme.error)
                                                    },
                                                    onClick = {
                                                        actions.content.onDeleteBook()
                                                        showLandscapeMenu = false
                                                    }
                                                )
                                            }
                                        }
                                    }

                                    // 为每一次改动添加详尽的中文注释：
                                    // 将 Spacer 修改为 weight(1f) 自适应占满剩余垂直空余，将章节名和播放控制等组件向下压实沉底对齐。
                                    Spacer(modifier = Modifier.weight(1f))
                                    PlayerControlPanel(
                                        viewModel = viewModel,
                                        metadata = metadata,
                                        controls = controls,
                                        settings = settings,
                                        actions = actions,
                                        buttonColor = animatedBgColor,
                                        glassEffectMode = glassEffectMode,
                                        hazeState = chapterSheetHazeState,
                                        // 为每一次改动添加详尽的中文注释：在横屏模式下为控制面板传入 fillMaxWidth 以撑满右侧布局区域
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }
                            }
                        }
                    } else {
                        // 为每一次改动添加详尽的中文注释：现有的竖屏单列 Column 编排布局（保留原有逻辑 100% 兼容不变）
                        Column(
                            modifier = Modifier.fillMaxSize()
                        ) {
                            PlayerAppBar(
                                title = metadata.title,
                                author = metadata.author,
                                narrator = metadata.narrator,
                                onNavigationClick = {
                                    focusManager.clearFocus()
                                    actions.content.onSelectedTabChange(PlayerScreenMode.PLAYER.index)
                                    navigationActions.onMinimize()
                                },
                                onToggleProgressMode = actions.content.onToggleProgressMode,
                                onDeleteBook = actions.content.onDeleteBook,
                                isChapterProgressMode = settings.isChapterProgressMode,
                                glassEffectMode = glassEffectMode,
                                dropdownMenuHazeState = chapterSheetHazeState,
                                modifier = Modifier.pointerInput(Unit) {
                                    detectVerticalDragGestures(
                                        onVerticalDrag = { change, dragAmount ->
                                            val newOffset = (offsetY.value + dragAmount).coerceAtLeast(0f)
                                            scope.launch {
                                                offsetY.snapTo(newOffset)
                                            }
                                            change.consume()
                                        },
                                        onDragEnd = {
                                            scope.launch {
                                                if (offsetY.value > dismissThreshold) {
                                                    actions.content.onSelectedTabChange(PlayerScreenMode.PLAYER.index)
                                                    navigationActions.onMinimize()
                                                } else {
                                                    offsetY.animateTo(0f, animationSpec = tween(300))
                                                }
                                            }
                                        },
                                        onDragCancel = {
                                            scope.launch { offsetY.animateTo(0f, animationSpec = tween(300)) }
                                        }
                                    )
                                }
                            )

                            val swipeThresholdPx = with(density) { 80.dp.toPx() }
                            val tabModes = remember {
                                listOf(PlayerScreenMode.BOOKMARKS, PlayerScreenMode.SUBTITLES, PlayerScreenMode.RELATED)
                            }
                            val contentShell = remember(currentMode) {
                                when (currentMode) {
                                    PlayerScreenMode.BOOKMARKS -> PlayerContentShell.Bookmarks
                                    PlayerScreenMode.RELATED -> PlayerContentShell.Related
                                    PlayerScreenMode.PLAYER,
                                    PlayerScreenMode.SUBTITLES -> PlayerContentShell.PlaybackShell
                                }
                            }
                            Box(modifier = Modifier
                                .weight(1f)
                                .pointerInput(currentMode) {
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
                                                    currentMode = nextMode
                                                    actions.content.onSelectedTabChange(nextMode.index)
                                                    hasSwipeTriggered = true
                                                }
                                            }
                                            change.consume()
                                        }
                                    )
                                }
                            ) {
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
                                                            // 为每一次改动添加详尽的中文注释：使用独立封装的自适应手势封面组件 PlayerCover，解耦布局层级
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
                                                PlayerControlPanel(
                                                    viewModel = viewModel,
                                                    metadata = metadata,
                                                    controls = controls,
                                                    settings = settings,
                                                    actions = actions,
                                                    buttonColor = animatedBgColor,
                                                    glassEffectMode = glassEffectMode,
                                                    hazeState = chapterSheetHazeState,
                                                    // 为每一次改动添加详尽的中文注释：在默认竖屏模式下传入 fillMaxWidth 并添加左右 24.dp padding 保持原有的安全内缩距离，以维持舒适的视觉美感
                                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp)
                                                )
                                            }
                                            PlayerContentShell.Bookmarks -> {
                                                Box(modifier = Modifier.weight(1f)) {
                                                    BookmarkListViewStateful(
                                                        viewModel = viewModel,
                                                        metadata = metadata,
                                                        actions = actions
                                                    )
                                                }
                                            }
                                            PlayerContentShell.Related -> {
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

                            BottomNavTabs(
                                selectedTab = currentMode,
                                onTabSelected = {
                                    val nextMode = if (currentMode == it) PlayerScreenMode.PLAYER else it
                                    currentMode = nextMode
                                    actions.content.onSelectedTabChange(nextMode.index)
                                }
                            )
                        }
                    }

                }

                // 为每一次改动添加详尽的中文注释：可切换 Haze 模糊的 Snackbar，作为兄弟节点直接放置在最外层 Box 的底部层叠位置。
                // 采用 150ms 上滑淡入/下滑淡出，显示时长控制仍由 ViewModel 管理。
                androidx.compose.animation.AnimatedVisibility(
                    visible = settings.showUndoSeek,
                    enter = slideInVertically(
                        animationSpec = tween(150),
                        initialOffsetY = { it }
                    ) + fadeIn(animationSpec = tween(150)),
                    exit = slideOutVertically(
                        animationSpec = tween(150),
                        targetOffsetY = { it }
                    ) + fadeOut(animationSpec = tween(150)),
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        // 详尽的中文注释：由于 Snackbar 是悬浮在 BottomNavTabs 之上的独立层级，我们将底边距提升至 96.dp，以防它与下方的 BottomNavTabs 发生视觉重叠遮挡。
                        .padding(horizontal = 16.dp, vertical = 96.dp)
                ) {
                    // 为每一次改动添加详尽的中文注释：使用新创建的 BlurSnackbar，支持在 Haze 模式下采样兄弟节点的背景模糊效果，Material 模式下展示原生样式。
                    BlurSnackbar(
                        hazeState = chapterSheetHazeState,
                        glassEffectMode = glassEffectMode,
                        action = {
                            TextButton(onClick = actions.playback.onUndoSeek) {
                                Text(
                                    text = "Undo",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        },
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = MaterialTheme.colorScheme.onSurface,
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(
                            text = "Jumped to a new position",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }
        }
        // 为每一次改动添加详尽的中文注释：关闭播放器 Surface 内容层，确保章节列表弹窗和书签 Dialog 仍作为同级浮层挂在 APlayerTheme 下。

        // 详尽的中文注释：采用 Stateful 局部隔间包裹章节列表弹窗，在弹窗不可见时完全停摆以防高频重组
        ChapterListSheetStateful(
            viewModel = viewModel,
            metadata = metadata,
            settings = settings,
            actions = actions,
            sheetState = sheetState,
            hazeState = chapterSheetHazeState,
            // 为每一次改动添加详尽的中文注释：章节列表 Stateful 层继续透传用户选择的 Material/Haze 模式。
            glassEffectMode = glassEffectMode
        )

        // 详尽的中文注释：桥接就近打字隔离后的 BookmarkDialog。通过回调 localTitle 执行 onTitleChange 和 onSave，无损向下兼容原契约
        BookmarkDialog(
            isVisible = settings.isBookmarkDialogVisible,
            defaultTitle = settings.bookmarkTitle,
            onSave = { localTitle ->
                actions.bookmarks.onTitleChange(localTitle)
                actions.bookmarks.onSave()
            },
            onDismiss = actions.bookmarks.onDismissDialog
        )
    }
}

@Composable
private fun PlayerCoverBlurredBackground(
    imageRequest: coil.request.ImageRequest,
    backgroundColor: Color,
    isDarkTheme: Boolean
) {
    Box(modifier = Modifier.fillMaxSize()) {
        // 为每一次改动添加详尽的中文注释：使用封面图本身铺满播放器背景，并通过 Compose blur 产生真正的像素级模糊，而不是仅使用主色渐变。
        AsyncImage(
            model = imageRequest,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxSize()
                // 为每一次改动添加详尽的中文注释：先轻微放大再模糊，避免 blur 半径在边缘产生透明留白。
                .graphicsLayer {
                    scaleX = 1.12f
                    scaleY = 1.12f
                }
                // 为每一次改动添加详尽的中文注释：64.dp 提供足够强的真实模糊，让背景只保留封面色块和氛围，不干扰播放器文字。
                .blur(64.dp)
        )

        // 为每一次改动添加详尽的中文注释：叠加主题背景遮罩，暗色/亮色分别控制可读性，避免封面高亮区域压住文字和控件。
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(backgroundColor.copy(alpha = if (isDarkTheme) 0.62f else 0.74f))
        )

        // 为每一次改动添加详尽的中文注释：底部稍微加深，保证播放控制区在不同封面颜色下仍保持稳定对比度。
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.Transparent,
                            backgroundColor.copy(alpha = if (isDarkTheme) 0.46f else 0.34f)
                        )
                    )
                )
        )
    }
}


// ==========================================
// 详尽的中文注释：APlayer 5 大局部 Stateful 隔间设计物理隔离区
// ==========================================


// 详尽的中文注释：
// 1. 进度条有状态局部隔间 PlaybackProgressStateful
// 本组件局部订阅高频 elapsedMs 进度状态，确保每 500ms 一次的高频进度改变
// 仅仅在 PlaybackProgress 内部引起局部微观重组，防止大范围 UI 污染。
@Composable
fun PlaybackProgressStateful(
    viewModel: PlayerViewModel,
    metadata: BookMetadataState,
    actions: PlayerActions,
    modifier: Modifier = Modifier
) {
    val isPreview = androidx.compose.ui.platform.LocalInspectionMode.current
    val progressState = if (isPreview) {
        PlayerViewModel.PlaybackProgressViewState(
            elapsedMs = 120000L,
            durationMs = 360000L,
            isChapterProgressMode = false
        )
    } else {
        viewModel.playbackProgressState.collectAsStateWithLifecycle().value
    }
    // 中文注释：已在此处取消了封面主导颜色取色（metadata.backgroundColorArgb）的绑定传递，使 PlaybackProgress 使用默认主题色
    PlaybackProgress(
        currentPosition = progressState.elapsedMs,
        totalDuration = progressState.durationMs,
        isChapterMode = progressState.isChapterProgressMode,
        chapters = metadata.chapters,
        markers = metadata.getChapterMarkers(progressState.durationMs),
        onSeek = { pos -> actions.playback.onSeek(pos, true) },
        modifier = modifier
    )
}

// 详尽的中文注释：
// 2. 章节标题显示有状态局部隔间 ChapterDisplayStateful
// 本组件局部订阅极其低频的章节变化通道 currentChapterState。
// 只有在真正切换音频章节的边界临界点时才会触发重组，实现了极致的重组频率隔离。
@Composable
fun ChapterDisplayStateful(
    viewModel: PlayerViewModel,
    metadata: BookMetadataState,
    actions: PlayerActions,
    glassEffectMode: GlassEffectMode,
    hazeState: HazeState?,
    modifier: Modifier = Modifier
) {
    val isPreview = androidx.compose.ui.platform.LocalInspectionMode.current
    val currentChapter = if (isPreview) {
        com.viel.aplayer.data.entity.ChapterEntity(
            id = "chapter_1",
            bookId = "book_1",
            bookFileId = "file_1",
            index = 1,
            title = "第一章：危机纪元",
            startPositionMs = 0L,
            durationMs = 360000L,
            fileOffsetMs = 0L,
            source = "EMBEDDED"
        )
    } else {
        viewModel.currentChapterState.collectAsStateWithLifecycle().value
    }
    ChapterDisplay(
        currentChapterTitle = currentChapter?.title ?: metadata.title,
        onChapterClick = actions.content.onShowChapterList,
        onBookmarkClick = actions.bookmarks.onShowDialog,
        glassEffectMode = glassEffectMode,
        hazeState = hazeState,
        modifier = modifier
    )
}

// 详尽的中文注释：
// 3. 书签面板有状态局部隔间 BookmarkListViewStateful
// 仅在展示 Bookmark 面板时，在此局部隔间内高频消费进度，以防进度刷新让外部关联列表和卡片无端重组。
// 详尽中文注释：M-16 修复 — 从 viewModel 收集书签对话框复合状态，并桥接回调事件至 viewModel 与 actions。
@Composable
fun BookmarkListViewStateful(
    viewModel: PlayerViewModel,
    metadata: BookMetadataState,
    actions: PlayerActions,
    modifier: Modifier = Modifier
) {
    val isPreview = androidx.compose.ui.platform.LocalInspectionMode.current
    val progressState = if (isPreview) {
        PlayerViewModel.PlaybackProgressViewState(
            elapsedMs = 120000L,
            durationMs = 360000L,
            isChapterProgressMode = false
        )
    } else {
        viewModel.playbackProgressState.collectAsStateWithLifecycle().value
    }
    // 详尽中文注释：M-16 — 实时收集书签对话框显示与编辑内容状态，防止配置变更（如屏幕旋转）导致输入内容丢失
    val dialogs = if (isPreview) {
        PlayerViewModel.BookmarkDialogsState()
    } else {
        viewModel.bookmarkDialogs.collectAsStateWithLifecycle().value
    }

    BookmarkListView(
        bookmarks = metadata.bookmarks,
        dialogs = dialogs,
        onBookmarkClick = { pos -> actions.playback.onSeek(pos, true) },
        // 详尽中文注释：M-16 — 请求删除，委托给 ViewModel 记录待删除条目
        onRequestDelete = { bookmark -> viewModel.requestDeleteBookmark(bookmark) },
        // 详尽中文注释：M-16 — 请求编辑，委托给 ViewModel 记录待编辑条目并回填初始标题
        onRequestEdit = { bookmark -> viewModel.requestEditBookmark(bookmark) },
        // 详尽中文注释：M-16 — 编辑框标题输入变更同步写入 ViewModel
        onEditTitleChange = { title -> viewModel.onBookmarkEditTitleChange(title) },
        // 详尽中文注释：M-16 — 确认删除动作，解包并触发 actions 的 onDelete 回调
        onConfirmDelete = {
            dialogs.toDelete?.let { bookmark ->
                actions.bookmarks.onDelete(bookmark)
            }
        },
        // 详尽中文注释：M-16 — 确认更新动作，解包并触发 actions 的 onUpdate 回调
        onConfirmUpdate = {
            dialogs.toEdit?.let { bookmark ->
                actions.bookmarks.onUpdate(bookmark, dialogs.editTitle)
            }
        },
        // 详尽中文注释：M-16 — 关闭/取消对话框，委托给 ViewModel 重置状态
        onDismissDialogs = { viewModel.dismissBookmarkDialogs() },
        currentPosition = progressState.elapsedMs,
        modifier = modifier
    )
}
// 详尽的中文注释：
// 4. 歌词字幕有状态局部隔间 SubtitlesViewStateful
// 局部订阅高频进度，维持流畅高频的歌词定位，阻断该高频对外部容器和 AppBar 等的刷新污染。
@Composable
fun SubtitlesViewStateful(
    viewModel: PlayerViewModel,
    metadata: BookMetadataState,
    actions: PlayerActions,
    modifier: Modifier = Modifier
) {
    val isPreview = androidx.compose.ui.platform.LocalInspectionMode.current
    val progressState = if (isPreview) {
        PlayerViewModel.PlaybackProgressViewState(
            elapsedMs = 120000L,
            durationMs = 360000L,
            isChapterProgressMode = false
        )
    } else {
        viewModel.playbackProgressState.collectAsStateWithLifecycle().value
    }
    SubtitlesView(
        subtitles = metadata.subtitles,
        currentPosition = progressState.elapsedMs,
        onSeek = { pos -> actions.playback.onSeek(pos, true) },
        modifier = modifier
    )
}

// 详尽的中文注释：
// 5. 章节列表弹窗有状态局部隔间 ChapterListSheetStateful
// 本隔间仅当弹窗真正可见（isVisible == true）时才订阅高频流以获取进度和高亮，
// 在弹窗关闭（isVisible == false）时整个有状态隔间不执行内部订阅代码，完全停摆，避免无意义的高频空耗。
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChapterListSheetStateful(
    viewModel: PlayerViewModel,
    metadata: BookMetadataState,
    settings: PlayerSettingsState,
    actions: PlayerActions,
    sheetState: androidx.compose.material3.SheetState,
    hazeState: HazeState,
    // 为每一次改动添加详尽的中文注释：接收全局玻璃效果模式并传给实际的 ChapterListSheet。
    glassEffectMode: GlassEffectMode
) {
    if (settings.isChapterListVisible) {
        val isPreview = androidx.compose.ui.platform.LocalInspectionMode.current
        val progressState = if (isPreview) {
            PlayerViewModel.PlaybackProgressViewState(
                elapsedMs = 120000L,
                durationMs = 360000L,
                isChapterProgressMode = false
            )
        } else {
            viewModel.playbackProgressState.collectAsStateWithLifecycle().value
        }
        val currentChapter = remember(progressState.elapsedMs, metadata.chapters) {
            com.viel.aplayer.media.ChapterTimeline.currentChapter(metadata.chapters, progressState.elapsedMs)
        }
        ChapterListSheet(
            isVisible = true,
            chapters = metadata.chapters,
            currentChapter = currentChapter,
            totalDuration = progressState.durationMs,
            onDismissRequest = actions.content.onDismissChapterList,
            onChapterClick = { pos ->
                actions.playback.onSeek(pos, true)
                actions.content.onDismissChapterList()
            },
            sheetState = sheetState,
            // 为每一次改动添加详尽的中文注释：将播放器背景 source 共用的 hazeState 传入章节列表面板 effect。
            hazeState = hazeState,
            // 为每一次改动添加详尽的中文注释：Material 模式会让章节列表回到原生 BottomSheet 容器层次。
            glassEffectMode = glassEffectMode
        )
    }
}

// ==========================================
// 详尽的中文注释：自适应播放器界面 Compose Previews 调试区
// ==========================================

@Preview(showBackground = true, apiLevel = 36)
@Composable
fun PlayerScreenPreview() {
    APlayerTheme {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
        ) {
            NewPlayerScreen(
                viewModel = PlayerViewModel(),
                actions = PlayerActions(),
                navigationActions = com.viel.aplayer.ui.navigation.PlayerNavigationActions(),
                glassEffectMode = GlassEffectMode.Material
            )
        }
    }
}

@Preview(showBackground = true, apiLevel = 36, widthDp = 800, heightDp = 480)
@Composable
fun PlayerScreenLandscapePreview() {
    APlayerTheme {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
        ) {
            NewPlayerScreen(
                viewModel = PlayerViewModel(),
                actions = PlayerActions(),
                navigationActions = PlayerNavigationActions(),
                glassEffectMode = GlassEffectMode.Material
            )
        }
    }
}
