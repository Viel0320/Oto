package com.viel.aplayer.ui.components

import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.tooling.preview.Preview
import com.viel.aplayer.R
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.viel.aplayer.ui.theme.APlayerTheme

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PlaybackControls(
    isPlaying: Boolean,
    playbackSpeed: Float,
    selectedSleepTimer: Int,
    onPlayPauseClick: () -> Unit,
    onSkipForward: () -> Unit,
    onSkipBackward: () -> Unit,
    onSpeedChange: (Float) -> Unit,
    onSleepTimerChange: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    
    // Track if we are in manual cycling mode or initial/reset state
    var isSpeedManualMode by remember { mutableStateOf(playbackSpeed != 1.0f) }
    
    val speeds = listOf(0.25f, 0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f)
    val sleepOptions = listOf(0, -1, 10, 15, 30, 45, 60)

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Speed Control
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(CircleShape)
                .combinedClickable(
                    onClick = {
                        isSpeedManualMode = true
                        val currentIndex = speeds.indexOf(playbackSpeed).coerceAtLeast(0)
                        val nextIndex = (currentIndex + 1) % speeds.size
                        onSpeedChange(speeds[nextIndex])
                    },
                    onLongClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        isSpeedManualMode = false
                        onSpeedChange(1.0f)
                        Toast.makeText(context, "倍速已关闭", Toast.LENGTH_SHORT).show()
                    }
                ),
            contentAlignment = Alignment.Center
        ) {
            if (playbackSpeed == 1.0f && !isSpeedManualMode) {
                Icon(
                    painterResource(R.drawable.ic_rounded_speed),
                    contentDescription = "Playback Speed",
                    modifier = Modifier.size(24.dp),
                    tint = MaterialTheme.colorScheme.onSurface
                )
            } else {
                Text(
                    text = "${playbackSpeed}x",
                    style = MaterialTheme.typography.labelLarge.copy(
                        fontSize = 12.sp,
                        fontWeight = FontWeight.ExtraBold
                    ),
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }

        // Backward
        IconButton(onClick = onSkipBackward, modifier = Modifier.size(56.dp)) {
            Icon(
                painterResource(R.drawable.ic_rounded_replay_10), 
                contentDescription = "Rewind 10 seconds", 
                modifier = Modifier.size(32.dp)
            )
        }

        // Play/Pause
        FilledIconButton(
            onClick = onPlayPauseClick,
            modifier = Modifier.size(80.dp),
            shape = CircleShape,
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer
            )
        ) {
            Icon(
                painter = if (isPlaying) painterResource(R.drawable.ic_rounded_pause) else painterResource(R.drawable.ic_rounded_play_arrow),
                contentDescription = if (isPlaying) "Pause" else "Play",
                modifier = Modifier.size(40.dp)
            )
        }

        // Forward
        IconButton(onClick = onSkipForward, modifier = Modifier.size(56.dp)) {
            Icon(
                painterResource(R.drawable.ic_rounded_forward_30), 
                contentDescription = "Forward 30 seconds", 
                modifier = Modifier.size(32.dp)
            )
        }

        // Sleep Timer
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(CircleShape)
                .combinedClickable(
                    onClick = {
                        val currentIndex = sleepOptions.indexOf(selectedSleepTimer).coerceAtLeast(0)
                        val nextIndex = (currentIndex + 1) % sleepOptions.size
                        onSleepTimerChange(sleepOptions[nextIndex])
                    },
                    onLongClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onSleepTimerChange(0)
                        Toast.makeText(context, "定时已关闭", Toast.LENGTH_SHORT).show()
                    }
                ),
            contentAlignment = Alignment.Center
        ) {
            if (selectedSleepTimer == 0) {
                Icon(
                    painterResource(R.drawable.ic_rounded_snooze),
                    contentDescription = "Sleep Timer",
                    modifier = Modifier.size(24.dp),
                    tint = MaterialTheme.colorScheme.onSurface
                )
            } else {
                val displayText = if (selectedSleepTimer == -1) "5s" else "${selectedSleepTimer}m"
                Text(
                    text = displayText,
                    style = MaterialTheme.typography.labelLarge.copy(
                        fontSize = 12.sp,
                        fontWeight = FontWeight.ExtraBold
                    ),
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

@Preview(apiLevel = 36)
@Composable
fun PlaybackControlsPreview() {
    APlayerTheme {
        Surface {
            PlaybackControls(
                isPlaying = false,
                playbackSpeed = 1.0f,
                selectedSleepTimer = 0,
                onPlayPauseClick = {},
                onSkipForward = {},
                onSkipBackward = {},
                onSpeedChange = {},
                onSleepTimerChange = {},
                modifier = Modifier.padding(16.dp)
            )
        }
    }
}
