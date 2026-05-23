package com.viel.aplayer.ui.player

// 为每一次改动添加详尽的中文注释：导入用于计算 PaddingValues 水平安全边距的扩展函数，解决横屏下防裁切的安全边距叠加计算编译依赖
import android.content.res.Configuration
import android.view.RoundedCorner
import androidx.compose.animation.AnimatedContent
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
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.viel.aplayer.data.store.GlassEffectMode
import com.viel.aplayer.ui.bookmarks.BookmarkDialog
import com.viel.aplayer.ui.bookmarks.BookmarkListViewStateful
import com.viel.aplayer.ui.common.BlurSnackbar
import com.viel.aplayer.ui.common.BottomNavTabs
import com.viel.aplayer.ui.common.CoverBackground
import com.viel.aplayer.ui.navigation.PlayerNavigationActions
import com.viel.aplayer.ui.player.components.ChapterListSheetStateful
import com.viel.aplayer.ui.player.components.PlayerControlPanel
import com.viel.aplayer.ui.common.PlayerCover
import com.viel.aplayer.ui.player.components.PlayerLandscapeHeader
import com.viel.aplayer.ui.player.components.PlayerVerticalAppBar
import com.viel.aplayer.ui.player.components.RelatedBooksView
import com.viel.aplayer.ui.player.components.SubtitlesViewStateful
import com.viel.aplayer.ui.theme.APlayerTheme
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import java.io.File
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
        val focusManager = LocalFocusManager.current
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
        // 为每一次改动添加详尽的中文注释：
        // 重新在 LocalComposables 环境下声明全局 chapterSheetBackdrop 状态机采样源，修复未解析引用的编译错误。
        // 将其作为背景层专属采样源，并与前景组件隔离为同级兄弟，彻底规避 Vulkan feedback loop 死锁崩溃。
        val chapterSheetBackdrop = rememberLayerBackdrop()


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
                // 为每一次改动添加详尽的中文注释：使用内层容器包裹背景封面、主体 UI Column 及底部 tabs，并在此应用 layerBackdrop。
                // 这样，外部 Box 中的 Snackbar 将作为兄弟节点运行在 layerBackdrop 之外，从而彻底规避旧版中由于父子生命周期导致的采样失效与绘制冲突。
                // 我们将 backgroundBrush 渐变背景移至此处 Box 挂载，并将其置于 chapterSheetBackdrop 之前，
                // 确保渐变层像素能够先于采样器绘制出来并被 chapterSheetBackdrop 100% 完整捕获，从而为兄弟节点 BlurSnackbar 提供饱满绚丽的磨砂高斯模糊效果。
                // 为每一次改动添加详尽的中文注释：
                // 彻底修复特定的 OPLUS (一加) 设备在启用 miuix-blur 模糊效果后，由于父子循环嵌套采样导致的 RenderThread SIGSEGV 段错误闪退。
                // 我们在此重构了 NewPlayerScreen 的根布局层级，将挂载了 layerBackdrop 采样器的背景图层，与使用 drawBackdrop 绘制毛玻璃的前景内容组件完全剥离为同级的【兄弟节点】。
                // 这样能确保采样源中仅包含渐变和模糊封面，前景组件采样时不会引起 Self-sampling Feedback Loop，从图形渲染树根源上斩断死锁，根治 Vulkan 崩溃，且继续维持精美的磨砂玻璃胶囊和按钮视觉。

                // 1. 纯净背景图层 (同级兄弟节点)
                // 为每一次改动添加详尽的中文注释：接入通用的 CoverBackground 组件，统一管理播放器背景逻辑。
                // 内部处理了颜色过渡动画、miuix-blur 采样源挂载以及 64.dp 强模糊封面渲染。
                CoverBackground(
                    coverPath = metadata.coverPath ?: metadata.thumbnailPath,
                    lastUpdated = metadata.coverLastUpdated,
                    backgroundColorArgb = metadata.backgroundColorArgb,
                    glassEffectMode = glassEffectMode,
                    backdrop = chapterSheetBackdrop
                )

                // 2. 纯净前景操作图层 (同级兄弟节点，内部所有组件均可安全 drawBackdrop 进行背景采样折射，彻底斩断循环死锁)
                Box(
                    modifier = Modifier.fillMaxSize()
                ) {

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
                            // 根据用户要求，在横屏/大屏模式下，已彻底去掉了原本的 miuix-blur 模糊卡片背景（blurEffect）、半透明底色以及边框描边，
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
                                    // 为每一次改动添加详尽的中文注释：使用新抽离的 PlayerLandscapeHeader 组件，统一横屏下的标题、作者信息及更多操作入口。
                                    PlayerLandscapeHeader(
                                        metadata = metadata,
                                        settings = settings,
                                        actions = actions,
                                        glassEffectMode = glassEffectMode,
                                        backdrop = chapterSheetBackdrop
                                    )

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
                                        backdrop = chapterSheetBackdrop,
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
                        // 为每一次改动添加详尽的中文注释：使用新抽离的 PlayerVerticalAppBar 组件，封装顶部栏及其下拉最小化的手势逻辑
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
                                                    backdrop = chapterSheetBackdrop,
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

                // 为每一次改动添加详尽的中文注释：可切换 miuix-blur 模糊的 Snackbar，作为兄弟节点直接放置在最外层 Box 的底部层叠位置。
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
                    // 为每一次改动添加详尽的中文注释：使用新创建的 BlurSnackbar，支持在 miuix-blur 模式下采样兄弟节点的背景模糊效果，Material 模式下展示原生样式。
                    BlurSnackbar(
                        backdrop = chapterSheetBackdrop,
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
            backdrop = chapterSheetBackdrop,
            // 为每一次改动添加详尽的中文注释：章节列表 Stateful 层继续透传用户选择的 Material/miuix-blur 模式。
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

// ==========================================
// 详尽的中文注释：APlayer 5 大局部 Stateful 隔间设计物理隔离区
// ==========================================


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
            // 为每一次改动添加详尽的中文注释：
            // 竖屏预览模式。注入默认的 ViewModel 和 Actions。
            // 此时组件会展示 PlayerContentShell.PlaybackShell 中的封面内容。
            NewPlayerScreen(
                viewModel = PlayerViewModel(),
                actions = PlayerActions(),
                navigationActions = PlayerNavigationActions(),
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
            // 为每一次改动添加详尽的中文注释：
            // 横屏预览模式。显式开启 GlassEffectMode.MiuixBlur，
            // 用于调试横屏双栏布局在大屏/平板下的自适应间距以及背景模糊的穿透效果。
            NewPlayerScreen(
                viewModel = PlayerViewModel(),
                actions = PlayerActions(),
                navigationActions = PlayerNavigationActions(),
                glassEffectMode = GlassEffectMode.MiuixBlur
            )
        }
    }
}
