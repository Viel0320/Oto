package com.viel.aplayer.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import com.viel.aplayer.R
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.viel.aplayer.ui.theme.APlayerTheme

data class SubtitleLine(
    val startTime: Long,
    val endTime: Long,
    val text: String
)

@Composable
fun SubtitlesView(
    subtitles: List<SubtitleLine>,
    currentPosition: Long,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()
    var autoScrollEnabled by remember { mutableStateOf(true) }
    val density = androidx.compose.ui.platform.LocalDensity.current
    
    // Logic to find all indices that should be highlighted (for bilingual support)
    val currentIndices by remember(currentPosition, subtitles) {
        derivedStateOf {
            if (subtitles.isEmpty()) emptySet()
            else {
                var low = 0
                var high = subtitles.size - 1
                var pivot = -1
                
                // 1. Binary search to find one matching line
                while (low <= high) {
                    val mid = (low + high) / 2
                    val sub = subtitles[mid]
                    if (currentPosition >= sub.startTime && currentPosition < sub.endTime) {
                        pivot = mid
                        break
                    } else if (currentPosition < sub.startTime) {
                        high = mid - 1
                    } else {
                        low = mid + 1
                    }
                }

                if (pivot == -1) emptySet()
                else {
                    val results = mutableSetOf(pivot)
                    // 2. Check previous line for overlap
                    if (pivot > 0) {
                        val prev = subtitles[pivot - 1]
                        if (currentPosition >= prev.startTime && currentPosition < prev.endTime) {
                            results.add(pivot - 1)
                        }
                    }
                    // 3. Check next line for overlap
                    if (pivot < subtitles.size - 1) {
                        val next = subtitles[pivot + 1]
                        if (currentPosition >= next.startTime && currentPosition < next.endTime) {
                            results.add(pivot + 1)
                        }
                    }
                    results
                }
            }
        }
    }

    // Use the first (top-most) index for scrolling calculations
    val scrollIndex = remember(currentIndices) {
        currentIndices.minOrNull() ?: -1
    }

    val isDragged by listState.interactionSource.collectIsDraggedAsState()
    LaunchedEffect(isDragged) {
        if (isDragged) autoScrollEnabled = false
    }

    // Auto-resume sync after 10 seconds of inactivity
    LaunchedEffect(autoScrollEnabled, isDragged) {
        if (!autoScrollEnabled && !isDragged) {
            kotlinx.coroutines.delay(5000)
            autoScrollEnabled = true
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        val secondRowOffsetPx = with(density) { 100.dp.toPx().toInt() }
        var isFirstScroll by remember { mutableStateOf(true) }

        // Auto-scroll logic using the top-most active index
        LaunchedEffect(scrollIndex, autoScrollEnabled) {
            if (scrollIndex != -1 && autoScrollEnabled) {
                val visibleItems = listState.layoutInfo.visibleItemsInfo
                val isVisible = visibleItems.any { it.index == scrollIndex }
                
                // If isFirstScroll is true, we always snap. 
                // If autoScrollEnabled JUST became true, we should also check if it's already in view.
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
            // ... (Empty state UI)
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
                contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 32.dp, bottom = 300.dp),
                verticalArrangement = Arrangement.spacedBy(28.dp)
            ) {
                itemsIndexed(
                    items = subtitles,
                    // Use a composite key to ensure uniqueness even with overlapping bilingual subtitles
                    key = { index, subtitle -> "${subtitle.startTime}_$index" }
                ) { index, subtitle ->
                    val isHighlighted = currentIndices.contains(index)
                    Text(
                        text = subtitle.text,
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = if (isHighlighted) FontWeight.Bold else FontWeight.Medium,
                            fontSize = if (isHighlighted) 24.sp else 18.sp,
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
        
        // ... (Resume button logic)
        if (!autoScrollEnabled && scrollIndex != -1 && subtitles.isNotEmpty()) {
            androidx.compose.animation.AnimatedVisibility(
                visible = true,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 32.dp)
            ) {
                Button(
                    onClick = { autoScrollEnabled = true },
                    colors = ButtonDefaults.filledTonalButtonColors(),
                    elevation = ButtonDefaults.buttonElevation(defaultElevation = 4.dp)
                ) {
                    Icon(painterResource(R.drawable.ic_rounded_history), contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Resume Sync")
                }
            }
        }
    }
}


@Preview
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
                currentPosition = 7500L,
                onSeek = {}
            )
        }
    }
}
