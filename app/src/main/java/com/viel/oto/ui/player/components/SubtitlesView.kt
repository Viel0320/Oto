package com.viel.oto.ui.player.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.viel.oto.R
import com.viel.oto.media.subtitle.SubtitleLine
import com.viel.oto.ui.common.theme.OtoTheme
import kotlin.math.abs
import kotlin.time.Duration.Companion.milliseconds

private const val SUBTITLE_SYNC_STEP_MS = 250L

/**
 * Matches the Material icon-button column used by SubtitleSyncControls.
 */
private val SUBTITLE_SYNC_CONTROLS_WIDTH = 48.dp

/**
 * Covers the vertical band occupied by the three icon buttons and offset label.
 */
private val SUBTITLE_SYNC_CONTROLS_HEIGHT = 168.dp

/**
 * Renders the subtitle timeline with a fixed-position sync-control column.
 */
@Composable
fun SubtitlesView(
    subtitles: List<SubtitleLine>,
    currentPosition: Long,
    subtitleSyncOffsetMs: Long,
    onAdjustSubtitleSync: (Long) -> Unit,
    onResetSubtitleSync: () -> Unit,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    val emptySubtitlesText = stringResource(R.string.subtitles_empty)
    val listState = rememberLazyListState()
    val density = androidx.compose.ui.platform.LocalDensity.current
    var autoScrollEnabled by remember(subtitles) { mutableStateOf(true) }
    var isFirstScroll by remember(subtitles) { mutableStateOf(true) }
    val adjustedPosition = (currentPosition + subtitleSyncOffsetMs).coerceAtLeast(0L)

    val highlightedIndices by remember(subtitles, adjustedPosition) {
        derivedStateOf {
            subtitles.findActiveSubtitleIndices(adjustedPosition)
        }
    }
    val scrollIndex by remember(highlightedIndices) {
        derivedStateOf {
            highlightedIndices.minOrNull() ?: -1
        }
    }

    val isDragged by listState.interactionSource.collectIsDraggedAsState()
    val syncControlOverlappedRows by remember(listState) {
        derivedStateOf {
            val controlsTop = listState.layoutInfo.viewportEndOffset - with(density) {
                SUBTITLE_SYNC_CONTROLS_HEIGHT.toPx().toInt()
            }
            listState.layoutInfo.visibleItemsInfo
                .filter { item -> item.offset + item.size > controlsTop }
                .mapTo(mutableSetOf()) { item -> item.index }
        }
    }

    LaunchedEffect(isDragged) {
        if (isDragged) {
            autoScrollEnabled = false
        }
    }

    LaunchedEffect(isDragged, autoScrollEnabled) {
        if (!isDragged && !autoScrollEnabled) {
            kotlinx.coroutines.delay(5000.milliseconds)
            autoScrollEnabled = true
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        val secondRowOffsetPx = with(density) { 100.dp.toPx().toInt() }

        LaunchedEffect(scrollIndex, autoScrollEnabled) {
            if (scrollIndex != -1 && autoScrollEnabled) {
                val visibleItems = listState.layoutInfo.visibleItemsInfo
                val isVisible = visibleItems.any { it.index == scrollIndex }
                val shouldSnap = isFirstScroll || !isVisible || abs(scrollIndex - listState.firstVisibleItemIndex) > 10

                if (shouldSnap) {
                    listState.scrollToItem(scrollIndex, scrollOffset = -secondRowOffsetPx)
                    isFirstScroll = false
                } else {
                    listState.animateScrollToItem(scrollIndex, scrollOffset = -secondRowOffsetPx)
                }
            }
        }

        if (subtitles.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = emptySubtitlesText,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    style = MaterialTheme.typography.bodyLarge
                )
            }
        } else {
            Box(modifier = Modifier.fillMaxSize()) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .fillMaxSize(),
                    contentPadding = PaddingValues(top = 32.dp, bottom = 32.dp),
                    verticalArrangement = Arrangement.spacedBy(28.dp),
                    flingBehavior = rememberSnapFlingBehavior(lazyListState = listState)
                ) {
                    itemsIndexed(
                        items = subtitles,
                        key = { index, subtitle -> "${subtitle.startTime}_$index" }
                    ) { index, subtitle ->
                        val isHighlighted = highlightedIndices.contains(index)
                        SubtitleGridRow(
                            text = subtitle.text,
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = if (isHighlighted) FontWeight.Bold else FontWeight.Medium,
                                fontSize = if (isHighlighted) 30.sp else 18.sp,
                                lineHeight = if (isHighlighted) 34.sp else 28.sp
                            ),
                            color = if (isHighlighted) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                            reserveSyncColumn = syncControlOverlappedRows.contains(index),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    autoScrollEnabled = true
                                    onSeek((subtitle.startTime - subtitleSyncOffsetMs).coerceAtLeast(0L))
                                }
                        )
                    }
                }
                SubtitleSyncControls(
                    subtitleSyncOffsetMs = subtitleSyncOffsetMs,
                    onAdjustSubtitleSync = onAdjustSubtitleSync,
                    onResetSubtitleSync = onResetSubtitleSync,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                )
            }
        }
    }
}

/**
 * Keeps each subtitle cue on a stable two-column grid row.
 *
 * The row spans text across both columns until the caller marks it as overlapping the sync controls.
 * Overlapping rows reserve only the fixed sync-control column, so wrapping follows the actual
 * available text column instead of relying on trailing padding.
 */
@Composable
private fun SubtitleGridRow(
    text: String,
    style: androidx.compose.ui.text.TextStyle,
    color: androidx.compose.ui.graphics.Color,
    reserveSyncColumn: Boolean,
    modifier: Modifier = Modifier
) {
    Row(modifier = modifier) {
        Text(
            text = text,
            style = style,
            color = color,
            modifier = if (reserveSyncColumn) Modifier.weight(1f) else Modifier.fillMaxWidth(),
            textAlign = TextAlign.Start
        )
        if (reserveSyncColumn) {
            Box(modifier = Modifier.width(SUBTITLE_SYNC_CONTROLS_WIDTH))
        }
    }
}


/**
 * Renders playback-plan-scoped subtitle timing controls as a right-side floating strip.
 *
 * The controls adjust the app-wide cue matching offset owned by PlaybackViewModel, keeping subtitle
 * parsing and persisted playback progress unchanged. Idle opacity keeps the controls secondary
 * until the listener starts adjusting sync again.
 */
@Composable
private fun SubtitleSyncControls(
    subtitleSyncOffsetMs: Long,
    onAdjustSubtitleSync: (Long) -> Unit,
    onResetSubtitleSync: () -> Unit,
    modifier: Modifier = Modifier
) {
    val delayText = stringResource(R.string.subtitle_sync_delay)
    val advanceText = stringResource(R.string.subtitle_sync_advance)
    val resetText = stringResource(R.string.subtitle_sync_reset)
    val offsetText = formatSubtitleOffset(subtitleSyncOffsetMs)
    var controlsActive by remember { mutableStateOf(false) }
    val controlsAlpha = if (controlsActive) 1f else 0.48f

    LaunchedEffect(controlsActive, subtitleSyncOffsetMs) {
        if (controlsActive) {
            kotlinx.coroutines.delay(1800.milliseconds)
            controlsActive = false
        }
    }

    Column(
        modifier = modifier.alpha(controlsAlpha),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        IconButton(onClick = {
            controlsActive = true
            onAdjustSubtitleSync(SUBTITLE_SYNC_STEP_MS)
        }) {
            Icon(Icons.Rounded.Add, contentDescription = advanceText)
        }
        Text(
            text = offsetText,
            modifier = Modifier.width(48.dp),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        IconButton(onClick = {
            controlsActive = true
            onResetSubtitleSync()
        }) {
            Icon(Icons.Rounded.RestartAlt, contentDescription = resetText)
        }
        IconButton(onClick = {
            controlsActive = true
            onAdjustSubtitleSync(-SUBTITLE_SYNC_STEP_MS)
        }) {
            Icon(Icons.Rounded.Remove, contentDescription = delayText)
        }
    }
}

/**
 * Formats millisecond offsets without locale-sensitive decimal rendering.
 *
 * Compose previews and tests stay deterministic because the sign and hundredths are assembled from
 * integer parts instead of depending on the device locale.
 */
private fun formatSubtitleOffset(offsetMs: Long): String {
    val sign = when {
        offsetMs > 0L -> "+"
        offsetMs < 0L -> "-"
        else -> ""
    }
    val absoluteMs = abs(offsetMs)
    val seconds = absoluteMs / 1_000L
    val hundredths = (absoluteMs % 1_000L) / 10L
    return "$sign$seconds.${hundredths.toString().padStart(2, '0')}s"
}

@Preview(showBackground = true, apiLevel = 36)
@Composable
fun SubtitlesViewPreview() {
    OtoTheme {
        Surface {
            SubtitlesView(
                subtitles = listOf(
                    SubtitleLine(0, 5000, "This is the first line."),
                    SubtitleLine(5000, 10000, "This is a bilingual line (English)"),
                    SubtitleLine(5000, 10000, "这是一条双语字幕 (中文)"),
                    SubtitleLine(10000, 15000, "You can click to seek."),
                    SubtitleLine(15000, 20000, "Smooth scrolling is supported."),
                    SubtitleLine(20000, 25000, "Line 6 Content"),
                    SubtitleLine(25000, 30000, "Line 7 Content"),
                    SubtitleLine(30000, 35000, "Line 8 Content"),
                    SubtitleLine(35000, 40000, "Line 9 Content"),
                    SubtitleLine(40000, 45000, "Line 10 Content")
                ),
                currentPosition = 5000,
                subtitleSyncOffsetMs = 250,
                onAdjustSubtitleSync = {},
                onResetSubtitleSync = {},
                onSeek = {},
            )
        }
    }
}

private fun List<SubtitleLine>.findActiveSubtitleIndices(currentPosition: Long): Set<Int> {
    if (isEmpty()) return emptySet()

    var low = 0
    var high = size - 1
    var pivot = -1

    while (low <= high) {
        val mid = (low + high) / 2
        val subtitle = this[mid]
        if (currentPosition >= subtitle.startTime && currentPosition < subtitle.endTime) {
            pivot = mid
            break
        } else if (currentPosition < subtitle.startTime) {
            high = mid - 1
        } else {
            low = mid + 1
        }
    }

    if (pivot == -1) return emptySet()

    val results = mutableSetOf(pivot)
    if (pivot > 0) {
        val previous = this[pivot - 1]
        if (currentPosition >= previous.startTime && currentPosition < previous.endTime) {
            results.add(pivot - 1)
        }
    }
    if (pivot < lastIndex) {
        val next = this[pivot + 1]
        if (currentPosition >= next.startTime && currentPosition < next.endTime) {
            results.add(pivot + 1)
        }
    }
    return results
}
