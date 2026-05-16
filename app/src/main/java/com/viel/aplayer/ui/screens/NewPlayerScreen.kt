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
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.viel.aplayer.data.AudiobookEntity
import com.viel.aplayer.data.BookmarkEntity
import com.viel.aplayer.data.ChapterEntity
import com.viel.aplayer.ui.state.RelatedSection
import com.viel.aplayer.ui.action.PlayerActions
import com.viel.aplayer.ui.action.PlayerNavigationActions
import com.viel.aplayer.ui.components.BookmarkDialog
import com.viel.aplayer.ui.components.BookmarkListView
import com.viel.aplayer.ui.components.ChapterDisplay
import com.viel.aplayer.ui.components.ChapterListSheet
import com.viel.aplayer.ui.components.PlaybackControls
import com.viel.aplayer.ui.components.PlaybackProgress
import com.viel.aplayer.ui.components.PlayerAppBar
import com.viel.aplayer.ui.components.RelatedBooksView
import com.viel.aplayer.ui.components.SubtitleLine
import com.viel.aplayer.ui.components.SubtitlesView
import com.viel.aplayer.ui.state.BookMetadataState
import com.viel.aplayer.ui.state.PlaybackState
import com.viel.aplayer.ui.state.PlayerSettingsState
import com.viel.aplayer.ui.state.PlayerUiState
import com.viel.aplayer.ui.theme.APlayerTheme
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
    viewModel: com.viel.aplayer.ui.viewmodel.PlayerViewModel,
    actions: PlayerActions,
    navigationActions: PlayerNavigationActions,
    modifier: Modifier = Modifier,
) {
    // 订阅拆分后的状态，实现细粒度更新
    val metadata by viewModel.metadataState.collectAsStateWithLifecycle()
    val playback by viewModel.playbackState.collectAsStateWithLifecycle()
    val settings by viewModel.settingsState.collectAsStateWithLifecycle()
    val controls by viewModel.playbackControlState.collectAsStateWithLifecycle()
    
    val fullUiState by viewModel.uiState.collectAsStateWithLifecycle()

    // 将外部状态转换为枚举
    val targetMode = remember(settings.selectedContentTab) {
        when(settings.selectedContentTab) {
            0 -> PlayerScreenMode.BOOKMARKS
            1 -> PlayerScreenMode.SUBTITLES
            2 -> PlayerScreenMode.RELATED
            else -> PlayerScreenMode.PLAYER
        }
    }

    // 内部状态，初始值直接跟随外部状态
    var currentMode by remember { mutableStateOf(targetMode) }

    // 动画状态：下滑位移
    val scope = rememberCoroutineScope()
    val offsetY = remember { Animatable(0f) }
    val density = LocalDensity.current
    val dismissThreshold = with(density) { 50.dp.toPx() }

    // 获取系统圆角
    val view = LocalView.current
    val systemCornerRadius = remember(view) {
        val insets = view.rootWindowInsets
        val corner = insets?.getRoundedCorner(RoundedCorner.POSITION_TOP_LEFT)
        corner?.radius ?: 0
    }
    val cornerRadiusDp = with(density) { systemCornerRadius.toDp().coerceAtLeast(24.dp) }

    // 当外部状态发生变化（如通过路由跳转）时，同步给内部状态
    LaunchedEffect(targetMode) {
        currentMode = targetMode
    }

    APlayerTheme(darkTheme = true) {
        val focusManager = LocalFocusManager.current
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)

        // 处理物理返回键：非 PLAYER 模式下先返回 PLAYER
        androidx.activity.compose.BackHandler(enabled = currentMode != PlayerScreenMode.PLAYER) {
            currentMode = PlayerScreenMode.PLAYER
        }

        // 当处于 PLAYER 模式且播放器展开时，返回键触发收起
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
                .clip(RoundedCornerShape(topStart = cornerRadiusDp, topEnd = cornerRadiusDp)) // 应用系统圆角
                .background(bgColor) // 1. 先铺设实心底色，防止半透明
                .background(backgroundBrush), // 2. 再叠加原有的渐变
            color = Color.Transparent
        ) {
            Column(
                modifier = Modifier.fillMaxSize()
            ) {
                // 1. App Bar - 始终固定
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

                // 2. Main Content & Controls Area
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
                                        MainCoverView(metadata.coverPath, controls.isPlaying, actions)
                                    }
                                    StablePlaybackControls(metadata, playback, controls, settings, actions, animatedBgColor)
                                }
                                PlayerScreenMode.BOOKMARKS -> {
                                    Box(modifier = Modifier.weight(1f)) {
                                        BookmarkListView(
                                            bookmarks = metadata.bookmarks,
                                            onBookmarkClick = { pos -> actions.playback.onSeek(pos, true) },
                                            onDeleteClick = actions.bookmarks.onDelete,
                                            onUpdateClick = actions.bookmarks.onUpdate,
                                            currentPosition = playback.currentPosition
                                        )
                                    }
                                }
                                PlayerScreenMode.SUBTITLES -> {
                                    Box(modifier = Modifier.weight(1f)) {
                                        SubtitlesView(
                                            subtitles = metadata.subtitles,
                                            currentPosition = playback.currentPosition,
                                            onSeek = { pos -> actions.playback.onSeek(pos, true) }
                                        )
                                    }
                                    StablePlaybackControls(metadata, playback, controls, settings, actions, animatedBgColor)
                                }
                                PlayerScreenMode.RELATED -> {
                                    Box(modifier = Modifier.weight(1f)) {
                                        RelatedBooksView(
                                            currentBookUri = metadata.uri,
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

                    // Undo Seek Button - Overlay
                    androidx.compose.animation.AnimatedVisibility(
                        visible = settings.showUndoSeek,
                        enter = fadeIn(),
                        exit = fadeOut(),
                        modifier = Modifier
                            .align(Alignment.Center)
                            .offset(y = 65.dp)
                    ) {
                        Surface(
                            onClick = actions.playback.onUndoSeek,
                            shape = RoundedCornerShape(20.dp),
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            tonalElevation = 6.dp,
                            shadowElevation = 8.dp
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.History,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Text(
                                    text = "Undo Seek",
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }

                // 4. Navigation Tabs - 始终固定
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

        // Overlays
        ChapterListSheet(
            isVisible = settings.isChapterListVisible,
            chapters = metadata.chapters,
            currentChapter = metadata.chapters.findLast { playback.currentPosition >= it.startPosition } ?: metadata.chapters.firstOrNull(),
            onDismissRequest = actions.content.onDismissChapterList,
            onChapterClick = { pos ->
                actions.playback.onSeek(pos, true)
                actions.content.onDismissChapterList()
            },
            sheetState = sheetState
        )

        BookmarkDialog(
            isVisible = settings.isBookmarkDialogVisible,
            title = settings.bookmarkTitle,
            onTitleChange = actions.bookmarks.onTitleChange,
            onSave = actions.bookmarks.onSave,
            onDismiss = actions.bookmarks.onDismissDialog
        )
    }
}

@Composable
private fun MainCoverView(coverPath: String?, isPlaying: Boolean, actions: PlayerActions) {
    val coverScale by animateFloatAsState(
        targetValue = if (isPlaying) 1f else 0.95f,
        animationSpec = tween(300)
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp, vertical = 32.dp) // 增加固定边距
            .pointerInput(Unit) {
                var offsetX = 0f
                var offsetY = 0f
                var dragDirectionLocked = false // 是否已锁定滑动方向
                var isVerticalDrag = false

                detectDragGestures(
                    onDragStart = {
                        offsetX = 0f
                        offsetY = 0f
                        dragDirectionLocked = false
                        isVerticalDrag = false
                    },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        offsetX += dragAmount.x
                        offsetY += dragAmount.y

                        // 如果方向未锁定，且滑动距离超过阈值（30px），则锁定方向
                        if (!dragDirectionLocked) {
                            if (kotlin.math.abs(offsetY) > 30f) {
                                isVerticalDrag = true
                                dragDirectionLocked = true
                            } else if (kotlin.math.abs(offsetX) > 30f) {
                                isVerticalDrag = false
                                dragDirectionLocked = true
                            }
                        }

                        // 如果已锁定为垂直方向，且总位移超过 50px，才开始调节音量
                        if (dragDirectionLocked && isVerticalDrag && kotlin.math.abs(offsetY) > 150f) {
                            actions.playback.onAdjustVolume(-dragAmount.y * 0.001f)
                        }
                    },
                    onDragEnd = {
                        // 只有锁定为横向滑动且超过 150px 时才触发章节跳转
                        if (dragDirectionLocked && !isVerticalDrag) {
                            if (offsetX < -150f) {
                                actions.playback.onPreviousChapter()
                            } else if (offsetX > 150f) {
                                actions.playback.onNextChapter()
                            }
                        }
                        offsetX = 0f
                        offsetY = 0f
                    },
                    onDragCancel = {
                        offsetX = 0f
                        offsetY = 0f
                    }
                )
            },
        contentAlignment = Alignment.TopCenter // 改为顶部对齐，防止下沉
    ) {
        val coverFile = remember(coverPath) {
            if (coverPath != null) File(coverPath) else null
        }
        Box(
            modifier = Modifier
                .fillMaxWidth() // 基于稳定的宽度
                .aspectRatio(1f)
                .graphicsLayer {
                    scaleX = coverScale
                    scaleY = coverScale
                    transformOrigin = TransformOrigin(0.5f, 0.0f)
                }
                .clip(RoundedCornerShape(24.dp))
        ) {
            if (coverFile != null && coverFile.exists()) {
                AsyncImage(
                    model = coverFile,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant),
                    contentScale = ContentScale.Crop
                )
            } else {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.PlayArrow, null, Modifier.size(80.dp), tint = Color.Gray)
                }
            }
        }
    }
}

@Composable
private fun StablePlaybackControls(
    metadata: BookMetadataState,
    playback: PlaybackState,
    controls: com.viel.aplayer.ui.viewmodel.PlayerViewModel.PlaybackControlState,
    settings: PlayerSettingsState,
    actions: PlayerActions,
    buttonColor: Color
) {
    // 使用 derivedStateOf 隔离高频位置更新带来的影响。
    // 只有当 currentPosition 的变化导致章节对象切换时，currentChapter 才会触发下游重组。
    val currentChapter by remember {
        derivedStateOf {
            metadata.chapters.findLast { playback.currentPosition >= it.startPosition } ?: metadata.chapters.firstOrNull()
        }
    }

    Column(modifier = Modifier.padding(horizontal = 24.dp)) {
        ChapterDisplay(
            currentChapterTitle = currentChapter?.title ?: metadata.title,
            onChapterClick = actions.content.onShowChapterList,
            onBookmarkClick = actions.bookmarks.onShowDialog
        )
        Spacer(Modifier.height(16.dp))
        
        PlaybackProgress(
            currentPosition = playback.currentPosition,
            totalDuration = playback.duration,
            isChapterMode = settings.isChapterProgressMode && currentChapter != null,
            chapterStart = currentChapter?.startPosition ?: 0L,
            chapterEnd = currentChapter?.endPosition ?: 0L,
            markers = metadata.getChapterMarkers(playback.duration),
            currentChapterIndex = metadata.chapters.indexOf(currentChapter),
            chapterCount = metadata.chapters.size,
            onSeek = { pos -> actions.playback.onSeek(pos, true) }
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

@Composable
private fun BottomNavTabs(
    selectedTab: PlayerScreenMode,
    onTabSelected: (PlayerScreenMode) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth().navigationBarsPadding()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
        ) {
            val tabs = listOf(
                "Bookmark" to PlayerScreenMode.BOOKMARKS,
                "Subtitles" to PlayerScreenMode.SUBTITLES,
                "Related" to PlayerScreenMode.RELATED
            )
            val alignments = listOf(TextAlign.Start, TextAlign.Center, TextAlign.End)
            val indicatorWidths = listOf(80.dp, 70.dp, 60.dp)

            // 记录上一个活跃详情页的状态
            var lastActiveTab by remember { mutableStateOf(PlayerScreenMode.SUBTITLES) }
            LaunchedEffect(selectedTab) {
                if (selectedTab != PlayerScreenMode.PLAYER) {
                    lastActiveTab = selectedTab
                }
            }

            val isMainPlayer = selectedTab == PlayerScreenMode.PLAYER
            val indicatorAlpha by animateFloatAsState(
                targetValue = if (isMainPlayer) 0f else 1f,
                animationSpec = tween(300),
                label = "indicator_alpha"
            )

            val indicatorOffset by animateFloatAsState(
                targetValue = lastActiveTab.index.toFloat(),
                animationSpec = if (indicatorAlpha == 0f) snap() else tween(300),
                label = "tab_indicator_offset"
            )

            val currentIndicatorWidth by animateDpAsState(
                targetValue = indicatorWidths[lastActiveTab.index],
                animationSpec = if (indicatorAlpha == 0f) snap() else tween(300),
                label = "tab_indicator_width"
            )

            val activeColor = MaterialTheme.colorScheme.onSurface

            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
            ) {
                val width = size.width
                val tabWidth = width / 3
                val indWidthPx = currentIndicatorWidth.toPx()

                val fluidXPos = if (indicatorOffset <= 1f) {
                    val startX = 0f
                    val endX = (tabWidth / 2) - (indWidthPx / 2) + tabWidth
                    startX + (endX - startX) * indicatorOffset
                } else {
                    val startX = (tabWidth / 2) - (indWidthPx / 2) + tabWidth
                    val endX = width - indWidthPx
                    startX + (endX - startX) * (indicatorOffset - 1f)
                }

                drawRoundRect(
                    color = activeColor.copy(alpha = indicatorAlpha),
                    topLeft = androidx.compose.ui.geometry.Offset(fluidXPos, size.height - 4.dp.toPx()),
                    size = androidx.compose.ui.geometry.Size(indWidthPx, 3.dp.toPx()),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(2.dp.toPx())
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth().height(48.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val interactionSource = remember { MutableInteractionSource() }
                tabs.forEachIndexed { index, (title, mode) ->
                    Text(
                        text = title,
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .clickable(
                                interactionSource = interactionSource,
                                indication = null
                            ) { onTabSelected(mode) },
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = if (selectedTab == mode) FontWeight.Bold else FontWeight.SemiBold,
                            fontSize = 16.sp
                        ),
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (selectedTab == mode) 1f else 0.6f),
                        textAlign = alignments[index]
                    )
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Preview(name = "Player Mode", showBackground = true, apiLevel = 35)
@Composable
fun NewPlayerScreenPlayerPreview() {
    NewPlayerScreenPreviewWrapper(initialTab = -1)
}

@Preview(name = "Bookmarks Mode", showBackground = true, apiLevel = 35)
@Composable
fun NewPlayerScreenBookmarksPreview() {
    NewPlayerScreenPreviewWrapper(initialTab = 0)
}

@Preview(name = "Subtitles Mode", showBackground = true, apiLevel = 35)
@Composable
fun NewPlayerScreenSubtitlesPreview() {
    NewPlayerScreenPreviewWrapper(initialTab = 1)
}

@Preview(name = "Related Mode", showBackground = true, apiLevel = 35)
@Composable
fun NewPlayerScreenRelatedPreview() {
    NewPlayerScreenPreviewWrapper(initialTab = 2)
}


@Composable
private fun NewPlayerScreenPreviewWrapper(initialTab: Int) {
    // 预览模式下无法轻易模拟完整 ViewModel，此处为了编译通过简单 Mock
    // 实际生产中应尽量使用 Interface 或抽象 State
    val sampleBook = AudiobookEntity(
        uri = "uri",
        title = "Another Book",
        author = "John Doe",
        narrator = "Jane Smith"
    )

    APlayerTheme {
        Text("Preview is currently disabled due to ViewModel dependency refactoring")
    }
}
