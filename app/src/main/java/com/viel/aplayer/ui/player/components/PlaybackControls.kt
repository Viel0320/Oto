package com.viel.aplayer.ui.player.components

// Import fundamental background modifiers to resolve unresolved reference compilation errors on frosted glass big button background modifiers.
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Snooze
import androidx.compose.material.icons.rounded.Speed
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
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.viel.aplayer.R
import com.viel.aplayer.data.store.GlassEffectMode
import com.viel.aplayer.data.store.PlaybackSeekStepConfig
import com.viel.aplayer.event.feedback.FeedbackMessages
import com.viel.aplayer.media.SeekStepPresentation
import com.viel.aplayer.ui.common.theme.APlayerTheme
import com.viel.aplayer.ui.common.theme.LiquidGlassStyle
import com.viel.aplayer.ui.common.theme.liquidGlassCompatEffect
import com.viel.aplayer.ui.player.PlaybackControlActions
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalFoundationApi::class, ExperimentalHazeMaterialsApi::class)
@Composable
fun PlaybackControls(
    isPlaying: Boolean,
    playbackSpeed: Float,
    selectedSleepTimer: Int,
    isSpeedManualMode: Boolean,
    playbackSeekStepConfig: PlaybackSeekStepConfig,
    actions: PlaybackControlActions,
    modifier: Modifier = Modifier,
    buttonColor: Color = MaterialTheme.colorScheme.primaryContainer,
    glassEffectMode: GlassEffectMode = GlassEffectMode.Material,
    // Setup Haze State (Transition backdrop reference to HazeState)
    hazeState: HazeState? = null
) {
    val haptic = LocalHapticFeedback.current
    // Localized Playback Control Copy (Resolve button descriptions and compact timer badges)
    // Playback state values are runtime data, while speed labels, transport descriptions, and timer units are app-authored UI copy.
    val playbackSpeedContentDescription = stringResource(R.string.playback_speed_content_description)
    val playbackSpeedText = stringResource(R.string.playback_speed_value, playbackSpeed.toString())
    val rewindContentDescription = stringResource(SeekStepPresentation.backwardLabel(playbackSeekStepConfig.backward))
    val playPauseContentDescription = stringResource(
        if (isPlaying) R.string.playback_pause_content_description else R.string.playback_play_content_description
    )
    val forwardContentDescription = stringResource(SeekStepPresentation.forwardLabel(playbackSeekStepConfig.forward))
    val sleepTimerContentDescription = stringResource(R.string.settings_sleep_timer_title)

    // Speed Toast logic (Debounced)
    var lastSpeed by remember { mutableFloatStateOf(playbackSpeed) }
    LaunchedEffect(playbackSpeed) {
        if (playbackSpeed != lastSpeed) {
            // Debounce delay adjustment for speed selection.
            //
            // Adjusted to 1500 milliseconds (1.5 seconds) to provide a more sufficient buffer against accidental double clicks or spamming.
            delay(1500.milliseconds) // Wait for 1.5s of inactivity
            val message = if (playbackSpeed == 1.0f) {
                FeedbackMessages.playbackSpeedReset()
            } else {
                FeedbackMessages.playbackSpeedChanged(formatPlaybackSpeedForFeedback(playbackSpeed))
            }
            // Resource-Backed Speed Feedback (Route playback-speed tips through FeedbackMessage)
            // This removes English Toast literals from the control surface while preserving the existing debounced UX behavior.
            actions.onShowToast(message)
            lastSpeed = playbackSpeed
        }
    }

    // Sleep Timer Toast logic (Debounced)
    var lastTimer by remember { androidx.compose.runtime.mutableIntStateOf(selectedSleepTimer) }
    LaunchedEffect(selectedSleepTimer) {
        if (selectedSleepTimer != lastTimer) {
            // Debounce delay adjustment for sleep timer.
            //
            // Adjusted to 1500 milliseconds (1.5 seconds) to avoid spamming the screen with toast popups during rapid successive clicks.
            delay(1500.milliseconds) // Wait for 1.5s of inactivity
            val message = when (selectedSleepTimer) {
                0 -> FeedbackMessages.sleepTimerOff()
                -1 -> FeedbackMessages.sleepTimerFiveSeconds()
                -2 -> FeedbackMessages.sleepTimerEndOfChapter()
                else -> FeedbackMessages.sleepTimerMinutes(selectedSleepTimer)
            }
            // Resource-Backed Sleep Timer Feedback (Route timer tips through FeedbackMessage)
            // This keeps timer copy in string resources while the composable remains responsible only for debounce timing.
            actions.onShowToast(message)
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
                    Icons.Rounded.Speed,
                    contentDescription = playbackSpeedContentDescription,
                    modifier = Modifier.size(24.dp),
                    tint = MaterialTheme.colorScheme.onSurface
                )
            } else {
                Text(
                    text = playbackSpeedText,
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
                painter = painterResource(SeekStepPresentation.backwardIcon(playbackSeekStepConfig.backward)),
                contentDescription = rewindContentDescription,
                modifier = Modifier.size(32.dp)
            )
        }

        // Detect whether the frosted Gaussian blur effect is enabled, aligning with Haze and unifying renamed logic references.
        val isBlur = glassEffectMode == GlassEffectMode.Haze && hazeState != null

        if (isBlur) {
            // Play/pause button upgrade for Haze glass effect.
            //
            // Upgrade the play/pause button to a clear and dynamic frosted glass big button Surface when Haze is active.
            // Completely strip the physical dependency on real-time backdrop sampling, cascading an adaptive light/dark semi-transparent circular mask color,
            // and combining it with an adaptive locally-declared 0.5.dp shimmering silver stroke on top of the player's built-in large-radius blur(64.dp) background.
            // This constructs an iOS-grade outline lighting breathing visual effect and completely eliminates Feedback Loop crashes on Qualcomm Vulkan drivers during translation animations.
            val playPauseShape = CircleShape
            // Frosted glass play button transformation.
            //
            // 1. If the backdrop sampling source is present, use textureBlur to render physical Gaussian blur and add subtle frosted noise.
            // 2. Chain-overlay a specular glare layer with diagonal linear gradient to form a sparkling droplet reflective surface.
            // 3. Chain-append a 1.dp ultra-fine adaptive refraction edge gradient border to reshape the 3D delicate outline.
            // 4. If null, elegantly and safely degrade back to a semi-transparent material background without strokes to maintain ultimate stability.
            // Liquid Glass Play/Pause (Use custom liquidGlassCompatEffect to apply fluid glass highlight and border details) Apply custom glass effect with playPauseShape profile.
            val glassModifier = Modifier
                .size(80.dp)
                .clip(playPauseShape)
                .liquidGlassCompatEffect(
                    state = hazeState,
                    style = LiquidGlassStyle(shape = playPauseShape)
                )
            Surface(
                onClick = actions.onPlayPauseClick,
                modifier = glassModifier,
                shape = playPauseShape,
                color = Color.Transparent,
                border = null, // Fully delegated to the gradient border modifier above for rendering
                contentColor = MaterialTheme.colorScheme.primary
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = if (isPlaying) {
                            Icons.Rounded.Pause
                        } else {
                            Icons.Rounded.PlayArrow
                        },
                        contentDescription = playPauseContentDescription,
                        modifier = Modifier.size(40.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
        } else {
            // Maintain the original filled solid-color design of FilledIconButton in Material default mode.
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
                    imageVector = if (isPlaying) {
                        Icons.Rounded.Pause
                    } else {
                        Icons.Rounded.PlayArrow
                    },
                    contentDescription = playPauseContentDescription,
                    modifier = Modifier.size(40.dp)
                )
            }
        }

        IconButton(onClick = actions.onSkipForward, modifier = Modifier.size(56.dp)) {
            Icon(
                painter = painterResource(SeekStepPresentation.forwardIcon(playbackSeekStepConfig.forward)),
                contentDescription = forwardContentDescription,
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
                    Icons.Rounded.Snooze,
                    contentDescription = sleepTimerContentDescription,
                    modifier = Modifier.size(24.dp),
                    tint = MaterialTheme.colorScheme.onSurface
                )
            } else {
                val displayText = when (selectedSleepTimer) {
                    -1 -> stringResource(R.string.playback_sleep_timer_seconds_short, 5)
                    -2 -> stringResource(R.string.playback_sleep_timer_chapter_short)
                    else -> stringResource(R.string.playback_sleep_timer_minutes_short, selectedSleepTimer)
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
                playbackSeekStepConfig = PlaybackSeekStepConfig(),
                actions = PlaybackControlActions(),
                modifier = Modifier.padding(16.dp)
            )
        }
    }
}

private fun formatPlaybackSpeedForFeedback(speed: Float): String {
    // Playback Speed Formatting (Keep feedback arguments copy-neutral)
    // Trims the trailing decimal from whole-number speeds while preserving fractional values such as 0.75 or 1.25.
    val text = speed.toString()
    return text.trimEnd('0').trimEnd('.')
}
