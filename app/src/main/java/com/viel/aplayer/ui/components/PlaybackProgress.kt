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
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.viel.aplayer.data.ChapterEntity
import com.viel.aplayer.ui.theme.APlayerTheme
import com.viel.aplayer.ui.utils.formatTime

/**
 * 播放进度条组件。
 * 内部封装了“全书进度”与“当前章节进度”的切换逻辑。
 */
@Composable
fun PlaybackProgress(
    currentPosition: Long,
    totalDuration: Long,
    isChapterMode: Boolean,
    chapters: List<ChapterEntity>,
    markers: List<Float>,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    // 1. 实时查找当前位置所在的章节
    val currentChapter = remember(currentPosition, chapters) {
        chapters.findLast { currentPosition >= it.startPositionMs } ?: chapters.firstOrNull()
    }

    // 2. 根据模式计算显示参数
    val chapterStart = currentChapter?.startPositionMs ?: 0L
    val chapterEnd = if (currentChapter != null) chapterStart + currentChapter.durationMs else totalDuration

    // 确保时长计算有效，防止除以零
    val displayDur = if (isChapterMode) (chapterEnd - chapterStart).coerceAtLeast(1) else totalDuration.coerceAtLeast(1)
    val displayPos = if (isChapterMode) (currentPosition - chapterStart).coerceIn(0, displayDur) else currentPosition

    Column(modifier = modifier.fillMaxWidth()) {
        AudioProgressBar(
            progress = { displayPos.toFloat() / displayDur.toFloat() },
            onProgressChange = { newProgress ->
                val targetPos = if (isChapterMode) {
                    chapterStart + (newProgress * displayDur).toLong()
                } else {
                    (newProgress * totalDuration).toLong()
                }
                onSeek(targetPos)
            },
            // 在章节模式下隐藏全书的章节标记点
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
            
            if (chapters.isNotEmpty()) {
                val currentIndex = if (currentChapter != null) chapters.indexOf(currentChapter) else 0
                Text(
                    text = "${currentIndex + 1} / ${chapters.size}",
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
                chapters = emptyList(),
                markers = listOf(0.2f, 0.5f, 0.8f),
                onSeek = {},
                modifier = Modifier.padding(16.dp)
            )
        }
    }
}
