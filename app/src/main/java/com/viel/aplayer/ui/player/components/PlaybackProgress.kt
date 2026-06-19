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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.viel.aplayer.R
import com.viel.aplayer.application.library.player.PlayerChapterItem
import com.viel.aplayer.application.library.player.PlayerChapterTimeline
import com.viel.aplayer.shared.formatTime
import com.viel.aplayer.shared.settings.GlassEffectMode
import com.viel.aplayer.ui.common.theme.APlayerTheme

/**
 * Playback progress bar component (PlaybackProgress).
 *
 * It internally encapsulates the switching logic between "entire book progress" and "current chapter progress".
 * The buffered position is rendered as a secondary track so remote-memory buffering is visible without changing seek semantics.
 */
@Composable
// The dynamic coloring functionality from cover palette extraction has been removed; the color parameter has been removed here, and the progress bar directly falls back to the system default Material 3 primary color to improve performance and UI consistency.
fun PlaybackProgress(
    currentPosition: Long,
    bufferedPosition: Long,
    totalDuration: Long,
    isChapterMode: Boolean,
    chapters: List<PlayerChapterItem>,
    markers: List<Float>,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
    // Adaptive glass effect mode parameter has been added.
    glassEffectMode: GlassEffectMode = GlassEffectMode.Material,
) {
    // 1. Find the chapter corresponding to the current position in real-time
    val currentChapter = remember(currentPosition, chapters) {
        // Unify chapter positioning to avoid UI and the notification bar searching for chapters using different ordering.
        PlayerChapterTimeline.currentChapter(chapters, currentPosition)
    }

    // 2. Calculate display parameters according to the mode
    val chapterStart = PlayerChapterTimeline.start(currentChapter)

    // Ensure duration calculations are valid to prevent division by zero.
    // The durationMs of embedded chapters in single files might be unreliable, so the chapter mode uniformly calculates based on adjacent start times and total duration.
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
        // The cover color extraction binding of the progress bar has been removed here, and the custom color attribute is no longer passed, causing AudioProgressBar to automatically return to the Material 3 primary color.
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
            // Hide the entire book's chapter markers when in chapter mode
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
                // The serial numbers use the same sorting result to guarantee that the display order matches the progress boundaries.
                val currentIndex = PlayerChapterTimeline.currentIndex(chapters, currentChapter).coerceAtLeast(0)
                // Localized Chapter Counter Copy (Format chapter count text through resources)
                // Chapter indices are runtime playback data, while the separator format is app-authored UI copy.
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
    APlayerTheme {
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
