package com.viel.aplayer.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.tooling.preview.Preview
import com.viel.aplayer.R
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.viel.aplayer.ui.components.ChapterDisplay
import com.viel.aplayer.ui.components.ChapterListSheet
import com.viel.aplayer.ui.components.PlaybackControls
import com.viel.aplayer.ui.components.PlaybackProgress
import com.viel.aplayer.ui.components.PlayerAppBar
import com.viel.aplayer.ui.action.PlayerActions
import com.viel.aplayer.ui.action.PlayerNavigationActions
import com.viel.aplayer.ui.theme.APlayerTheme
import com.viel.aplayer.ui.state.PlayerUiState
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
                        }
                    )

                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                    // Large Cover Art
                    Box(
                        modifier = Modifier
                            .weight(10f)
                            .fillMaxWidth(),
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

                    Spacer(modifier = Modifier.weight(1f))

                    // Chapter Display
                    ChapterDisplay(
                        currentChapterTitle = uiState.currentChapter?.title ?: uiState.currentTitle,
                        onChapterClick = actions.onShowChapterList,
                        onBookmarkClick = actions.onShowBookmarkDialog
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    PlaybackProgress(
                        currentPosition = uiState.currentPosition,
                        duration = uiState.duration,
                        markers = uiState.chapterMarkers,
                        onSeek = { pos ->
                            actions.seek(pos)
                            if (!uiState.isPlaying) actions.onPlayPauseClick()
                        },
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.weight(1.0f))

                    // Playback Controls
                    PlaybackControls(
                        isPlaying = uiState.isPlaying,
                        playbackSpeed = uiState.playbackSpeed,
                        selectedSleepTimer = uiState.selectedSleepTimer,
                        isSpeedManualMode = uiState.isSpeedManualMode,
                        actions = actions.playbackControls
                    )

                    Spacer(modifier = Modifier.height(24.dp))
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Additional Bottom Options Placeholder
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 24.dp)
                        .padding(bottom = 32.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { navigationActions.onBookmarksClick() },
                        contentAlignment = Alignment.CenterStart
                    ) {
                        Text(
                            text = "Bookmarks",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                        )
                    }
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { navigationActions.onSubtitlesClick() },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "Subtitles",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                        )
                    }
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { navigationActions.onRelatedClick() },
                        contentAlignment = Alignment.CenterEnd
                    ) {
                        Text(
                            text = "Related",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                        )
                    }
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
                    actions.onDismissChapterList()
                },
                sheetState = sheetState
            )

            if (uiState.isBookmarkDialogVisible) {
                AlertDialog(
                    onDismissRequest = actions.onDismissBookmarkDialog,
                    title = { Text("Add Bookmark") },
                    text = {
                        OutlinedTextField(
                            value = uiState.bookmarkTitle,
                            onValueChange = actions.onBookmarkTitleChange,
                            label = { Text("Bookmark Title") },
                            placeholder = { Text("Enter a name for this bookmark") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    },
                    confirmButton = {
                        TextButton(
                            onClick = actions.onSaveBookmark
                        ) {
                            Text("Save")
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = actions.onDismissBookmarkDialog) {
                            Text("Cancel")
                        }
                    }
                )
            }
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
                duration = 3600000L
            ),
            actions = PlayerActions(),
            navigationActions = PlayerNavigationActions()
        )
    }
}
