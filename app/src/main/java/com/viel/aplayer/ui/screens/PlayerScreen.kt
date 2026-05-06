package com.viel.aplayer.ui.screens

import android.graphics.BitmapFactory
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.List
import androidx.compose.material.icons.rounded.BookmarkAdd
import androidx.compose.material.icons.rounded.Forward30
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Replay10
import androidx.compose.material.icons.rounded.Snooze
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.palette.graphics.Palette
import coil.compose.AsyncImage
import com.viel.aplayer.data.ChapterEntity
import com.viel.aplayer.ui.components.AudioProgressBar
import com.viel.aplayer.ui.theme.APlayerTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class, ExperimentalSharedTransitionApi::class, ExperimentalFoundationApi::class)
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
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null,
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val backgroundColor = remember { mutableStateOf(Color(0xFF1C1B1F)) }

    var showChapterList by remember { mutableStateOf(value = false) }
    val sheetState = rememberModalBottomSheetState()
    
    // Find current chapter using derivedStateOf to minimize recompositions
    val currentChapter by remember(chapters) {
        androidx.compose.runtime.derivedStateOf {
            chapters.find {
                (currentPosition() >= it.startPosition) && (currentPosition() < it.endPosition)
            } ?: chapters.firstOrNull { currentPosition() < it.startPosition }
        }
    }

    // Track if we are in manual cycling mode or initial/reset state
    var isSpeedManualMode by remember { mutableStateOf(playbackSpeed != 1.0f) }

    val animatedBgColor by animateColorAsState(
        targetValue = backgroundColor.value,
        animationSpec = tween(300),
        label = "bg_color"
    )

    LaunchedEffect(coverPath) {
        if (coverPath != null) {
            val file = File(coverPath)
            if (file.exists()) {
                val bitmap = BitmapFactory.decodeFile(file.absolutePath)
                if (bitmap != null) {
                    val palette = withContext(Dispatchers.Default) {
                        Palette.from(bitmap).generate()
                    }
                    backgroundColor.value = Color(palette.getDominantColor(0xFF1C1B1F.toInt()))
                }
            }
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = Color.Transparent,
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = author,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onMinimize) {
                        Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = "Minimize")
                    }
                },
                actions = {
                    IconButton(onClick = { /* TODO */ }) {
                        Icon(Icons.Rounded.MoreVert, contentDescription = "Options")
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = Color.Transparent
                )
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            animatedBgColor.copy(alpha = 0.6f),
                            MaterialTheme.colorScheme.background
                        )
                    )
                )
                .padding(padding)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(modifier = Modifier.height(24.dp))

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

                    Box(
                        modifier = if (sharedTransitionScope != null && animatedVisibilityScope != null) {
                            with(sharedTransitionScope) {
                                coverModifier.sharedElement(
                                    rememberSharedContentState(key = "cover_$title"),
                                    animatedVisibilityScope = animatedVisibilityScope,
                                    boundsTransform = { _, _ ->
                                        tween(400, easing = FastOutSlowInEasing)
                                    }
                                ).clip(coverShape)
                            }
                        } else {
                            coverModifier.clip(coverShape)
                        },
                        contentAlignment = Alignment.Center
                    ) {
                        if (coverPath != null && File(coverPath).exists()) {
                            AsyncImage(
                                model = File(coverPath),
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
                                    Icons.Rounded.PlayArrow,
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
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SuggestionChip(
                        onClick = { showChapterList = true },
                        modifier = Modifier.weight(1f, fill = false),
                        label = {
                            Text(
                                text = currentChapter?.title ?: "No Chapters",
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        },
                        icon = {
                            Icon(
                                Icons.AutoMirrored.Rounded.List,
                                contentDescription = currentChapter?.title ?: "No Chapters",
                                modifier = Modifier.size(AssistChipDefaults.IconSize)
                            )
                        },
                        shape = RoundedCornerShape(12.dp)
                    )

                    IconButton(onClick = { /* TODO */ }) {
                        Icon(Icons.Rounded.BookmarkAdd, contentDescription = "Bookmark")
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Progress Bar
                val chapterMarkers by remember(chapters) {
                    androidx.compose.runtime.derivedStateOf {
                        val d = duration()
                        if (d > 0) {
                            chapters.map { it.startPosition.toFloat() / d.toFloat() }
                        } else emptyList()
                    }
                }

                // 使用 remember 缓存 lambda，避免 AudioProgressBar 不必要的重组
                val progressProvider = remember(currentPosition, duration) {
                    {
                        val d = duration()
                        if (d > 0) (currentPosition().toFloat() / d.toFloat()) else 0f
                    }
                }

                AudioProgressBar(
                    progress = progressProvider,
                    onProgressChange = { newProgress ->
                        val d = duration()
                        if (d > 0) {
                            onSeek((newProgress * d).toLong())
                        }
                    },
                    markers = chapterMarkers,
                    modifier = Modifier.fillMaxWidth()
                )

                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    TimeLabel(currentPosition, MaterialTheme.typography.labelMedium)
                    TimeLabel(duration, MaterialTheme.typography.labelMedium)
                }

                Spacer(modifier = Modifier.weight(1.5f))

                // Playback Controls
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val speeds = listOf(0.25f, 0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f)
                    
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .clip(CircleShape)
                            .combinedClickable(
                                onClick = {
                                    isSpeedManualMode = true
                                    val currentIndex = speeds.indexOf(playbackSpeed).coerceAtLeast(0)
                                    val nextIndex = (currentIndex + 1) % speeds.size
                                    onSpeedChange(speeds[nextIndex])
                                },
                                onLongClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    isSpeedManualMode = false
                                    onSpeedChange(1.0f)
                                    Toast.makeText(context, "倍速已关闭", Toast.LENGTH_SHORT).show()
                                }
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        if (playbackSpeed == 1.0f && !isSpeedManualMode) {
                            Icon(
                                Icons.Rounded.Speed,
                                contentDescription = "Playback Speed",
                                modifier = Modifier.size(24.dp),
                                tint = MaterialTheme.colorScheme.onSurface
                            )
                        } else {
                            Text(
                                text = "${playbackSpeed}x",
                                style = MaterialTheme.typography.labelLarge.copy(
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.ExtraBold
                                ),
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }

                    IconButton(onClick = onSkipBackward, modifier = Modifier.size(56.dp)) {
                        // TODO: Update icon and content description when skip duration is customized
                        Icon(Icons.Rounded.Replay10, contentDescription = "Rewind 10 seconds", modifier = Modifier.size(32.dp))
                    }

                    FilledIconButton(
                        onClick = onPlayPauseClick,
                        modifier = Modifier.size(80.dp),
                        shape = CircleShape,
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    ) {
                        Icon(
                            imageVector = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                            contentDescription = if (isPlaying) "Pause" else "Play",
                            modifier = Modifier.size(40.dp)
                        )
                    }

                    IconButton(onClick = onSkipForward, modifier = Modifier.size(56.dp)) {
                        // TODO: Update icon and content description when skip duration is customized
                        Icon(Icons.Rounded.Forward30, contentDescription = "Forward 30 seconds", modifier = Modifier.size(32.dp))
                    }

                    val sleepOptions = listOf(0, -1, 10, 15, 30, 45, 60)
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .clip(CircleShape)
                            .combinedClickable(
                                onClick = {
                                    val currentIndex = sleepOptions.indexOf(selectedSleepTimer).coerceAtLeast(0)
                                    val nextIndex = (currentIndex + 1) % sleepOptions.size
                                    onSleepTimerChange(sleepOptions[nextIndex])
                                },
                                onLongClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    onSleepTimerChange(0)
                                    Toast.makeText(context, "定时已关闭", Toast.LENGTH_SHORT).show()
                                }
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        if (selectedSleepTimer == 0) {
                            Icon(
                                Icons.Rounded.Snooze,
                                contentDescription = "Sleep Timer",
                                modifier = Modifier.size(24.dp),
                                tint = MaterialTheme.colorScheme.onSurface
                            )
                        } else {
                            val displayText = if (selectedSleepTimer == -1) "5s" else "${selectedSleepTimer}m"
                            Text(
                                text = displayText,
                                style = MaterialTheme.typography.labelLarge.copy(
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.ExtraBold
                                ),
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.weight(1f))

                // Additional Bottom Options Placeholder
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 32.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                        Text(
                            text = "Bookmarks",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        Text(
                            text = "Subtitles",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                        Text(
                            text = "Related",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        if (showChapterList) {
            ModalBottomSheet(
                onDismissRequest = { showChapterList = false },
                sheetState = sheetState,
                containerColor = MaterialTheme.colorScheme.surface,
                tonalElevation = 8.dp
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .navigationBarsPadding()
                ) {
                    Text(
                        text = "Chapters",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(bottom = 16.dp)
                    )

                    if (chapters.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(200.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("No chapters available", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier.weight(1f, fill = false)
                        ) {
                            itemsIndexed(chapters) { index, chapter ->
                                val isCurrent = chapter == currentChapter
                                ListItem(
                                    headlineContent = {
                                        Text(
                                            text = chapter.title,
                                            fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                                            color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                        )
                                    },
                                    leadingContent = {
                                        Text(
                                            text = (index + 1).toString(),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    },
                                    trailingContent = {
                                        Text(
                                            text = formatTime(chapter.startPosition),
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    },
                                    modifier = Modifier.clickable {
                                        onSeek(chapter.startPosition)
                                        showChapterList = false
                                    },
                                    colors = ListItemDefaults.colors(
                                        containerColor = if (isCurrent) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f) else Color.Transparent
                                    )
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(32.dp))
                }
            }
        }
    }
}

@Composable
private fun TimeLabel(timeProvider: () -> Long, style: androidx.compose.ui.text.TextStyle) {
    Text(
        text = formatTime(timeProvider()),
        style = style
    )
}

fun formatTime(ms: Long): String {
    val totalSeconds = ms / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return String.format(Locale.getDefault(), "%02d:%02d:%02d", hours, minutes, seconds)
}

@OptIn(ExperimentalSharedTransitionApi::class)
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
