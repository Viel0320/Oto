package com.viel.aplayer.ui.settings.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.viel.aplayer.R
import com.viel.aplayer.data.store.SeekStepSeconds
import com.viel.aplayer.data.store.SleepMode
import com.viel.aplayer.data.store.ThemeMode
import com.viel.aplayer.ui.common.formatFileSize

/**
 * Settings Segmented Items (Own multi-choice Settings controls)
 * Segmented controls are split from simple rows because they carry option ordering, selected-state rendering, and explanatory copy together.
 */


/**
 * Settings Segmented Sleep Mode Item (Renders sleep countdown strategy choices)
 * Keeps the three sleep modes in one component so the timer section can focus on feature grouping rather than button-row mechanics.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSegmentedSleepModeItem(
    title: String,
    subtitle: String,
    icon: ImageVector,
    selectedMode: SleepMode,
    onModeSelected: (SleepMode) -> Unit
) {
    val modes = listOf(SleepMode.Regular, SleepMode.MotionTracking, SleepMode.SleepTracking)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(24.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.titleMedium)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))
            // Sleep Mode Selection Group Semantics (Rely on Material3's built-in selectable group row)
            // The surrounding settings row stays a passive layout container because only an individual segment can identify the requested sleep mode.
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                modes.forEachIndexed { index, mode ->
                    SegmentedButton(
                        selected = selectedMode == mode,
                        onClick = { onModeSelected(mode) },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = modes.size)
                    ) {
                        Text(
                            text = when (mode) {
                                SleepMode.Regular -> stringResource(R.string.settings_sleep_mode_regular)
                                SleepMode.MotionTracking -> stringResource(R.string.settings_sleep_mode_motion_tracking)
                                SleepMode.SleepTracking -> stringResource(R.string.settings_sleep_mode_sleep_tracking)
                            },
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = when (selectedMode) {
                    SleepMode.Regular -> stringResource(R.string.settings_sleep_mode_regular_description)
                    SleepMode.MotionTracking -> stringResource(R.string.settings_sleep_mode_motion_tracking_description)
                    SleepMode.SleepTracking -> stringResource(R.string.settings_sleep_mode_sleep_tracking_description)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.85f)
            )
        }
    }
}

/**
 * Settings Segmented Theme Mode Item (Renders light/dark/system theme choices)
 * Keeps Haze-disabled state handling inside the visual control so appearance sections do not duplicate segmented-button rules.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSegmentedThemeModeItem(
    title: String,
    subtitle: String,
    icon: ImageVector,
    selectedMode: ThemeMode,
    onModeSelected: (ThemeMode) -> Unit,
    enabled: Boolean = true
) {
    val modes = listOf(ThemeMode.System, ThemeMode.Light, ThemeMode.Dark)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (enabled) Modifier else Modifier.alpha(0.38f))
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(24.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.titleMedium)
            Text(
                text = if (enabled) subtitle else stringResource(R.string.settings_haze_forced_dark_subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))
            // Theme Mode Selection Group Semantics (Rely on Material3's built-in selectable group row)
            // Keeping the group marker inside the segmented button row lets accessibility services announce radio-style choices without making the full settings row clickable.
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                modes.forEachIndexed { index, mode ->
                    SegmentedButton(
                        selected = selectedMode == mode,
                        onClick = { onModeSelected(mode) },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = modes.size),
                        enabled = enabled
                    ) {
                        Text(
                            text = when (mode) {
                                ThemeMode.System -> stringResource(R.string.settings_theme_mode_system)
                                ThemeMode.Light -> stringResource(R.string.settings_theme_mode_light)
                                ThemeMode.Dark -> stringResource(R.string.settings_theme_mode_dark)
                            },
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
            }
        }
    }
}

/**
 * Settings Segmented Seek Step Item (Renders constrained short-seek increments)
 * Keeps the 10/20/30-second option set in one reusable component so playback behavior settings cannot drift between backward and forward rows.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSegmentedSeekStepItem(
    title: String,
    subtitle: String,
    icon: ImageVector,
    selectedStep: SeekStepSeconds,
    onStepSelected: (SeekStepSeconds) -> Unit
) {
    val steps = SeekStepSeconds.supported
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(24.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.titleMedium)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))
            // Seek Step Selection Group Semantics (Rely on Material3's built-in selectable group row)
            // The row title and subtitle remain descriptive context while each segment owns the concrete step-change action.
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                steps.forEachIndexed { index, step ->
                    SegmentedButton(
                        selected = selectedStep == step,
                        onClick = { onStepSelected(step) },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = steps.size)
                    ) {
                        Text(
                            text = stringResource(R.string.settings_seek_step_seconds_option, step.seconds),
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
            }
        }
    }
}

/**
 * Playback Buffer Size Option projection model.
 * Associates concrete byte allocations to localized readable file size descriptions.
 */
private data class PlaybackBufferSizeOption(
    val bytes: Long,
    val label: String
)

/**
 * Generates options for playback buffer allocations.
 * Defines four predefined memory boundaries: 32MB, 64MB, 128MB, and 256MB.
 */
private fun playbackBufferSizeOptions(): List<PlaybackBufferSizeOption> =
    listOf(
        64L * 1024L * 1024L,
        128L * 1024L * 1024L,
        256L * 1024L * 1024L
    ).map { bytes ->
        PlaybackBufferSizeOption(
            bytes = bytes,
            label = formatFileSize(bytes)
        )
    }

/**
 * SettingsSegmentedPlaybackBufferItem Composable.
 * Renders a segmented selection bar inside the main settings layout to configure the player's max buffer capacity in memory.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSegmentedPlaybackBufferItem(
    selectedBytes: Long,
    onSelected: (Long) -> Unit
) {
    val options = remember { playbackBufferSizeOptions() }
    val resolvedSelectedBytes = options.firstOrNull { option -> option.bytes == selectedBytes }?.bytes
        ?: com.viel.aplayer.data.store.AppSettings.DEFAULT_PLAYBACK_BUFFER_MAX_BYTES

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Rounded.Storage,
            contentDescription = null,
            modifier = Modifier.size(24.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.settings_cache_playback_capacity_title),
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                text = stringResource(R.string.settings_cache_playback_capacity_subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                options.forEachIndexed { index, option ->
                    SegmentedButton(
                        selected = resolvedSelectedBytes == option.bytes,
                        onClick = { onSelected(option.bytes) },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size)
                    ) {
                        Text(
                            text = option.label,
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
            }
        }
    }
}

