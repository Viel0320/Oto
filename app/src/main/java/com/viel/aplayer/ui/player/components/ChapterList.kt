package com.viel.aplayer.ui.player.components

// Migrated to BlurModalBottomSheet, enabling native Window background blur (API 31+).

// Import Resolution (Brings snapshotFlow into scope to observe Compose state changes in flows)
// Added snapshotFlow import to fix unresolved reference snapshotFlow error.
import android.widget.Toast
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Warning
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.viel.aplayer.data.entity.ChapterEntity
import com.viel.aplayer.data.entity.ChapterWithBookFile
import com.viel.aplayer.data.store.AppSettings
import com.viel.aplayer.data.store.GlassEffectMode
import com.viel.aplayer.media.ChapterTimeline
import com.viel.aplayer.ui.common.BlurModalBottomSheet
import com.viel.aplayer.ui.common.formatTime
import com.viel.aplayer.ui.common.theme.APlayerTheme
import com.viel.aplayer.ui.player.BookMetadataState
import com.viel.aplayer.ui.player.PlayerActions
import com.viel.aplayer.ui.settings.PlayerSettingsState
import dev.chrisbanes.haze.HazeState
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.UUID

// 5. Stateful local compartment for chapter list sheet (ChapterListSheetStateful).
//
// This compartment is only rendered and calculates the current chapter when the sheet is actually visible (isVisible == true).
// It completely eliminates the dependency on PlayerViewModel, relying instead on flattened high-frequency state values passed from outside to align with the 3-layer architecture specifications.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChapterListSheetStateful(
    currentPosition: Long, // The current physical playback progress (in milliseconds), passed from upper-level stateless container to decouple ViewModel.
    totalDuration: Long, // Total media duration (in milliseconds).
    metadata: BookMetadataState,
    settings: PlayerSettingsState,
    actions: PlayerActions,
    sheetState: SheetState,
    // Haze State Config (Coordinate Sheet Blur Backdrop) Sourced from player backdrop sampling.
    hazeState: HazeState? = null,
    // Receive the global glass effect mode and pass it to the actual ChapterListSheet.
    glassEffectMode: GlassEffectMode
) {
    if (settings.isChapterListVisible) {
        // Calculate the current chapter according to the current position and chapter info passed from outside
        val currentChapter = remember(currentPosition, metadata.chapters) {
            ChapterTimeline.currentChapter(metadata.chapters.map { it.chapter }, currentPosition)
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
            sheetState = sheetState,
            // Pass the hazeState shared with the player background source to the chapter list panel effect.
            hazeState = hazeState,
            // Material mode will return the chapter list to the native BottomSheet container layer.
            glassEffectMode = glassEffectMode
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChapterListSheet(
    isVisible: Boolean,
    chapters: List<ChapterWithBookFile>,
    currentChapter: ChapterEntity?,
    totalDuration: Long,
    onDismissRequest: () -> Unit,
    onChapterClick: (Long) -> Unit,
    hazeState: HazeState? = null,
    // Glass effect mode must be explicitly passed from the settings state by the player page; the chapter BottomSheet no longer declares a Material default.
    glassEffectMode: GlassEffectMode,
    sheetState: SheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
) {
    val density = LocalDensity.current
    val windowInfo = LocalWindowInfo.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    val initialIndex = remember(chapters, currentChapter) {
        val index = chapters.indexOfFirst { it.chapter.id == currentChapter?.id }
        (index - 2).coerceAtLeast(0)
    }

    val listState = remember(isVisible) {
        LazyListState(firstVisibleItemIndex = initialIndex)
    }

    var canCalculateOffset by remember(isVisible) { mutableStateOf(false) }
    LaunchedEffect(isVisible) {
        if (isVisible) {
            snapshotFlow { sheetState.currentValue == sheetState.targetValue }
                .filter { it }
                .first()
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
                listState = listState,
                // Preview/Inspection path also passes the mode to avoid discrepancies between debug rendering and the actual BottomSheet selected state.
                glassEffectMode = glassEffectMode
            )
        } else {
            // Use the miuix-blur version of BlurModalBottomSheet to replace the original Window-blur-based wrapper.
            // The backdrop is sourced from the player Surface's sampling, allowing the chapter list to sample the player screen to form a frosted glass effect.
            // All original parameters (sheetState, custom drag handle, zero inner padding, etc.) are preserved.
            BlurModalBottomSheet(
                onDismissRequest = onDismissRequest,
                sheetState = sheetState,
                // Pass the HazeState sampling source shared with the player background to render a high-precision frosted glass effect on the BottomSheet overlay.
                hazeState = hazeState,
                // Pass the Material/Haze selection into the general BottomSheet wrapper to unify control of whether internal drawBackdrop is enabled.
                glassEffectMode = glassEffectMode,
                // The blur parameters of the chapter list are configured directly by BlurModalBottomSheet and are no longer passed separately here.
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
                    listState = listState,
                    bottomSpacerHeight = dynamicSpacerHeight,
                    // The chapter list content selects different current chapter highlight styles according to the Material/miuix-blur mode.
                    glassEffectMode = glassEffectMode
                )
            }
        }
    }
}

@Composable
fun ChapterListContent(
    chapters: List<ChapterWithBookFile>,
    currentChapter: ChapterEntity?,
    totalDuration: Long,
    onChapterClick: (Long) -> Unit,
    listState: LazyListState,
    modifier: Modifier = Modifier,
    bottomSpacerHeight: Dp = 0.dp,
    // Glass effect mode must be explicitly passed from the chapter BottomSheet; the list content no longer declares a Material default.
    glassEffectMode: GlassEffectMode
) {

    Column(
        modifier = modifier
            .fillMaxWidth()            .padding(horizontal = 16.dp)
    ) {
        Text(
            text = "Chapters",
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
                Text("No chapters available", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f)
            ) {
                itemsIndexed(
                    items = chapters,
                    key = { _, chapterWithFile -> chapterWithFile.chapter.id }
                ) { index, chapterWithFile ->
                    val chapter = chapterWithFile.chapter
                    val bookFile = chapterWithFile.bookFile
                    val isCurrent = chapter.id == currentChapter?.id
                    val isMissing = bookFile?.status == com.viel.aplayer.data.db.AudiobookSchema.FileStatus.MISSING
                    val context = LocalContext.current

                    // Haze Blur mode uses a lighter rounded glass highlight, while Material mode retains a more distinct primaryContainer selection feedback. Modified references to Haze.
                    val selectedContainerColor = when (glassEffectMode) {
                        GlassEffectMode.Haze -> MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.22f)
                        GlassEffectMode.Material -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.36f)
                    }
                    // Haze Blur mode uses fine borders instead of large blue blocks, making the selected state more delicate on frosted glass and less distracting from the background. Modified references to Haze.
                    val selectedBorderModifier = if (isCurrent && glassEffectMode == GlassEffectMode.Haze) {
                        Modifier.border(
                            width = 1.dp,
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.24f),
                            shape = RoundedCornerShape(8.dp)
                        )
                    } else {
                        Modifier
                    }
                    // Uniformly add rounded corners to the current chapter row to avoid sharp rectangular highlights on the miuix-blur background.
                    val rowShape = RoundedCornerShape(8.dp)
                    ListItem(
                        headlineContent = {
                            // Simplified chapter title layout.
                            //
                            // Removed the redundant "[File Unavailable]" red text to cooperate with the exquisite Rounded.Warning alert icon on the right.
                            // At the same time, the meaningless Row container nesting was physically removed to present the chapter title Text directly, reducing recomposition depth, boosting rendering performance, and achieving a more minimalist design.
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
                                // If the physical file corresponding to the chapter is missing, replace the duration display with a high-fidelity red warning icon.
                                // This provides a more intuitive, premium alert feedback and enhances the overall typographic quality of the list in exception scenarios.
                                Icon(
                                    imageVector = Icons.Rounded.Warning,
                                    contentDescription = "文件不可用",
                                    tint = MaterialTheme.colorScheme.error
                                )
                            } else {
                                // When single files contain embedded chapters, prioritize deriving duration using adjacent start times to avoid inconsistent durationMs displays.
                                Text(
                                    text = formatTime(ChapterTimeline.duration(chapters.map { it.chapter }, chapter, totalDuration)),
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
                                    Toast.makeText(context, "该章节对应的物理文件已丢失，无法播放", Toast.LENGTH_SHORT).show()
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

// Preview of the stateful bridge component for the chapter list sheet. Since decoupling from ViewModel has been completed, mock progress and total duration can be directly passed without constructing a ViewModel.
@OptIn(ExperimentalMaterial3Api::class)
@Preview(showBackground = true, apiLevel = 36)
@Composable
fun ChapterListSheetStatefulPreview() {
    APlayerTheme {
        Surface {
            ChapterListSheetStateful(
                currentPosition = 120000L,
                totalDuration = 360000L,
                metadata = BookMetadataState(title = "三体"),
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
        ChapterWithBookFile(
            chapter = ChapterEntity(
                id = UUID.randomUUID().toString(),
                bookId = "bookId",
                bookFileId = "fileId",
                index = i,
                title = "Chapter ${i + 1}",
                startPositionMs = i * 60000L,
                durationMs = 60000L,
                fileOffsetMs = 0L,
                source = "EMBEDDED"
            ),
            bookFile = if (i == 5) {
                com.viel.aplayer.data.entity.BookFileEntity(
                    id = "fileId",
                    bookId = "bookId",
                    rootId = "rootId",
                    index = i,
                    sourcePath = "path",
                    sourceIdentity = "identity",
                    displayName = "file",
                    durationMs = 60000L,
                    fileSize = 1024L,
                    lastModified = 0L,
                    status = com.viel.aplayer.data.db.AudiobookSchema.FileStatus.MISSING
                )
            } else {
                null
            }
        )
    }

    APlayerTheme {
        Surface {
            ChapterListContent(
                chapters = sampleChapters,
                currentChapter = sampleChapters[17].chapter,
                totalDuration = sampleChapters.last().chapter.startPositionMs + sampleChapters.last().chapter.durationMs,
                onChapterClick = {},
                listState = rememberLazyListState(initialFirstVisibleItemIndex = 15),
                // Preview explicitly references the default glass effect in the settings model, preventing ChapterListContent parameters from having local default values again.
                glassEffectMode = AppSettings.DEFAULT_GLASS_EFFECT_MODE
            )
        }
    }
}
