package com.viel.aplayer.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.viel.aplayer.data.BookmarkEntity
import com.viel.aplayer.data.ChapterEntity
import com.viel.aplayer.ui.action.PlayerActions
import com.viel.aplayer.ui.action.PlayerNavigationActions
import com.viel.aplayer.ui.components.BookmarkDialog
import com.viel.aplayer.ui.components.BookmarkListView
import com.viel.aplayer.ui.components.ChapterDisplay
import com.viel.aplayer.ui.components.ChapterListSheet
import com.viel.aplayer.ui.components.PlaybackControls
import com.viel.aplayer.ui.components.PlaybackProgress
import com.viel.aplayer.ui.components.PlayerAppBar
import com.viel.aplayer.ui.components.SubtitleLine
import com.viel.aplayer.ui.components.SubtitlesView
import com.viel.aplayer.ui.state.PlayerUiState
import com.viel.aplayer.ui.theme.APlayerTheme
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerContentScreen(
    uiState: PlayerUiState,
    actions: PlayerActions,
    navigationActions: PlayerNavigationActions,
    modifier: Modifier = Modifier,
) {
    APlayerTheme(darkTheme = true) {
        val focusManager = LocalFocusManager.current
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
        
        // Delay rendering to ensure smooth transition
        val isPreview = LocalInspectionMode.current
        var isReady by remember { mutableStateOf(isPreview) }
        LaunchedEffect(Unit) {
            if (!isPreview) {
                delay(400) // Match the transition duration
                isReady = true
            }
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
                        animatedBgColor.copy(alpha = 0.6f),
                        bgColor
                    )
                )
            }
        }

        Surface(
            modifier = modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(backgroundBrush)
                ) {
                    // App Bar
                    PlayerAppBar(
                        title = uiState.currentTitle,
                        author = uiState.currentAuthor,
                        narrator = uiState.currentNarrator,
                        onNavigationClick = {
                            focusManager.clearFocus()
                            navigationActions.onClose()
                        },
                        onToggleProgressMode = actions.onToggleProgressMode,
                        isChapterProgressMode = uiState.isChapterProgressMode
                    )

                    // Content Area (Middle)
                    Box(modifier = Modifier.weight(1f)) {
                        // Main Tab Content
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(vertical = 12.dp)
                        ) {
                            when (uiState.selectedContentTab) {
                                0 -> {
                                    BookmarkListView(
                                        bookmarks = uiState.currentBookmarks,
                                        onBookmarkClick = { pos -> 
                                            actions.seekWithUndo(pos)
                                            if (!uiState.isPlaying) actions.onPlayPauseClick()
                                        },
                                        onDeleteClick = actions.onDeleteBookmark,
                                        currentPosition = uiState.currentPosition
                                    )
                                }
                                1 -> {
                                    if (isReady) {
                                        SubtitlesView(
                                            subtitles = uiState.currentSubtitles,
                                            currentPosition = uiState.currentPosition,
                                            onSeek = { pos -> actions.seekWithUndo(pos) }
                                        )
                                    } else {
                                        Box(modifier = Modifier.fillMaxSize())
                                    }
                                }
                                2 -> {
                                    Box(
                                        modifier = Modifier.fillMaxSize(),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = "Related Audiobooks",
                                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(
                                                alpha = 0.6f
                                            ),
                                            style = MaterialTheme.typography.titleMedium
                                        )
                                    }
                                }
                            }
                        }

                        // Undo Seek Banner - Overlay at the top of the content area
                        androidx.compose.animation.AnimatedVisibility(
                            visible = uiState.showUndoSeek,
                            enter = fadeIn() + expandVertically(),
                            exit = fadeOut() + shrinkVertically(),
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .padding(top = 24.dp) // Exactly 24dp below the AppBar
                                .padding(horizontal = 24.dp)
                        ) {
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(56.dp),
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.secondaryContainer,
                                tonalElevation = 4.dp,
                                shadowElevation = 8.dp
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(horizontal = 16.dp),
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

                    if (uiState.selectedContentTab == 1) {
                        // Chapter Display
                        ChapterDisplay(
                            currentChapterTitle = uiState.currentChapter?.title ?: uiState.currentTitle,
                            onChapterClick = actions.onShowChapterList,
                            onBookmarkClick = actions.onShowBookmarkDialog,
                            modifier = Modifier.padding(horizontal = 24.dp),
                            title = uiState.currentTitle
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        val currentChapter = uiState.currentChapter
                        val isChapterMode = uiState.isChapterProgressMode && currentChapter != null

                        val (displayPos, displayDur, displayMarkers) = if (isChapterMode) {
                            val start = currentChapter.startPosition
                            val end = currentChapter.endPosition
                            Triple(
                                (uiState.currentPosition - start).coerceAtLeast(0),
                                (end - start).coerceAtLeast(1),
                                emptyList<Float>()
                            )
                        } else {
                            Triple(uiState.currentPosition, uiState.duration, uiState.chapterMarkers)
                        }

                        PlaybackProgress(
                            currentPosition = displayPos,
                            duration = displayDur,
                            markers = displayMarkers,
                            onSeek = { relPos ->
                                val targetPos = if (isChapterMode) {
                                    currentChapter.startPosition + relPos
                                } else {
                                    relPos
                                }
                                actions.seek(targetPos)
                                if (!uiState.isPlaying) actions.onPlayPauseClick()
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 24.dp)
                        )

                        Spacer(modifier = Modifier.height(24.dp))

                        // Playback Controls
                        PlaybackControls(
                            isPlaying = uiState.isPlaying,
                            playbackSpeed = uiState.playbackSpeed,
                            selectedSleepTimer = uiState.selectedSleepTimer,
                            isSpeedManualMode = uiState.isSpeedManualMode,
                            actions = actions.playbackControls,
                            modifier = Modifier.padding(horizontal = 24.dp),
                            buttonColor = animatedBgColor
                        )

                        Spacer(modifier = Modifier.height(24.dp))
                    }

                    // Bottom Tabs
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .navigationBarsPadding()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .padding(horizontal = 24.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            val interactionSource = remember { MutableInteractionSource() }
                            Text(
                                text = "Bookmark",
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable(
                                        interactionSource = interactionSource,
                                        indication = null
                                    ) { actions.onSelectedContentTabChange(0) },
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 16.sp
                                ),
                                color = if (uiState.selectedContentTab == 0) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                                textAlign = TextAlign.Start
                            )
                            Text(
                                text = "Subtitles",
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable(
                                        interactionSource = interactionSource,
                                        indication = null
                                    ) { actions.onSelectedContentTabChange(1) },
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 16.sp
                                ),
                                color = if (uiState.selectedContentTab == 1) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                                textAlign = TextAlign.Center
                            )
                            Text(
                                text = "Related",
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable(
                                        interactionSource = interactionSource,
                                        indication = null
                                    ) { actions.onSelectedContentTabChange(2) },
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 16.sp
                                ),
                                color = if (uiState.selectedContentTab == 2) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                                textAlign = TextAlign.End
                            )
                        }
                        Spacer(modifier = Modifier.height(24.dp))
                    }
                }
            }

            ChapterListSheet(
                isVisible = uiState.isChapterListVisible,
                chapters = uiState.currentChapters,
                currentChapter = uiState.currentChapter,
                onDismissRequest = actions.onDismissChapterList,
                onChapterClick = { pos ->
                    actions.seekWithUndo(pos)
                    if (!uiState.isPlaying) actions.onPlayPauseClick()
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
}

@Preview(name = "Subtitles Tab", showBackground = true, apiLevel = 36)
@Composable
fun PlayerContentScreenSubtitlesPreview() {
    PlayerContentScreenWrapper(initialTab = 1)
}

@Preview(name = "Bookmarks Tab", showBackground = true, apiLevel = 36)
@Composable
fun PlayerContentScreenBookmarksPreview() {
    PlayerContentScreenWrapper(initialTab = 0)
}

@Preview(name = "Related Tab", showBackground = true, apiLevel = 36)
@Composable
fun PlayerContentScreenRelatedPreview() {
    PlayerContentScreenWrapper(initialTab = 2)
}

@Composable
private fun PlayerContentScreenWrapper(initialTab: Int) {
    val mockSubtitles = listOf(
        SubtitleLine(0, 5000, "Opening line"),
        SubtitleLine(5000, 10000, "Current subtitle line"),
        SubtitleLine(10000, 15000, "Tap a subtitle to seek"),
        SubtitleLine(15000, 20000, "Auto scroll preview")
    )
    val mockBookmarks = listOf(
        BookmarkEntity(1, "uri", 30000L, "Important moment"),
        BookmarkEntity(2, "uri", 600000L, "Good dialogue"),
        BookmarkEntity(3, "uri", 1200000L, "Ending hint")
    )
    val mockChapters = listOf(
        ChapterEntity(1, "uri", "Chapter 1", 0L, 300000L),
        ChapterEntity(2, "uri", "Chapter 2", 300000L, 900000L),
        ChapterEntity(3, "uri", "Chapter 3", 900000L, 1800000L),
        ChapterEntity(4, "uri", "Chapter 4", 1800000L, 3600000L)
    )

    APlayerTheme {
        PlayerContentScreen(
            uiState = PlayerUiState(
                currentTitle = "Preview Book",
                currentAuthor = "Preview Author",
                currentNarrator = "Preview Narrator",
                currentPosition = 18000L,
                duration = 3600000L,
                currentSubtitles = mockSubtitles,
                currentBookmarks = mockBookmarks,
                currentChapters = mockChapters,
                showUndoSeek = true,
                selectedContentTab = initialTab
            ),
            actions = PlayerActions(),
            navigationActions = PlayerNavigationActions()
        )
    }
}
