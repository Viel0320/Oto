package com.viel.aplayer.ui.screens

import android.graphics.BitmapFactory
import androidx.compose.animation.animateColorAsState
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.tooling.preview.Preview
import com.viel.aplayer.R
import androidx.compose.ui.unit.dp
import androidx.palette.graphics.Palette
import coil.compose.AsyncImage
import com.viel.aplayer.data.ChapterEntity
import com.viel.aplayer.ui.components.ChapterDisplay
import com.viel.aplayer.ui.components.ChapterListSheet
import com.viel.aplayer.ui.components.PlaybackControls
import com.viel.aplayer.ui.components.PlaybackProgress
import com.viel.aplayer.ui.components.PlayerAppBar
import com.viel.aplayer.ui.theme.APlayerTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun PlayerScreen(
    onMinimize: () -> Unit,
    modifier: Modifier = Modifier,
    isPlaying: Boolean = false,
    title: String = "Unknown Title",
    author: String = "Unknown Author",
    coverPath: String? = null,
    currentPosition: () -> Long = { 0L },
    duration: () -> Long = { 0L },
    chapters: List<ChapterEntity> = emptyList(),
    onSeek: (Long) -> Unit = {},
    onSkipForward: () -> Unit = {},
    onSkipBackward: () -> Unit = {},
    onPlayPauseClick: () -> Unit = {},
    playbackSpeed: Float = 1.0f,
    onSpeedChange: (Float) -> Unit = {},
    selectedSleepTimer: Int = 0,
    onSleepTimerChange: (Int) -> Unit = {},
    onSubtitlesClick: () -> Unit = {},
    onBookmarksClick: () -> Unit = {},
    onRelatedClick: () -> Unit = {},
    onAddBookmark: (String) -> Unit = {},
) {
    APlayerTheme(darkTheme = true) {
        val backgroundColor = remember { mutableStateOf(Color(0xFF1C1B1F)) }
        val focusManager = LocalFocusManager.current

        val showChapterList = remember { mutableStateOf(false) }
        val showBookmarkDialog = remember { mutableStateOf(false) }
        val sheetState = rememberModalBottomSheetState()

        // Find current chapter using derivedStateOf to minimize recompositions
        val currentChapter by remember(chapters) {
            derivedStateOf {
                chapters.find {
                    (currentPosition() >= it.startPosition) && (currentPosition() < it.endPosition)
                } ?: chapters.firstOrNull { currentPosition() < it.startPosition }
            }
        }

        val animatedBgColor by animateColorAsState(
            targetValue = backgroundColor.value,
            animationSpec = tween(300),
            label = "bg_color"
        )

        LaunchedEffect(coverPath) {
            if (coverPath != null) {
                val file = File(coverPath)
                if (file.exists()) {
                    val bitmap = withContext(Dispatchers.IO) {
                        BitmapFactory.decodeFile(file.absolutePath)
                    }
                    if (bitmap != null) {
                        val palette = withContext(Dispatchers.Default) {
                            Palette.from(bitmap).generate()
                        }
                        backgroundColor.value = Color(palette.getDominantColor(0xFF1C1B1F.toInt()))
                    }
                }
            }
        }

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

        Scaffold(
            modifier = modifier.fillMaxSize(),
            containerColor = MaterialTheme.colorScheme.background,
            topBar = {
                PlayerAppBar(
                    title = title,
                    author = author,
                    onNavigationClick = {
                        focusManager.clearFocus()
                        onMinimize()
                    }
                )
            }
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(backgroundBrush)
                    .padding(padding)
            ) {
                // Player Box (Main Content)
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

                        val coverFile = remember(coverPath) {
                            if (coverPath != null) File(coverPath) else null
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
                        currentChapterTitle = currentChapter?.title,
                        onChapterClick = { showChapterList.value = true },
                        onBookmarkClick = { showBookmarkDialog.value = true }
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    // Progress Bar
                    val chapterMarkers by remember(chapters) {
                        derivedStateOf {
                            val d = duration()
                            if (d > 0) {
                                chapters.map { it.startPosition.toFloat() / d.toFloat() }
                            } else emptyList()
                        }
                    }

                    PlaybackProgress(
                        currentPosition = currentPosition,
                        duration = duration,
                        markers = chapterMarkers,
                        onSeek = onSeek,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.weight(1.0f))

                    // Playback Controls
                    PlaybackControls(
                        isPlaying = isPlaying,
                        playbackSpeed = playbackSpeed,
                        selectedSleepTimer = selectedSleepTimer,
                        onPlayPauseClick = onPlayPauseClick,
                        onSkipForward = onSkipForward,
                        onSkipBackward = onSkipBackward,
                        onSpeedChange = onSpeedChange,
                        onSleepTimerChange = onSleepTimerChange
                    )

                    Spacer(modifier = Modifier.height(24.dp))
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Additional Bottom Options Placeholder
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp)
                        .padding(bottom = 32.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { onBookmarksClick() },
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
                            .clickable { onSubtitlesClick() },
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
                            .clickable { onRelatedClick() },
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

            ChapterListSheet(
                isVisible = showChapterList.value,
                chapters = chapters,
                currentChapter = currentChapter,
                onDismissRequest = { showChapterList.value = false },
                onChapterClick = { pos ->
                    onSeek(pos)
                    showChapterList.value = false
                },
                sheetState = sheetState
            )

            if (showBookmarkDialog.value) {
                var bookmarkTitle by remember { mutableStateOf("") }
                AlertDialog(
                    onDismissRequest = { showBookmarkDialog.value = false },
                    title = { Text("Add Bookmark") },
                    text = {
                        OutlinedTextField(
                            value = bookmarkTitle,
                            onValueChange = { bookmarkTitle = it },
                            label = { Text("Bookmark Title") },
                            placeholder = { Text("Enter a name for this bookmark") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                val finalTitle = bookmarkTitle.ifBlank { "Bookmark" }
                                onAddBookmark(finalTitle)
                                showBookmarkDialog.value = false
                            }
                        ) {
                            Text("Save")
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showBookmarkDialog.value = false }) {
                            Text("Cancel")
                        }
                    }
                )
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun PlayerScreenPreview() {
    APlayerTheme {
        PlayerScreen(
            onMinimize = {},
            title = "イン・ザ-メガチャーチ",
            author = "朝井 リョウ",
            currentPosition = { 1200000L },
            duration = { 3600000L }
        )
    }
}
