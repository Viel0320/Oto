package com.viel.oto.ui.player.components

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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.viel.oto.R
import com.viel.oto.application.library.player.PlayerChapterItem
import com.viel.oto.application.library.player.PlayerChapterTimeline
import com.viel.oto.shared.formatTime
import com.viel.oto.shared.settings.GlassEffectMode
import com.viel.oto.ui.common.theme.OtoTheme

/**
 * Renders playback progress in whole-book or current-chapter mode.
 *
 * The buffered position is rendered as a secondary track so remote-memory buffering is visible without changing seek semantics.
 */
@Composable
fun PlaybackProgress(
    currentPosition: Long,
    bufferedPosition: Long,
    totalDuration: Long,
    isChapterMode: Boolean,
    chapters: List<PlayerChapterItem>,
    markers: List<Float>,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
    glassEffectMode: GlassEffectMode = GlassEffectMode.Material,
) {
    val currentChapter = remember(currentPosition, chapters) {
        PlayerChapterTimeline.currentChapter(chapters, currentPosition)
    }

    val chapterStart = PlayerChapterTimeline.start(currentChapter)

    val displayDur = if (isChapterMode) {
        PlayerChapterTimeline.duration(chapters, currentChapter, totalDuration)
    } else {
        totalDuration.coerceAtLeast(1)
    }
    val displayPos = if (isChapterMode) {
        PlayerChapterTimeline.positionInChapter(chapters, currentChapter, currentPosition, totalDuration)
    } else {
        currentPosition.coerceIn(0L, displayDur)
    }
    val displayBufferedPos = if (isChapterMode) {
        PlayerChapterTimeline.positionInChapter(chapters, currentChapter, bufferedPosition, totalDuration)
    } else {
        bufferedPosition.coerceIn(displayPos, displayDur)
    }.coerceAtLeast(displayPos)

    Column(modifier = modifier.fillMaxWidth()) {
        AudioProgressBar(
            progress = { displayPos.toFloat() / displayDur.toFloat() },
            bufferedProgress = { displayBufferedPos.toFloat() / displayDur.toFloat() },
            onProgressChange = { newProgress ->
                val targetPos = if (isChapterMode) {
                    chapterStart + (newProgress * displayDur).toLong()
                } else {
                    (newProgress * totalDuration).toLong()
                }
                onSeek(targetPos)
            },
            markers = if (isChapterMode) emptyList() else markers,
            modifier = Modifier.fillMaxWidth(),
            glassEffectMode = glassEffectMode
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
                val currentIndex = PlayerChapterTimeline.currentIndex(chapters, currentChapter).coerceAtLeast(0)
                val chapterCounterText = stringResource(
                    R.string.player_chapter_counter,
                    currentIndex + 1,
                    chapters.size
                )
                Text(
                    text = chapterCounterText,
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
    OtoTheme {
        Surface {
            PlaybackProgress(
                currentPosition = 120000L,
                bufferedPosition = 220000L,
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
