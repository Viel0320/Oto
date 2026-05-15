package com.viel.aplayer.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.viel.aplayer.R
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
import com.viel.aplayer.ui.state.PlayerUiState
import com.viel.aplayer.ui.theme.APlayerTheme
import java.io.File

enum class PlayerScreenMode(val index: Int) {
    PLAYER(-1),
    BOOKMARKS(0),
    SUBTITLES(1),
    RELATED(2)
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun NewPlayerScreen(
    uiState: PlayerUiState,
    actions: PlayerActions,
    navigationActions: PlayerNavigationActions,
    modifier: Modifier = Modifier,
) {
    // 内部状态管理当前模式
    
    // 动画防抖：动画播完前禁止交互
    var isReady by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(400) // 匹配 MainActivity 中的 tween(400)
        isReady = true
    }
    
    // 将外部状态转换为枚举
    val targetMode = remember(uiState.selectedContentTab) {
        when(uiState.selectedContentTab) {
            0 -> PlayerScreenMode.BOOKMARKS
            1 -> PlayerScreenMode.SUBTITLES
            2 -> PlayerScreenMode.RELATED
            else -> PlayerScreenMode.PLAYER
        }
    }

    // 内部状态，初始值直接跟随外部状态
    var currentMode by remember { mutableStateOf(targetMode) }
    
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

        val animatedBgColor by animateColorAsState(
            targetValue = Color(uiState.backgroundColorArgb),
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
                .pointerInput(isReady) {
                    if (!isReady) {
                        awaitPointerEventScope {
                            while (true) {
                                // 动画期间消费所有触摸事件，防止抖动和误操作
                                awaitPointerEvent().changes.forEach { it.consume() }
                            }
                        }
                    }
                },
            color = MaterialTheme.colorScheme.background
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(backgroundBrush)
            ) {
                // 1. App Bar - 始终固定
                PlayerAppBar(
                    title = uiState.currentTitle,
                    author = uiState.currentAuthor,
                    narrator = uiState.currentNarrator,
                    onNavigationClick = {
                        focusManager.clearFocus()
                        if (currentMode == PlayerScreenMode.PLAYER) {
                            navigationActions.onMinimize()
                        } else {
                            currentMode = PlayerScreenMode.PLAYER
                        }
                    },
                    onToggleProgressMode = actions.onToggleProgressMode,
                    isChapterProgressMode = uiState.isChapterProgressMode
                )

                // 2. Middle Content Area - 状态切换
                Box(modifier = Modifier.weight(1f)) {
                    AnimatedContent(
                        targetState = currentMode,
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
                        when (mode) {
                            PlayerScreenMode.PLAYER -> {
                                MainCoverView(uiState, actions)
                            }
                            PlayerScreenMode.BOOKMARKS -> {
                                BookmarkListView(
                                    bookmarks = uiState.currentBookmarks,
                                    onBookmarkClick = { pos -> actions.onSeek(pos, true) },
                                    onDeleteClick = actions.onDeleteBookmark,
                                    onUpdateClick = actions.onUpdateBookmark,
                                    currentPosition = uiState.currentPosition
                                )
                            }
                            PlayerScreenMode.SUBTITLES -> {
                                SubtitlesView(
                                    subtitles = uiState.currentSubtitles,
                                    currentPosition = uiState.currentPosition,
                                    onSeek = { pos -> actions.onSeek(pos, true) }
                                )
                            }
                            PlayerScreenMode.RELATED -> {
                                RelatedBooksView(
                                    author = uiState.currentAuthor,
                                    narrator = uiState.currentNarrator,
                                    authorBooks = uiState.relatedAuthorBooks,
                                    narratorBooks = uiState.relatedNarratorBooks,
                                    recentBooks = uiState.recentlyAddedBooks,
                                    onBookClick = actions.onLoadRelatedBook
                                )
                            }
                        }
                    }

                    // Undo Seek Banner - Overlay
                    androidx.compose.animation.AnimatedVisibility(
                        visible = uiState.showUndoSeek,
                        enter = fadeIn() + androidx.compose.animation.expandVertically(),
                        exit = fadeOut() + androidx.compose.animation.shrinkVertically(),
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = 24.dp)
                            .padding(horizontal = 24.dp)
                    ) {
                        Surface(
                            modifier = Modifier.fillMaxWidth().height(56.dp),
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            tonalElevation = 4.dp,
                            shadowElevation = 8.dp
                        ) {
                            Row(
                                modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "Jumped to new position",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer
                                )
                                TextButton(onClick = actions.onUndoSeek) {
                                    Text("UNDO", fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }

                // 3. Stable Bottom Controls - 位置保持不变
                val controlsVisible = currentMode == PlayerScreenMode.PLAYER || currentMode == PlayerScreenMode.SUBTITLES
                androidx.compose.animation.AnimatedVisibility(
                    visible = controlsVisible,
                    enter = androidx.compose.animation.expandVertically(tween(300)) + fadeIn(),
                    exit = androidx.compose.animation.shrinkVertically(tween(300)) + fadeOut(),
                    label = "controls_visibility"
                ) {
                    StablePlaybackControls(uiState, actions, animatedBgColor)
                }

                // 4. Navigation Tabs - 始终固定
                BottomNavTabs(
                    selectedTab = currentMode,
                    onTabSelected = { 
                        currentMode = if (currentMode == it) PlayerScreenMode.PLAYER else it
                    }
                )
            }
        }

        // Overlays
        ChapterListSheet(
            isVisible = uiState.isChapterListVisible,
            chapters = uiState.currentChapters,
            currentChapter = uiState.currentChapter,
            onDismissRequest = actions.onDismissChapterList,
            onChapterClick = { pos ->
                actions.onSeek(pos, true)
                actions.onDismissChapterList()
            },
            sheetState = sheetState
        )

        BookmarkDialog(
            isVisible = uiState.isBookmarkDialogVisible,
            title = uiState.bookmarkTitle,
            onTitleChange = actions.onBookmarkTitleChange,
            onSave = actions.onSaveBookmark,
            onDismiss = actions.onDismissBookmarkDialog
        )
    }
}

@Composable
private fun MainCoverView(uiState: PlayerUiState, actions: PlayerActions) {
    val coverScale by animateFloatAsState(
        targetValue = if (uiState.playWhenReady) 1f else 0.95f,
        animationSpec = tween(300)
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp, vertical = 32.dp) // 增加固定边距
            .pointerInput(Unit) {
                detectDragGestures(
                    onDrag = { change, dragAmount ->
                        change.consume()
                        if (kotlin.math.abs(dragAmount.y) > kotlin.math.abs(dragAmount.x)) {
                            actions.onAdjustVolume(-dragAmount.y * 0.002f)
                        }
                    }
                )
            },
        contentAlignment = Alignment.TopCenter // 改为顶部对齐，防止下沉
    ) {
        val coverFile = remember(uiState.currentCoverPath) {
            if (uiState.currentCoverPath != null) File(uiState.currentCoverPath) else null
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
                    Icon(painterResource(R.drawable.ic_rounded_play_arrow), null, Modifier.size(80.dp), tint = Color.Gray)
                }
            }
        }
    }
}

@Composable
private fun StablePlaybackControls(uiState: PlayerUiState, actions: PlayerActions, buttonColor: Color) {
    Column(modifier = Modifier.padding(horizontal = 24.dp)) {
        ChapterDisplay(
            currentChapterTitle = uiState.currentChapter?.title ?: uiState.currentTitle,
            onChapterClick = actions.onShowChapterList,
            onBookmarkClick = actions.onShowBookmarkDialog
        )
        Spacer(Modifier.height(16.dp))
        
        val currentChapter = uiState.currentChapter
        val isChapterMode = uiState.isChapterProgressMode && currentChapter != null
        val displayPos = if (isChapterMode) (uiState.currentPosition - currentChapter.startPosition).coerceAtLeast(0) else uiState.currentPosition
        val displayDur = if (isChapterMode) (currentChapter.endPosition - currentChapter.startPosition).coerceAtLeast(1) else uiState.duration

        PlaybackProgress(
            currentPosition = displayPos,
            duration = displayDur,
            markers = if (isChapterMode) emptyList() else uiState.chapterMarkers,
            currentChapterIndex = uiState.currentChapters.indexOf(uiState.currentChapter),
            chapterCount = uiState.currentChapters.size,
            onSeek = { relPos -> actions.onSeek(if (isChapterMode) currentChapter.startPosition + relPos else relPos, true) }
        )
        Spacer(Modifier.height(24.dp))
        PlaybackControls(
            isPlaying = uiState.isPlaying,
            playbackSpeed = uiState.playbackSpeed,
            selectedSleepTimer = uiState.selectedSleepTimer,
            isSpeedManualMode = uiState.isSpeedManualMode,
            actions = actions.playbackControls,
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

@Preview(name = "Player Mode", showBackground = true, apiLevel = 36)
@Composable
fun NewPlayerScreenPlayerPreview() {
    NewPlayerScreenPreviewWrapper(initialTab = -1)
}

@Preview(name = "Bookmarks Mode", showBackground = true, apiLevel = 36)
@Composable
fun NewPlayerScreenBookmarksPreview() {
    NewPlayerScreenPreviewWrapper(initialTab = 0)
}

@Preview(name = "Subtitles Mode", showBackground = true, apiLevel = 36)
@Composable
fun NewPlayerScreenSubtitlesPreview() {
    NewPlayerScreenPreviewWrapper(initialTab = 1)
}

@Preview(name = "Related Mode", showBackground = true, apiLevel = 36)
@Composable
fun NewPlayerScreenRelatedPreview() {
    NewPlayerScreenPreviewWrapper(initialTab = 2)
}

@Composable
private fun NewPlayerScreenPreviewWrapper(initialTab: Int) {
    APlayerTheme {
        NewPlayerScreen(
            uiState = PlayerUiState(
                currentTitle = "The Great Adventure",
                currentAuthor = "John Doe",
                currentNarrator = "Jane Smith",
                currentPosition = 300000L,
                duration = 3600000L,
                selectedContentTab = initialTab,
                currentChapters = listOf(
                    com.viel.aplayer.data.ChapterEntity(1, "", "Chapter 1", 0L, 1000000L),
                    com.viel.aplayer.data.ChapterEntity(2, "", "Chapter 2", 1000000L, 2000000L)
                ),
                currentBookmarks = listOf(
                    com.viel.aplayer.data.BookmarkEntity(1, "", 500000L, "Interesting part")
                ),
                currentSubtitles = listOf(
                    SubtitleLine(0, 5000, "Once upon a time..."),
                    SubtitleLine(5000, 10000, "In a land far, far away.")
                )
            ),
            actions = PlayerActions(),
            navigationActions = PlayerNavigationActions()
        )
    }
}
