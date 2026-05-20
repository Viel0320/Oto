package com.viel.aplayer.ui.player

import android.view.RoundedCorner
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import java.io.File
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import com.viel.aplayer.media.ChapterTimeline
import com.viel.aplayer.ui.bookmarks.BookmarkDialog
import com.viel.aplayer.ui.bookmarks.BookmarkListView
import com.viel.aplayer.ui.common.BottomNavTabs
import com.viel.aplayer.ui.navigation.PlayerNavigationActions
import com.viel.aplayer.ui.player.components.ChapterDisplay
import com.viel.aplayer.ui.player.components.ChapterListSheet
import com.viel.aplayer.ui.player.components.PlaybackControls
import com.viel.aplayer.ui.player.components.PlaybackProgress
import com.viel.aplayer.ui.player.components.MainCoverView
import com.viel.aplayer.ui.player.components.PlayerAppBar
import com.viel.aplayer.ui.player.components.PlayerControlPanel
import com.viel.aplayer.ui.player.components.RelatedBooksView
import com.viel.aplayer.ui.player.components.SubtitlesView
import com.viel.aplayer.ui.settings.PlayerSettingsState
import com.viel.aplayer.ui.theme.APlayerTheme



enum class PlayerScreenMode(val index: Int) {
    PLAYER(-1),
    BOOKMARKS(0),
    SUBTITLES(1),
    RELATED(2)
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun NewPlayerScreen(
    viewModel: com.viel.aplayer.ui.player.PlayerViewModel,
    actions: PlayerActions,
    navigationActions: PlayerNavigationActions,
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
    var playerBackProgress by remember { mutableStateOf(0f) }

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

    APlayerTheme(darkTheme = true) {
        val focusManager = LocalFocusManager.current
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)

        // 详尽的中文注释：当处于书签/歌词/推荐等面板时，使用 PredictiveBackHandler 平滑返回主播放页面
        androidx.activity.compose.PredictiveBackHandler(enabled = currentMode != PlayerScreenMode.PLAYER) { progressFlow ->
            try {
                progressFlow.collect { }
                currentMode = PlayerScreenMode.PLAYER
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
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
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
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
        val backgroundBrush by remember(animatedBgColor, bgColor) {
            derivedStateOf {
                Brush.verticalGradient(
                    colors = listOf(
                        animatedBgColor.copy(alpha = 0.5f),
                        bgColor
                    )
                )
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
                .background(backgroundBrush),
            color = Color.Transparent
        ) {
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

                Box(modifier = Modifier.weight(1f)) {
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
                                        // 详尽的中文注释：在此处将自愈封面最后修改时间戳 metadata.coverLastUpdated 传入，从而强力打通缓存刷新机制
                                        MainCoverView(metadata.coverPath, controls.isPlaying, metadata.coverLastUpdated)
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

        // 详尽的中文注释：采用 Stateful 局部隔间包裹章节列表弹窗，在弹窗不可见时完全停摆以防高频重组
        ChapterListSheetStateful(
            viewModel = viewModel,
            metadata = metadata,
            settings = settings,
            actions = actions,
            sheetState = sheetState
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

// 详尽的中文注释：
// 1. 进度条有状态局部隔间 PlaybackProgressStateful
// 本组件局部订阅高频 elapsedMs 进度状态，确保每 500ms 一次的高频进度改变
// 仅仅在 PlaybackProgress 内部引起局部微观重组，防止大范围 UI 污染。
@Composable
fun PlaybackProgressStateful(
    viewModel: com.viel.aplayer.ui.player.PlayerViewModel,
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
        modifier = modifier
    )
}

// 详尽的中文注释：
// 2. 章节标题显示有状态局部隔间 ChapterDisplayStateful
// 本组件局部订阅极其低频的章节变化通道 currentChapterState。
// 只有在真正切换音频章节的边界临界点时才会触发重组，实现了极致的重组频率隔离。
@Composable
fun ChapterDisplayStateful(
    viewModel: com.viel.aplayer.ui.player.PlayerViewModel,
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
@Composable
fun BookmarkListViewStateful(
    viewModel: com.viel.aplayer.ui.player.PlayerViewModel,
    metadata: BookMetadataState,
    actions: PlayerActions,
    modifier: Modifier = Modifier
) {
    val progressState by viewModel.playbackProgressState.collectAsStateWithLifecycle()
    BookmarkListView(
        bookmarks = metadata.bookmarks,
        onBookmarkClick = { pos -> actions.playback.onSeek(pos, true) },
        onDeleteClick = actions.bookmarks.onDelete,
        onUpdateClick = actions.bookmarks.onUpdate,
        currentPosition = progressState.elapsedMs,
        modifier = modifier
    )
}

// 详尽的中文注释：
// 4. 歌词字幕有状态局部隔间 SubtitlesViewStateful
// 局部订阅高频进度，维持流畅高频的歌词定位，阻断该高频对外部容器和 AppBar 等的刷新污染。
@Composable
fun SubtitlesViewStateful(
    viewModel: com.viel.aplayer.ui.player.PlayerViewModel,
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
    viewModel: com.viel.aplayer.ui.player.PlayerViewModel,
    metadata: BookMetadataState,
    settings: PlayerSettingsState,
    actions: PlayerActions,
    sheetState: androidx.compose.material3.SheetState
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
            sheetState = sheetState
        )
    }
}