package com.viel.aplayer.ui.screens

import android.graphics.BitmapFactory
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
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
import androidx.palette.graphics.Palette
import com.viel.aplayer.data.BookmarkEntity
import com.viel.aplayer.data.ChapterEntity
import com.viel.aplayer.ui.components.BookmarkListView
import com.viel.aplayer.ui.components.ChapterDisplay
import com.viel.aplayer.ui.components.ChapterListSheet
import com.viel.aplayer.ui.components.PlaybackControls
import com.viel.aplayer.ui.components.PlaybackProgress
import com.viel.aplayer.ui.components.PlayerAppBar
import com.viel.aplayer.ui.components.SubtitleLine
import com.viel.aplayer.ui.components.SubtitlesView
import com.viel.aplayer.ui.theme.APlayerTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerContentScreen(
    title: String,
    author: String,
    narrator: String,
    currentPosition: Long,
    duration: Long,
    isPlaying: Boolean,
    playbackSpeed: Float,
    selectedSleepTimer: Int,
    subtitles: List<SubtitleLine>,
    bookmarks: List<BookmarkEntity>,
    chapters: List<ChapterEntity>,
    showUndoSeek: Boolean,
    onSeek: (Long, Boolean) -> Unit,
    onUndoSeek: () -> Unit,
    onDeleteBookmark: (BookmarkEntity) -> Unit,
    onAddBookmark: (String) -> Unit,
    onPlayPauseClick: () -> Unit,
    onSkipForward: () -> Unit,
    onSkipBackward: () -> Unit,
    onSpeedChange: (Float) -> Unit,
    onSleepTimerChange: (Int) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    initialTab: Int = 1, // 0: Bookmark, 1: Subtitles, 2: Related
    coverPath: String? = null
) {
    APlayerTheme(darkTheme = true) {
        var selectedTab by rememberSaveable { mutableIntStateOf(initialTab) }
        val focusManager = LocalFocusManager.current
        val showChapterList = remember { mutableStateOf(false) }
        val showBookmarkDialog = remember { mutableStateOf(false) }
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
        val backgroundColor = remember { mutableStateOf(Color(0xFF1C1B1F)) }
        
        // Delay rendering to ensure smooth transition
        val isPreview = LocalInspectionMode.current
        var isReady by remember { mutableStateOf(isPreview) }
        LaunchedEffect(Unit) {
            if (!isPreview) {
                delay(400) // Match the transition duration
                isReady = true
            }
        }

        // Find current chapter
        val currentChapter by remember(chapters, currentPosition) {
            derivedStateOf {
                chapters.find {
                    (currentPosition >= it.startPosition) && (currentPosition < it.endPosition)
                } ?: chapters.firstOrNull { currentPosition < it.startPosition }
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
                        title = title,
                        author = author,
                        narrator = narrator,
                        onNavigationClick = {
                            focusManager.clearFocus()
                            onClose()
                        }
                    )

                    // Content Area (Middle)
                    Box(modifier = Modifier.weight(1f)) {
                        // Main Tab Content
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(vertical = 12.dp)
                        ) {
                            when (selectedTab) {
                                0 -> {
                                    BookmarkListView(
                                        bookmarks = bookmarks,
                                        onBookmarkClick = { pos -> onSeek(pos, true) },
                                        onDeleteClick = onDeleteBookmark,
                                        currentPosition = currentPosition
                                    )
                                }
                                1 -> {
                                    if (isReady) {
                                        SubtitlesView(
                                            subtitles = subtitles,
                                            currentPosition = currentPosition,
                                            onSeek = { pos -> onSeek(pos, true) }
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
                                            text = "Related Audiobooks (TODO)",
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
                            visible = showUndoSeek,
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
                                    TextButton(onClick = onUndoSeek) {
                                        Text("UNDO", fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }
                    }

                    if (selectedTab == 1) {
                        // Chapter Display
                        ChapterDisplay(
                            currentChapterTitle = currentChapter?.title ?: title,
                            onChapterClick = { showChapterList.value = true },
                            onBookmarkClick = { showBookmarkDialog.value = true },
                            modifier = Modifier.padding(horizontal = 24.dp),
                            title = title
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        // Progress Bar
                        val chapterMarkers by remember(chapters, duration) {
                            derivedStateOf {
                                if (duration > 0) {
                                    chapters.map { it.startPosition.toFloat() / duration.toFloat() }
                                } else emptyList()
                            }
                        }

                        PlaybackProgress(
                            currentPosition = { currentPosition },
                            duration = { duration },
                            markers = chapterMarkers,
                            onSeek = { pos ->
                                onSeek(pos, false)
                                if (!isPlaying) onPlayPauseClick()
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 24.dp)
                        )

                        Spacer(modifier = Modifier.height(24.dp))

                        // Playback Controls
                        PlaybackControls(
                            isPlaying = isPlaying,
                            playbackSpeed = playbackSpeed,
                            selectedSleepTimer = selectedSleepTimer,
                            onPlayPauseClick = onPlayPauseClick,
                            onSkipForward = onSkipForward,
                            onSkipBackward = onSkipBackward,
                            onSpeedChange = onSpeedChange,
                            onSleepTimerChange = onSleepTimerChange,
                            modifier = Modifier.padding(horizontal = 24.dp)
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
                                    ) { selectedTab = 0 },
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 16.sp
                                ),
                                color = if (selectedTab == 0) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
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
                                    ) { selectedTab = 1 },
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 16.sp
                                ),
                                color = if (selectedTab == 1) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
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
                                    ) { selectedTab = 2 },
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 16.sp
                                ),
                                color = if (selectedTab == 2) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                                textAlign = TextAlign.End
                            )
                        }
                        Spacer(modifier = Modifier.height(24.dp))
                    }
                }
            }

            ChapterListSheet(
                isVisible = showChapterList.value,
                chapters = chapters,
                currentChapter = currentChapter,
                onDismissRequest = { showChapterList.value = false },
                onChapterClick = { pos ->
                    onSeek(pos, true)
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
        SubtitleLine(0, 5000, "这是第一行字幕内容"),
        SubtitleLine(5000, 10000, "这是正在播放的第二行字幕"),
        SubtitleLine(10000, 15000, "点击字幕可以跳转进度"),
        SubtitleLine(15000, 20000, "支持双语显示和自动滚动")
    )
    val mockBookmarks = listOf(
        BookmarkEntity(1, "uri", 30000L, "重要的伏笔"),
        BookmarkEntity(2, "uri", 600000L, "精彩对白"),
        BookmarkEntity(3, "uri", 1200000L, "结局预感")
    )
    val mockChapters = listOf(
        ChapterEntity(1, "uri", "第一章：序幕", 0L, 300000L),
        ChapterEntity(2, "uri", "第二章：相遇", 300000L, 900000L),
        ChapterEntity(3, "uri", "第三章：危机", 900000L, 1800000L),
        ChapterEntity(4, "uri", "第四章：转机", 1800000L, 3600000L)
    )

    APlayerTheme {
        PlayerContentScreen(
            title = "暁星",
            author = "湊 かなえ",
            narrator = "大森 ゆき",
            currentPosition = 18000L,
            duration = 3600000L,
            isPlaying = false,
            playbackSpeed = 1.0f,
            selectedSleepTimer = 0,
            subtitles = mockSubtitles,
            bookmarks = mockBookmarks,
            chapters = mockChapters,
            showUndoSeek = true,
            onSeek = { _, _ -> },
            onUndoSeek = {},
            onDeleteBookmark = {},
            onAddBookmark = {},
            onPlayPauseClick = {},
            onSkipForward = {},
            onSkipBackward = {},
            onSpeedChange = {},
            onSleepTimerChange = {},
            onClose = {},
            initialTab = initialTab
        )
    }
}
