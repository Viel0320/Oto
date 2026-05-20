package com.viel.aplayer.ui.screens

import android.view.RoundedCorner
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
import com.viel.aplayer.playback.ChapterTimeline
import com.viel.aplayer.ui.action.PlayerActions
import com.viel.aplayer.ui.action.PlayerNavigationActions
import com.viel.aplayer.ui.components.BookmarkDialog
import com.viel.aplayer.ui.components.BottomNavTabs
import com.viel.aplayer.ui.components.BookmarkListView
import com.viel.aplayer.ui.components.ChapterDisplay
import com.viel.aplayer.ui.components.ChapterListSheet
import com.viel.aplayer.ui.components.PlaybackControls
import com.viel.aplayer.ui.components.PlaybackProgress
import com.viel.aplayer.ui.components.PlayerAppBar
import com.viel.aplayer.ui.components.RelatedBooksView
import com.viel.aplayer.ui.components.SubtitlesView
import com.viel.aplayer.ui.state.BookMetadataState
import com.viel.aplayer.ui.state.PlaybackState
import com.viel.aplayer.ui.state.PlayerSettingsState
import com.viel.aplayer.ui.theme.APlayerTheme
import kotlinx.coroutines.launch
import java.io.File
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
    viewModel: com.viel.aplayer.ui.viewmodel.PlayerViewModel,
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

        androidx.activity.compose.BackHandler(enabled = currentMode != PlayerScreenMode.PLAYER) {
            currentMode = PlayerScreenMode.PLAYER
        }

        androidx.activity.compose.BackHandler(enabled = currentMode == PlayerScreenMode.PLAYER && settings.isFullPlayerVisible) {
            actions.content.onSelectedTabChange(PlayerScreenMode.PLAYER.index)
            navigationActions.onMinimize()
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

        Surface(
            modifier = modifier
                .fillMaxSize()
                .offset { IntOffset(0, offsetY.value.roundToInt()) }
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
                                    StablePlaybackControls(viewModel, metadata, controls, settings, actions, animatedBgColor)
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
                                    StablePlaybackControls(viewModel, metadata, controls, settings, actions, animatedBgColor)
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

@Composable
private fun MainCoverView(coverPath: String?, isPlaying: Boolean, coverLastUpdated: Long = 0L) {
    val coverScale by animateFloatAsState(
        targetValue = if (isPlaying) 1f else 0.95f,
        animationSpec = tween(300)
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp, vertical = 32.dp),
        contentAlignment = Alignment.TopCenter
    ) {
        // 详尽的中文注释：将 coverLastUpdated 纳入 remember 的 keys，确保当数据库自愈更新时间戳改变后，强行使 File 对象及其后的分支重新判断与重绘
        val coverFile = remember(coverPath, coverLastUpdated) {
            if (coverPath != null) File(coverPath) else null
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .graphicsLayer {
                    scaleX = coverScale
                    scaleY = coverScale
                    transformOrigin = TransformOrigin(0.5f, 0.0f)
                }
                .clip(RoundedCornerShape(24.dp))
        ) {
            if (coverFile != null && coverFile.exists()) {
                // 详尽的中文注释：使用 ImageRequest.Builder 重新构建 data model，
                // 并利用具有更新时间戳后缀的 memoryCacheKey 和 diskCacheKey 来打破 Coil 对该图片的加载失败或已有缓存，
                // 确保在封面文件被自愈重建后，Coil 能够丢弃原有失败记忆、即刻从物理文件中拉取并渲染最新的封面。
                // 另外，加上 onError 加载错误日志打印，使 Scoped Storage 等加载异常可视化。
                AsyncImage(
                    model = coil.request.ImageRequest.Builder(androidx.compose.ui.platform.LocalContext.current)
                        .data(coverFile)
                        .memoryCacheKey("${coverFile.absolutePath}_$coverLastUpdated")
                        .diskCacheKey("${coverFile.absolutePath}_$coverLastUpdated")
                        .build(),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant),
                    contentScale = ContentScale.Crop,
                    onError = { errorState ->
                        android.util.Log.e("NewPlayerScreen", "全屏播放器加载封面图片失败: ${coverFile.absolutePath}, 原因: ", errorState.result.throwable)
                    }
                )
            } else {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.PlayArrow, null, Modifier.size(80.dp), tint = Color.Gray)
                }
            }
        }
    }
}

// 详尽的中文注释：
// 改造后的 StablePlaybackControls。彻底剔除了对高频 PlaybackState 实体的直接依赖。
// 内部的进度条和章节标题分别采用 Stateful 局部隔间进行包装，只在各自局部订阅对应高/低频数据通道，
// 从而确保在音乐播放时主播放控制区的重组发生率完美降为 0。
@Composable
private fun StablePlaybackControls(
    viewModel: com.viel.aplayer.ui.viewmodel.PlayerViewModel,
    metadata: BookMetadataState,
    controls: com.viel.aplayer.ui.viewmodel.PlayerViewModel.PlaybackControlState,
    settings: PlayerSettingsState,
    actions: PlayerActions,
    buttonColor: Color
) {
    Column(modifier = Modifier.padding(horizontal = 24.dp)) {
        // 详尽的中文注释：章节标题显示局部隔间，订阅极其低频的章节变化流
        ChapterDisplayStateful(
            viewModel = viewModel,
            metadata = metadata,
            actions = actions
        )
        Spacer(Modifier.height(16.dp))
        
        // 详尽的中文注释：进度条显示局部隔间，只在此内部高频重组
        PlaybackProgressStateful(
            viewModel = viewModel,
            metadata = metadata,
            actions = actions
        )
        Spacer(Modifier.height(24.dp))
        PlaybackControls(
            isPlaying = controls.isPlaying,
            playbackSpeed = controls.playbackSpeed,
            selectedSleepTimer = settings.selectedSleepTimer,
            isSpeedManualMode = controls.isSpeedManualMode,
            actions = actions.playback,
            buttonColor = buttonColor
        )
        Spacer(Modifier.height(12.dp))
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
    viewModel: com.viel.aplayer.ui.viewmodel.PlayerViewModel,
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
    viewModel: com.viel.aplayer.ui.viewmodel.PlayerViewModel,
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
    viewModel: com.viel.aplayer.ui.viewmodel.PlayerViewModel,
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
    viewModel: com.viel.aplayer.ui.viewmodel.PlayerViewModel,
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
    viewModel: com.viel.aplayer.ui.viewmodel.PlayerViewModel,
    metadata: BookMetadataState,
    settings: PlayerSettingsState,
    actions: PlayerActions,
    sheetState: androidx.compose.material3.SheetState
) {
    if (settings.isChapterListVisible) {
        val progressState by viewModel.playbackProgressState.collectAsStateWithLifecycle()
        val currentChapter = remember(progressState.elapsedMs, metadata.chapters) {
            com.viel.aplayer.playback.ChapterTimeline.currentChapter(metadata.chapters, progressState.elapsedMs)
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
