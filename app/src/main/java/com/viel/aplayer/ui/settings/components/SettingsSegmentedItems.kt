package com.viel.aplayer.ui.settings.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.viel.aplayer.R
import com.viel.aplayer.data.store.SleepMode
import com.viel.aplayer.data.store.ThemeMode

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
