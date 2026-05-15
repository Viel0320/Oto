package com.viel.aplayer.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.viel.aplayer.ui.theme.APlayerTheme
import com.viel.aplayer.ui.utils.formatTime

@Composable
fun PlaybackProgress(
    currentPosition: Long,
    totalDuration: Long,
    isChapterMode: Boolean,
    chapterStart: Long,
    chapterEnd: Long,
    markers: List<Float>,
    currentChapterIndex: Int,
    chapterCount: Int,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    // 逻辑放回组件内部处理
    val effectiveEnd = if (isChapterMode && chapterEnd <= chapterStart) totalDuration else if (isChapterMode) chapterEnd else totalDuration
    val displayDur = if (isChapterMode) (effectiveEnd - chapterStart).coerceAtLeast(1) else totalDuration
    val displayPos = if (isChapterMode) (currentPosition - chapterStart).coerceAtLeast(0) else currentPosition

    Column(modifier = modifier.fillMaxWidth()) {
        AudioProgressBar(
            progress = {
                if (displayDur > 0) displayPos.toFloat() / displayDur.toFloat() else 0f
            },
            onProgressChange = { newProgress ->
                if (displayDur > 0) {
                    val targetPos = if (isChapterMode) {
                        chapterStart + (newProgress * displayDur).toLong()
                    } else {
                        (newProgress * totalDuration).toLong()
                    }
                    onSeek(targetPos)
                }
            },
            markers = if (isChapterMode) emptyList() else markers,
            modifier = Modifier.fillMaxWidth()
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = formatTime(displayPos),
                style = MaterialTheme.typography.labelMedium
            )
            
            if (chapterCount > 0) {
                Text(
                    text = "${currentChapterIndex + 1} / $chapterCount",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Text(
                text = formatTime(displayDur),
                style = MaterialTheme.typography.labelMedium
            )
        }
    }
}

@Preview(showBackground = true, apiLevel = 36)
@Composable
fun PlaybackProgressPreview() {
    APlayerTheme {
        Surface {
            PlaybackProgress(
                currentPosition = 120000L,
                totalDuration = 360000L,
                isChapterMode = false,
                chapterStart = 0,
                chapterEnd = 0,
                markers = listOf(0.2f, 0.5f, 0.8f),
                currentChapterIndex = 1,
                chapterCount = 4,
                onSeek = {},
                modifier = Modifier.padding(16.dp)
            )
        }
    }
}
