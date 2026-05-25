package com.viel.aplayer.ui.player.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.viel.aplayer.ui.player.BookMetadataState
import com.viel.aplayer.ui.player.PlayerActions
import com.viel.aplayer.ui.player.PlayerViewModel
import com.viel.aplayer.ui.theme.APlayerTheme

data class SubtitleLine(
    val startTime: Long,
    val endTime: Long,
    val text: String
)

// 
// 4. 歌词字幕有状态局部隔间 SubtitlesViewStateful
// 局部订阅高频进度，维持流畅高频的歌词定位，阻断该高频对外部容器和 AppBar 等的刷新污染。
@Composable
fun SubtitlesViewStateful(
    viewModel: PlayerViewModel,
    metadata: BookMetadataState,
    actions: PlayerActions,
    modifier: Modifier = Modifier
) {
    val isPreview = androidx.compose.ui.platform.LocalInspectionMode.current
    val progressState = if (isPreview) {
        PlayerViewModel.PlaybackProgressViewState(
            elapsedMs = 120000L,
            durationMs = 360000L,
            isChapterProgressMode = false
        )
    } else {
        viewModel.playbackProgressState.collectAsStateWithLifecycle().value
    }
    SubtitlesView(
        subtitles = metadata.subtitles,
        currentPosition = progressState.elapsedMs,
        onSeek = { pos -> actions.playback.onSeek(pos, true) },
        modifier = modifier
    )
}

@Composable
fun SubtitlesView(
    subtitles: List<SubtitleLine>,
    currentPosition: Long,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()
    val density = androidx.compose.ui.platform.LocalDensity.current
    var autoScrollEnabled by remember(subtitles) { mutableStateOf(true) }
    var isFirstScroll by remember(subtitles) { mutableStateOf(true) }

    val highlightedIndices by remember(subtitles, currentPosition) {
        derivedStateOf {
            subtitles.findActiveSubtitleIndices(currentPosition)
        }
    }
    val scrollIndex by remember(highlightedIndices) {
        derivedStateOf {
            highlightedIndices.minOrNull() ?: -1
        }
    }

    val isDragged by listState.interactionSource.collectIsDraggedAsState()
    LaunchedEffect(isDragged) {
        if (isDragged) {
            autoScrollEnabled = false
        }
    }

    LaunchedEffect(isDragged, autoScrollEnabled) {
        if (!isDragged && !autoScrollEnabled) {
            kotlinx.coroutines.delay(5000)
            autoScrollEnabled = true
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        val secondRowOffsetPx = with(density) { 100.dp.toPx().toInt() }

        LaunchedEffect(scrollIndex, autoScrollEnabled) {
            if (scrollIndex != -1 && autoScrollEnabled) {
                val visibleItems = listState.layoutInfo.visibleItemsInfo
                val isVisible = visibleItems.any { it.index == scrollIndex }
                val shouldSnap = isFirstScroll || !isVisible || 
                                 kotlin.math.abs(scrollIndex - listState.firstVisibleItemIndex) > 10

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
                    text = "No subtitles found",
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    style = MaterialTheme.typography.bodyLarge
                )
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 32.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(28.dp),
                flingBehavior = rememberSnapFlingBehavior(lazyListState = listState)
            ) {
                itemsIndexed(
                    items = subtitles,
                    key = { index, subtitle -> "${subtitle.startTime}_$index" }
                ) { index, subtitle ->
                    val isHighlighted = highlightedIndices.contains(index)
                    Text(
                        text = subtitle.text,
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = if (isHighlighted) FontWeight.Bold else FontWeight.Medium,
                            fontSize = if (isHighlighted) 30.sp else 18.sp,
                            lineHeight = if (isHighlighted) 34.sp else 28.sp
                        ),
                        color = if (isHighlighted) MaterialTheme.colorScheme.primary 
                                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { 
                                autoScrollEnabled = true
                                onSeek(subtitle.startTime) 
                            },
                        textAlign = TextAlign.Start
                    )
                }
            }
        }
    }
}


// 使用 @Suppress 抑制在 Composable 预览中直接构造 ViewModel 的 Lint 校验错误
@Suppress("ComposeViewModelForwarding", "ComposeViewModelInjection", "ViewModelConstructorInComposable")
@Preview(showBackground = true, apiLevel = 36)
@Composable
fun SubtitlesViewStatefulPreview() {
    APlayerTheme {
        Surface {
            SubtitlesViewStateful(
                viewModel = PlayerViewModel(),
                metadata = BookMetadataState(title = "三体"),
                actions = PlayerActions(),
                modifier = Modifier.padding(16.dp)
            )
        }
    }
}

@Preview(showBackground = true, apiLevel = 36)
@Composable
fun SubtitlesViewPreview() {
    APlayerTheme {
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
