package com.viel.aplayer.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
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
    
    val currentIndex by remember(currentPosition, subtitles) {
        derivedStateOf {
            subtitles.indexOfFirst { it.startTime <= currentPosition && it.endTime > currentPosition }
        }
    }

    LaunchedEffect(currentIndex) {
        if (currentIndex != -1) {
            listState.animateScrollToItem(currentIndex, scrollOffset = -200)
        }
    }

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        itemsIndexed(subtitles) { index, subtitle ->
            val isCurrent = index == currentIndex
            Text(
                text = subtitle.text,
                style = MaterialTheme.typography.headlineSmall.copy(
                    fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                    fontSize = if (isCurrent) 22.sp else 18.sp
                ),
                color = if (isCurrent) Color.White else Color.White.copy(alpha = 0.3f),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSeek(subtitle.startTime) },
                textAlign = TextAlign.Start
            )
        }
    }
}


@Preview
@Composable
fun SubtitlesViewPreview() {
    APlayerTheme {
        Surface(color = Color(0xFF101418)) {
            SubtitlesView(
                subtitles = listOf(
                    SubtitleLine(0, 5000, "这是第一个字幕。"),
                    SubtitleLine(5000, 10000, "第二个字幕会在这里滚动显示。"),
                    SubtitleLine(10000, 15000, "当前正在播放的字幕会高亮。"),
                    SubtitleLine(15000, 20000, "点击字幕可以直接跳转播放。"),
                    SubtitleLine(20000, 25000, "支持流畅的滚动动画。"),
                    SubtitleLine(25000, 30000, "这是第 6 条字幕内容"),
                    SubtitleLine(30000, 35000, "这是第 7 条字幕内容"),
                    SubtitleLine(35000, 40000, "这是第 8 条字幕内容"),
                    SubtitleLine(40000, 45000, "这是第 9 条字幕内容"),
                    SubtitleLine(45000, 50000, "这是第 10 条字幕内容"),
                    SubtitleLine(50000, 55000, "这是第 11 条字幕内容"),
                    SubtitleLine(55000, 60000, "这是第 12 条字幕内容"),
                    SubtitleLine(60000, 65000, "这是第 13 条字幕内容"),
                    SubtitleLine(65000, 70000, "这是第 14 条字幕内容"),
                    SubtitleLine(70000, 75000, "这是第 15 条字幕内容"),
                    SubtitleLine(75000, 80000, "这是第 16 条字幕内容"),
                    SubtitleLine(80000, 85000, "这是第 17 条字幕内容"),
                    SubtitleLine(85000, 90000, "这是第 18 条字幕内容"),
                    SubtitleLine(90000, 95000, "这是第 19 条字幕内容"),
                    SubtitleLine(95000, 100000, "这是第 20 条字幕内容"),
                    SubtitleLine(100000, 105000, "这是第 21 条字幕内容"),
                    SubtitleLine(105000, 110000, "这是第 22 条字幕内容"),
                    SubtitleLine(110000, 115000, "这是第 23 条字幕内容"),
                    SubtitleLine(115000, 120000, "这是第 24 条字幕内容"),
                    SubtitleLine(120000, 125000, "这是第 25 条字幕内容"),
                    SubtitleLine(125000, 130000, "这是第 26 条字幕内容"),
                    SubtitleLine(130000, 135000, "这是第 27 条字幕内容"),
                    SubtitleLine(135000, 140000, "这是第 28 条字幕内容"),
                    SubtitleLine(140000, 145000, "这是第 29 条字幕内容"),
                    SubtitleLine(145000, 150000, "这是第 30 条字幕内容")
                ),
                currentPosition = 12000L,
                onSeek = {}
            )
        }
    }
}
