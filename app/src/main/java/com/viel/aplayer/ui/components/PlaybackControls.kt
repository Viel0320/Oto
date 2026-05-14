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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.viel.aplayer.R
import com.viel.aplayer.ui.action.PlaybackControlActions
import com.viel.aplayer.ui.theme.APlayerTheme

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PlaybackControls(
    isPlaying: Boolean,
    playbackSpeed: Float,
    selectedSleepTimer: Int,
    isSpeedManualMode: Boolean,
    actions: PlaybackControlActions,
    modifier: Modifier = Modifier,
    buttonColor: Color = MaterialTheme.colorScheme.primaryContainer
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current

    // Speed Toast logic
    var lastSpeed by remember { mutableFloatStateOf(playbackSpeed) }
    LaunchedEffect(playbackSpeed) {
        if (playbackSpeed != lastSpeed) {
            val msg = if (playbackSpeed == 1.0f) "Playback speed reset" else "Playback speed: ${playbackSpeed}x"
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
            lastSpeed = playbackSpeed
        }
    }

    // Sleep Timer Toast logic
    var lastTimer by remember { androidx.compose.runtime.mutableIntStateOf(selectedSleepTimer) }
    LaunchedEffect(selectedSleepTimer) {
        if (selectedSleepTimer != lastTimer) {
            val msg = when (selectedSleepTimer) {
                0 -> "Sleep timer off"
                -1 -> "Sleep in 5 seconds"
                -2 -> "Stop at end of chapter"
                else -> "Sleep in $selectedSleepTimer minutes"
            }
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
            lastTimer = selectedSleepTimer
        }
    }

    val contentColor = if (buttonColor.luminance() > 0.5f) Color.Black else Color.White

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(CircleShape)
                .combinedClickable(
                    onClick = actions.onCyclePlaybackSpeed,
                    onLongClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        actions.onResetPlaybackSpeed()
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

        IconButton(onClick = actions.onSkipBackward, modifier = Modifier.size(56.dp)) {
            Icon(
                painterResource(R.drawable.ic_rounded_replay_10),
                contentDescription = "Rewind 10 seconds",
                modifier = Modifier.size(32.dp)
            )
        }

        FilledIconButton(
            onClick = actions.onPlayPauseClick,
            modifier = Modifier.size(80.dp),
            shape = CircleShape,
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = buttonColor,
                contentColor = contentColor
            )
        ) {
            Icon(
                painter = if (isPlaying) {
                    painterResource(R.drawable.ic_rounded_pause)
                } else {
                    painterResource(R.drawable.ic_rounded_play_arrow)
                },
                contentDescription = if (isPlaying) "Pause" else "Play",
                modifier = Modifier.size(40.dp)
            )
        }

        IconButton(onClick = actions.onSkipForward, modifier = Modifier.size(56.dp)) {
            Icon(
                painterResource(R.drawable.ic_rounded_forward_30),
                contentDescription = "Forward 30 seconds",
                modifier = Modifier.size(32.dp)
            )
        }

        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(CircleShape)
                .combinedClickable(
                    onClick = actions.onCycleSleepTimer,
                    onLongClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        actions.onCancelSleepTimer()
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
                val displayText = when (selectedSleepTimer) {
                    -1 -> "5s"
                    -2 -> "Ch"
                    else -> "${selectedSleepTimer}m"
                }
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
                isSpeedManualMode = false,
                actions = PlaybackControlActions(),
                modifier = Modifier.padding(16.dp)
            )
        }
    }
}
