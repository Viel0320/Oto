package com.viel.aplayer.ui.player.components

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
import com.viel.aplayer.data.entity.ChapterEntity
import com.viel.aplayer.media.ChapterTimeline
import com.viel.aplayer.ui.common.AudioProgressBar
import com.viel.aplayer.ui.common.formatTime
import com.viel.aplayer.ui.theme.APlayerTheme

/**
 * 播放进度条组件。
 * 内部封装了“全书进度”与“当前章节进度”的切换逻辑。
 */
@Composable
// 中文注释：已取消封面取色动态着色功能，此处的 color 参数已被移除，进度条直接回退至系统默认的 Material 3 主色调以提升性能和UI一致性
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
        // 统一章节定位，避免 UI 和通知栏各自按不同顺序查找章节。
        ChapterTimeline.currentChapter(chapters, currentPosition)
    }

    // 2. 根据模式计算显示参数
    val chapterStart = ChapterTimeline.start(currentChapter)

    // 确保时长计算有效，防止除以零
    // 单文件内嵌章节的 durationMs 可能不可靠，章节模式统一按相邻 start/总时长计算。
    val displayDur = if (isChapterMode) {
        ChapterTimeline.duration(chapters, currentChapter, totalDuration)
    } else {
        totalDuration.coerceAtLeast(1)
    }
    val displayPos = if (isChapterMode) {
        ChapterTimeline.positionInChapter(chapters, currentChapter, currentPosition, totalDuration)
    } else {
        currentPosition.coerceIn(0L, displayDur)
    }

    Column(modifier = modifier.fillMaxWidth()) {
        // 中文注释：已在此处取消了进度条的封面取色绑定，不再传入自定义 color 属性，使 AudioProgressBar 自动回归为 Material 3 主色调
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
                // 序号使用同一排序结果，保证显示顺序和进度边界一致。
                val currentIndex = ChapterTimeline.currentIndex(chapters, currentChapter).coerceAtLeast(0)
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