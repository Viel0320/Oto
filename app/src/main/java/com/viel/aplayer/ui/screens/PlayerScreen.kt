package com.viel.aplayer.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
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
import com.viel.aplayer.ui.components.ChapterDisplay
import com.viel.aplayer.ui.components.ChapterListSheet
import com.viel.aplayer.ui.components.PlaybackControls
import com.viel.aplayer.ui.components.PlaybackProgress
import com.viel.aplayer.ui.components.PlayerAppBar
import com.viel.aplayer.ui.state.PlayerUiState
import com.viel.aplayer.ui.theme.APlayerTheme
import java.io.File

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun PlayerScreen(
    uiState: PlayerUiState,
    actions: PlayerActions,
    navigationActions: PlayerNavigationActions,
    modifier: Modifier = Modifier,
) {
    APlayerTheme(darkTheme = true) {
        val focusManager = LocalFocusManager.current
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)

        val animatedBgColor by animateColorAsState(
            targetValue = Color(uiState.backgroundColorArgb),
            animationSpec = tween(300),
            label = "bg_color"
        )

        val coverScale by animateFloatAsState(
            targetValue = if (uiState.playWhenReady) 1f else 0.95f,
            animationSpec = tween(durationMillis = 300),
            label = "cover_scale"
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
            modifier = modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(backgroundBrush)
                ) {
                    PlayerAppBar(
                        title = uiState.currentTitle,
                        author = uiState.currentAuthor,
                        narrator = uiState.currentNarrator,
                        onNavigationClick = {
                            focusManager.clearFocus()
                            navigationActions.onMinimize()
                        },
                        onToggleProgressMode = actions.onToggleProgressMode,
                        isChapterProgressMode = uiState.isChapterProgressMode
                    )

                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .padding(vertical = 12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                    // Large Cover Art
                    var totalHorizontalDrag by remember { mutableFloatStateOf(0f) }
                    var hasTriggeredHorizontalDrag by remember { mutableStateOf(false) }
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp)
                            .pointerInput(Unit) {
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
                                            // Vertical drag for volume
                                            actions.onAdjustVolume(-dragAmount.y * 0.002f)
                                        } else if (!hasTriggeredHorizontalDrag) {
                                            // Horizontal drag for chapters with debounce
                                            totalHorizontalDrag += dragAmount.x
                                            if (kotlin.math.abs(totalHorizontalDrag) > 300f) {
                                                if (totalHorizontalDrag > 0) {
                                                    actions.onNextChapter()
                                                } else {
                                                    actions.onPreviousChapter()
                                                }
                                                hasTriggeredHorizontalDrag = true
                                            }
                                        }
                                    }
                                )
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        val coverShape = RoundedCornerShape(24.dp)
                        val coverModifier = Modifier
                            .fillMaxHeight()
                            .aspectRatio(1f)
                            .graphicsLayer {
                                scaleX = coverScale
                                scaleY = coverScale
                                transformOrigin = TransformOrigin(0.5f, 0.0f)
                            }

                        val coverFile = remember(uiState.currentCoverPath) {
                            if (uiState.currentCoverPath != null) File(uiState.currentCoverPath) else null
                        }

                        Box(
                            modifier = coverModifier.clip(coverShape),
                            contentAlignment = Alignment.Center
                        ) {
                            if (coverFile != null && coverFile.exists()) {
                                AsyncImage(
                                    model = coverFile,
                                    contentDescription = "Cover Art",
                                    modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant),
                                    contentScale = ContentScale.Crop
                                )
                            } else {
                                Box(
                                    modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        painterResource(R.drawable.ic_rounded_play_arrow),
                                        contentDescription = null,
                                        modifier = Modifier.size(80.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f)
                                    )
                                }
                            }
                        }
                    }

                    // Chapter Display
                    ChapterDisplay(
                        currentChapterTitle = uiState.currentChapter?.title ?: uiState.currentTitle,
                        onChapterClick = actions.onShowChapterList,
                        onBookmarkClick = actions.onShowBookmarkDialog,
                        modifier = Modifier.padding(horizontal = 24.dp)
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
                            emptyList()
                        )
                    } else {
                        Triple(uiState.currentPosition, uiState.duration, uiState.chapterMarkers)
                    }

                    PlaybackProgress(
                        currentPosition = displayPos,
                        duration = displayDur,
                        markers = displayMarkers,
                        currentChapterIndex = uiState.currentChapters.indexOf(uiState.currentChapter),
                        chapterCount = uiState.currentChapters.size,
                        onSeek = { relPos ->
                            val targetPos = if (isChapterMode) {
                                currentChapter.startPosition + relPos
                            } else {
                                relPos
                            }
                            actions.onSeek(targetPos, true)
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

                    Spacer(modifier = Modifier.height(12.dp))
                }

                // Bottom Navigation Tabs
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp)
                            .height(48.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val tabTitles = listOf("Bookmark", "Subtitles", "Related")
                        val alignments = listOf(TextAlign.Start, TextAlign.Center, TextAlign.End)
                        val textColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)

                        tabTitles.forEachIndexed { index, title ->
                            Text(
                                text = title,
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable {
                                        when (index) {
                                            0 -> navigationActions.onBookmarksClick()
                                            1 -> navigationActions.onSubtitlesClick()
                                            2 -> navigationActions.onRelatedClick()
                                        }
                                    },
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 16.sp
                                ),
                                color = textColor,
                                textAlign = alignments[index]
                            )
                        }
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
                    actions.seek(pos)
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

@Preview(showBackground = true, apiLevel = 36)
@Composable
fun PlayerScreenPreview() {
    APlayerTheme {
        PlayerScreen(
            uiState = PlayerUiState(
                currentTitle = "Preview Title",
                currentPosition = 1200000L,
                duration = 3600000L,
                currentChapters = listOf(
                    com.viel.aplayer.data.ChapterEntity(bookUri = "", title = "Chapter 1", startPosition = 0L, endPosition = 1000000L),
                    com.viel.aplayer.data.ChapterEntity(bookUri = "", title = "Chapter 2", startPosition = 1000000L, endPosition = 2000000L),
                    com.viel.aplayer.data.ChapterEntity(bookUri = "", title = "Chapter 3", startPosition = 2000000L, endPosition = 3600000L)
                )
            ),
            actions = PlayerActions(),
            navigationActions = PlayerNavigationActions()
        )
    }
}
