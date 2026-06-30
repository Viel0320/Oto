package com.viel.oto.ui.player.components

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SheetState
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.viel.oto.shared.R
import com.viel.oto.application.library.LibraryChapterSource
import com.viel.oto.application.library.player.PlayerChapterItem
import com.viel.oto.application.library.player.PlayerChapterTimeline
import com.viel.oto.shared.policy.formatTime
import com.viel.oto.shared.model.AppSettings
import com.viel.oto.shared.model.GlassEffectMode
import com.viel.oto.ui.common.BlurModalBottomSheet
import com.viel.oto.ui.common.layout.LocalAppWindowSizeClass
import com.viel.oto.ui.common.theme.OtoTheme
import com.viel.oto.ui.player.BookMetadataState
import com.viel.oto.ui.player.PlayerActions
import com.viel.oto.ui.settings.PlayerSettingsState
import dev.chrisbanes.haze.HazeState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.UUID
import com.viel.oto.ui.common.icons.OtoIcons

/**
 * Owns the stateful shell for the chapter list sheet.
 *
 * This compartment is rendered only while the sheet is visible, and it derives the current
 * chapter from flattened playback state passed by the caller instead of reaching into PlayerViewModel.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChapterListSheetStateful(
    currentPosition: Long,
    totalDuration: Long,
    metadata: BookMetadataState,
    settings: PlayerSettingsState,
    actions: PlayerActions,
    sheetState: SheetState,
    hazeState: HazeState? = null,
    glassEffectMode: GlassEffectMode
) {
    if (settings.isChapterListVisible) {
        val currentChapter = remember(currentPosition, metadata.chapters) {
            PlayerChapterTimeline.currentChapter(metadata.chapters, currentPosition)
        }
        ChapterListSheet(
            isVisible = true,
            chapters = metadata.chapters,
            currentChapter = currentChapter,
            totalDuration = totalDuration,
            onDismissRequest = actions.content.onDismissChapterList,
            onChapterClick = { pos ->
                actions.playback.onSeek(pos, true)
                actions.content.onDismissChapterList()
            },
            onMissingChapterClick = actions.playback.onMissingChapterClick,
            sheetState = sheetState,
            hazeState = hazeState,
            glassEffectMode = glassEffectMode
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChapterListSheet(
    isVisible: Boolean,
    chapters: List<PlayerChapterItem>,
    currentChapter: PlayerChapterItem?,
    totalDuration: Long,
    onDismissRequest: () -> Unit,
    onChapterClick: (Long) -> Unit,
    onMissingChapterClick: (bookId: String) -> Unit,
    hazeState: HazeState? = null,
    glassEffectMode: GlassEffectMode,
    sheetState: SheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
) {
    val density = LocalDensity.current
    val windowInfo = LocalWindowInfo.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    val initialIndex = remember(chapters, currentChapter) {
        val index = chapters.indexOfFirst { it.id == currentChapter?.id }
        (index - 2).coerceAtLeast(0)
    }

    val listState = remember(isVisible) {
        LazyListState(firstVisibleItemIndex = initialIndex)
    }

    var canCalculateOffset by remember(isVisible) { mutableStateOf(false) }
    LaunchedEffect(isVisible) {
        if (isVisible) {
            snapshotFlow { sheetState.currentValue == sheetState.targetValue }.first { it }
            canCalculateOffset = true
        } else {
            canCalculateOffset = false
        }
    }

    val dynamicSpacerHeight by remember(sheetState, canCalculateOffset) {
        derivedStateOf {
            val halfHeight = with(density) { (windowInfo.containerSize.height / 2).toDp() }
            if (!canCalculateOffset || sheetState.targetValue == SheetValue.Hidden) {
                halfHeight
            } else {
                try {
                    val offsetPx = sheetState.requireOffset()
                    with(density) { offsetPx.toDp() }
                } catch (_: IllegalStateException) {
                    halfHeight
                }
            }
        }
    }

    if (isVisible) {
        if (LocalInspectionMode.current) {
            ChapterListContent(
                chapters = chapters,
                currentChapter = currentChapter,
                totalDuration = totalDuration,
                onChapterClick = onChapterClick,
                onMissingChapterClick = {},
                listState = listState,
                glassEffectMode = glassEffectMode
            )
        } else {
            BlurModalBottomSheet(
                onDismissRequest = onDismissRequest,
                sheetState = sheetState,
                hazeState = hazeState,
                glassEffectMode = glassEffectMode,
                tonalElevation = 8.dp,
                contentWindowInsets = { WindowInsets(0, 0, 0, 0) },
                dragHandle = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Spacer(Modifier.statusBarsPadding())
                        BottomSheetDefaults.DragHandle()
                    }
                }
            ) {
                ChapterListContent(
                    chapters = chapters,
                    currentChapter = currentChapter,
                    totalDuration = totalDuration,
                    onChapterClick = { pos ->
                        scope.launch {
                            sheetState.hide()
                            onChapterClick(pos)
                            onDismissRequest()
                        }
                    },
                    onMissingChapterClick = onMissingChapterClick,
                    listState = listState,
                    bottomSpacerHeight = dynamicSpacerHeight,
                    glassEffectMode = glassEffectMode
                )
            }
        }
    }
}

@Composable
fun ChapterListContent(
    chapters: List<PlayerChapterItem>,
    currentChapter: PlayerChapterItem?,
    totalDuration: Long,
    onChapterClick: (Long) -> Unit,
    onMissingChapterClick: (bookId: String) -> Unit,
    listState: LazyListState,
    modifier: Modifier = Modifier,
    bottomSpacerHeight: Dp = 0.dp,
    glassEffectMode: GlassEffectMode
) {
    val screenHorizontalPadding = LocalAppWindowSizeClass.current.screenHorizontalPadding

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = screenHorizontalPadding)
    ) {
        Text(
            text = stringResource(R.string.chapter_list_title),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp, top = 8.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )

        if (chapters.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(stringResource(R.string.chapter_list_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f)
            ) {
                itemsIndexed(
                    items = chapters,
                    key = { _, chapter -> chapter.id }
                ) { index, chapter ->
                    val isCurrent = chapter.id == currentChapter?.id
                    val isMissing = chapter.isFileMissing

                    val selectedContainerColor = when (glassEffectMode) {
                        GlassEffectMode.Haze -> MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.22f)
                        GlassEffectMode.Material -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.36f)
                    }
                    val selectedBorderModifier = if (isCurrent && glassEffectMode == GlassEffectMode.Haze) {
                        Modifier.border(
                            width = 1.dp,
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.24f),
                            shape = RoundedCornerShape(8.dp)
                        )
                    } else {
                        Modifier
                    }
                    val rowShape = RoundedCornerShape(8.dp)
                    ListItem(
                        headlineContent = {
                            Text(
                                text = chapter.title,
                                fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                                color = if (isMissing) {
                                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                                } else if (isCurrent) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                }
                            )
                        },
                        leadingContent = {
                            Text(
                                text = (index + 1).toString(),
                                style = MaterialTheme.typography.bodySmall,
                                color = if (isMissing) {
                                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                                } else if (isCurrent) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                }
                            )
                        },
                        trailingContent = {
                            if (isMissing) {
                                Icon(
                                    imageVector = OtoIcons.Rounded.Warning,
                                    contentDescription = stringResource(R.string.chapter_file_unavailable_description),
                                    tint = MaterialTheme.colorScheme.error
                                )
                            } else {
                                Text(
                                    text = formatTime(PlayerChapterTimeline.duration(chapters, chapter, totalDuration)),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp)
                            .clip(rowShape)
                            .then(selectedBorderModifier)
                            .clickable {
                                if (isMissing) {
                                    onMissingChapterClick(chapter.bookId)
                                } else {
                                    onChapterClick(chapter.startPositionMs)
                                }
                            },
                        colors = ListItemDefaults.colors(
                            containerColor = if (isCurrent)
                                selectedContainerColor
                            else Color.Transparent
                        )
                    )
                }

                item(key = "bottom_spacer") {
                    Spacer(modifier = Modifier.height(bottomSpacerHeight))
                    Spacer(modifier = Modifier.height(32.dp))
                }

            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Preview(showBackground = true, apiLevel = 36)
@Composable
fun ChapterListSheetStatefulPreview() {
    OtoTheme {
        Surface {
            ChapterListSheetStateful(
                currentPosition = 120000L,
                totalDuration = 360000L,
                metadata = BookMetadataState(title = "The Three-Body Problem"),
                settings = PlayerSettingsState(isChapterListVisible = true),
                actions = PlayerActions(),
                sheetState = rememberModalBottomSheetState(),
                hazeState = null,
                glassEffectMode = GlassEffectMode.Material
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Preview(showBackground = true, apiLevel = 35)
@Composable
fun ChapterListSheetPreview() {
    val sampleChapters = List(20) { i ->
        PlayerChapterItem(
            id = UUID.randomUUID().toString(),
            bookId = "bookId",
            bookFileId = "fileId",
            index = i,
            title = "Chapter ${i + 1}",
            startPositionMs = i * 60000L,
            durationMs = 60000L,
            fileOffsetMs = 0L,
            source = LibraryChapterSource.EMBEDDED,
            isFileMissing = i == 5
        )
    }

    OtoTheme {
        Surface {
            ChapterListContent(
                chapters = sampleChapters,
                currentChapter = sampleChapters[17],
                totalDuration = sampleChapters.last().startPositionMs + sampleChapters.last().durationMs,
                onChapterClick = {},
                onMissingChapterClick = {},
                listState = rememberLazyListState(initialFirstVisibleItemIndex = 15),
                glassEffectMode = AppSettings.DEFAULT_GLASS_EFFECT_MODE
            )
        }
    }
}
