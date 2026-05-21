package com.viel.aplayer.ui.player

import android.view.RoundedCorner
import androidx.compose.animation.AnimatedContent
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
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
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
import com.viel.aplayer.ui.navigation.PlayerNavigationActions
import com.viel.aplayer.ui.player.components.ChapterDisplay
import com.viel.aplayer.ui.player.components.ChapterListSheet
import com.viel.aplayer.ui.player.components.MainCoverView
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

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun NewPlayerScreen(
    viewModel: PlayerViewModel,
    actions: PlayerActions,
    navigationActions: PlayerNavigationActions,
    // 为每一次改动添加详尽的中文注释：接收全局玻璃效果模式，用于控制章节列表背景是否启用 Haze 采样；未传入时默认 Material。
    glassEffectMode: GlassEffectMode = GlassEffectMode.Material,
    modifier: Modifier = Modifier,
) {
    val metadata by viewModel.metadataState.collectAsStateWithLifecycle()
    val settings by viewModel.settingsState.collectAsStateWithLifecycle()
    val controls by viewModel.playbackControlState.collectAsStateWithLifecycle()
    
    val fullUiState by viewModel.uiState.collectAsStateWithLifecycle()

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
                .clip(RoundedCornerShape(topStart = cornerRadiusDp, topEnd = cornerRadiusDp))
                .background(bgColor)
                .background(backgroundBrush)
                // 为每一次改动添加详尽的中文注释：播放器完整沉浸界面作为 ChapterListSheet 的 Haze 背景采样源。
                .then(chapterSheetHazeSourceModifier),
            color = Color.Transparent
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                // 为每一次改动添加详尽的中文注释：只有 Haze 模式渲染真实封面模糊背景，Material 模式保持原有主色渐变背景不变。
                if (glassEffectMode == GlassEffectMode.Haze && playerBackgroundCoverRequest != null) {
                    PlayerCoverBlurredBackground(
                        imageRequest = playerBackgroundCoverRequest,
                        backgroundColor = bgColor,
                        isDarkTheme = isDarkTheme
                    )
                }

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
                        // 为每一次改动添加详尽的中文注释：播放器顶部更多菜单复用播放器 Surface 的 HazeState，并跟随全局 Material/Haze 模式。
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

                    // 详尽中文注释：为非 PLAYER 三种 tab（书签/字幕/推荐）的内容区域添加左右滑动手势。
                // 使用 detectHorizontalDragGestures 累积水平位移，超过 swipeThresholdPx 阈值（80dp）后触发切换。
                // 三个 tab 按 index 排列：BOOKMARKS(0) → SUBTITLES(1) → RELATED(2)。
                // 左滑（负向）切换到更大 index 的 tab；右滑（正向）切换到更小 index 的 tab。
                // 当已到达边界时继续同方向滑动则返回 PLAYER 主播放页面。
                // PLAYER 模式本身不响应此手势，封面区有独立的水平切章手势不会产生冲突。
                val swipeThresholdPx = with(density) { 80.dp.toPx() }
                // 详尽中文注释：按 index 顺序排列的 tab 模式列表，用于边界计算和相邻导航
                val tabModes = remember {
                    listOf(PlayerScreenMode.BOOKMARKS, PlayerScreenMode.SUBTITLES, PlayerScreenMode.RELATED)
                }
                Box(modifier = Modifier
                    .weight(1f)
                    .pointerInput(currentMode) {
                        // 详尽中文注释：仅在非 PLAYER 模式下拦截水平拖拽手势
                        if (currentMode == PlayerScreenMode.PLAYER) return@pointerInput
                        var accumulatedX = 0f  // 累积水平位移，重置于每次 drag 开始
                        var hasSwipeTriggered = false  // 去抖标志：同一次手势只触发一次切换
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
                                // 详尽中文注释：未触发过切换时持续累积位移，超阈值后执行一次切换并标记去抖
                                if (!hasSwipeTriggered) {
                                    accumulatedX += dragAmount
                                    if (kotlin.math.abs(accumulatedX) > swipeThresholdPx) {
                                        val currentIndex = tabModes.indexOf(currentMode)
                                        val nextMode = if (accumulatedX < 0) {
                                            // 详尽中文注释：左滑（手指向左）→ 切换到 index 更大的下一个 tab；已在最右则返回 PLAYER
                                            if (currentIndex < tabModes.lastIndex) tabModes[currentIndex + 1]
                                            else PlayerScreenMode.PLAYER
                                        } else {
                                            // 详尽中文注释：右滑（手指向右）→ 切换到 index 更小的上一个 tab；已在最左则返回 PLAYER
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
                        targetState = currentMode,
                        modifier = Modifier.fillMaxSize(),
                        transitionSpec = {
                            if (initialState == PlayerScreenMode.PLAYER || targetState == PlayerScreenMode.PLAYER) {
                                fadeIn(animationSpec = tween(300)) togetherWith fadeOut(animationSpec = tween(300))
                            } else {
                                if (targetState.index > initialState.index) {
                                    (slideInHorizontally(animationSpec = tween(300)) { it } + fadeIn(animationSpec = tween(300)))
                                        .togetherWith(slideOutHorizontally(animationSpec = tween(300)) { -it } + fadeOut(animationSpec = tween(300)))
                                } else {
                                    (slideInHorizontally(animationSpec = tween(300)) { -it } + fadeIn(animationSpec = tween(300)))
                                        .togetherWith(slideOutHorizontally(animationSpec = tween(300)) { it } + fadeOut(animationSpec = tween(300)))
                                }
                            }.using(SizeTransform(clip = false))
                        },
                        label = "player_mode_transition"
                    ) { mode ->
                        Column(modifier = Modifier.fillMaxSize()) {
                            when (mode) {
                                PlayerScreenMode.PLAYER -> {
                                    Box(modifier = Modifier.weight(1f)) {
                                        // 详尽中文注释：定义封面拖拽手势所需的水平拖拽距离累加器与触发状态标志，用于水平滑动切换上下章节时的去抖动处理。
                                        var totalHorizontalDrag by remember { mutableFloatStateOf(0f) }
                                        var hasTriggeredHorizontalDrag by remember { mutableStateOf(false) }

                                        // 详尽的中文注释：在此处将自愈封面最后修改时间戳 metadata.coverLastUpdated 传入，从而强力打通缓存刷新机制。
                                        // 同时，通过 pointerInput 绑定 detectDragGestures，重构并恢复此前遗失的封面交互手势（上下滑动微调音量，左右滑动切歌/切章节）。
                                        MainCoverView(
                                            coverPath = metadata.coverPath,
                                            isPlaying = controls.isPlaying,
                                            coverLastUpdated = metadata.coverLastUpdated,
                                            modifier = Modifier.pointerInput(Unit) {
                                                detectDragGestures(
                                                    onDragStart = { 
                                                        totalHorizontalDrag = 0f
                                                        hasTriggeredHorizontalDrag = false
                                                    },
                                                    onDragEnd = { 
                                                        totalHorizontalDrag = 0f
                                                        hasTriggeredHorizontalDrag = false
                                                    },
                                                    onDragCancel = { 
                                                        totalHorizontalDrag = 0f
                                                        hasTriggeredHorizontalDrag = false
                                                    },
                                                    onDrag = { change, dragAmount ->
                                                        change.consume()
                                                        if (kotlin.math.abs(dragAmount.y) > kotlin.math.abs(dragAmount.x)) {
                                                            // 详尽中文注释：垂直拖拽用于微调系统音量大小，负号是为了拖动方向更符合直觉（向上滑增大，向下滑减小）
                                                            actions.playback.onAdjustVolume(-dragAmount.y * 0.002f)
                                                        } else if (!hasTriggeredHorizontalDrag) {
                                                            // 详尽中文注释：水平拖拽用于切换章节，使用 totalHorizontalDrag 累积滑动距离进行阈值（300f）去抖
                                                            totalHorizontalDrag += dragAmount.x
                                                            if (kotlin.math.abs(totalHorizontalDrag) > 300f) {
                                                                if (totalHorizontalDrag > 0) {
                                                                    actions.playback.onNextChapter()
                                                                } else {
                                                                    actions.playback.onPreviousChapter()
                                                                }
                                                                hasTriggeredHorizontalDrag = true
                                                            }
                                                        }
                                                    }
                                                )
                                            }
                                        )
                                    }
                                    // 详尽的中文注释：将 viewModel 注入，并在内部采用局部的进度和章节 Stateful 隔间包装
                                    PlayerControlPanel(viewModel, metadata, controls, settings, actions, animatedBgColor)
                                }
                                PlayerScreenMode.BOOKMARKS -> {
                                    Box(modifier = Modifier.weight(1f)) {
                                        // 详尽的中文注释：使用 Stateful 局部进度隔间，防止进度刷新引发书签背景重组
                                        BookmarkListViewStateful(
                                            viewModel = viewModel,
                                            metadata = metadata,
                                            actions = actions
                                        )
                                    }
                                }
                                PlayerScreenMode.SUBTITLES -> {
                                    Box(modifier = Modifier.weight(1f)) {
                                        // 详尽的中文注释：使用 Stateful 局部进度隔间控制歌词字幕高频刷新，而不影响外层
                                        SubtitlesViewStateful(
                                            viewModel = viewModel,
                                            metadata = metadata,
                                            actions = actions
                                        )
                                    }
                                    PlayerControlPanel(viewModel, metadata, controls, settings, actions, animatedBgColor)
                                }
                                PlayerScreenMode.RELATED -> {
                                    Box(modifier = Modifier.weight(1f)) {
                                        RelatedBooksView(
                                            currentBookId = metadata.id,
                                            // 为每一次改动添加详尽的中文注释：在此传入由 ViewModel 响应式流动汇聚的“启发式智能推荐”书籍列表，完成端到端数据的完美交接与置顶呈现。
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

                    // Undo seek Snackbar 使用 150ms 上滑淡入/下滑淡出，3 秒可见窗口仍由 ViewModel 控制。
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
                            .padding(horizontal = 16.dp, vertical = 12.dp)
                    ) {
                        // 详尽的中文注释：根据 Material 3 规范与 APlayer 播放器暗色主题优化 Snackbar 视觉样式
                        // 使用 surfaceVariant 作为背景色以防止在深色界面中显得过于刺眼，结合 primary 主色调动作按钮提升视觉品质，配以 12.dp 圆角
                        Snackbar(
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
        // 为每一次改动添加详尽的中文注释：关闭播放器 Surface 内容层，确保章节列表弹窗和书签 Dialog 仍作为同级浮层挂在 APlayerTheme 下。
        }

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
    val progressState by viewModel.playbackProgressState.collectAsStateWithLifecycle()
    PlaybackProgress(
        currentPosition = progressState.elapsedMs,
        totalDuration = progressState.durationMs,
        isChapterMode = progressState.isChapterProgressMode,
        chapters = metadata.chapters,
        markers = metadata.getChapterMarkers(progressState.durationMs),
        onSeek = { pos -> actions.playback.onSeek(pos, true) },
        modifier = modifier,
        // 为每一次改动添加详尽的中文注释：将封面取色所得的背景/主导 ARGB 色值转换为 Compose Color，传给进度条用于已阅读轨道及圆点 dot 着色
        color = Color(metadata.backgroundColorArgb)
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
    modifier: Modifier = Modifier
) {
    val currentChapter by viewModel.currentChapterState.collectAsStateWithLifecycle()
    ChapterDisplay(
        currentChapterTitle = currentChapter?.title ?: metadata.title,
        onChapterClick = actions.content.onShowChapterList,
        onBookmarkClick = actions.bookmarks.onShowDialog,
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
    val progressState by viewModel.playbackProgressState.collectAsStateWithLifecycle()
    // 详尽中文注释：M-16 — 实时收集书签对话框显示与编辑内容状态，防止配置变更（如屏幕旋转）导致输入内容丢失
    val dialogs by viewModel.bookmarkDialogs.collectAsStateWithLifecycle()

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
    val progressState by viewModel.playbackProgressState.collectAsStateWithLifecycle()
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
        val progressState by viewModel.playbackProgressState.collectAsStateWithLifecycle()
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
