package com.viel.oto.ui.settings.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.viel.oto.R
import com.viel.oto.shared.formatFileSize
import com.viel.oto.shared.settings.GlassEffectMode
import com.viel.oto.shared.settings.SeekStepSeconds
import com.viel.oto.shared.settings.SleepMode
import com.viel.oto.shared.settings.ThemeMode
import com.viel.oto.ui.common.OtoPopupSelector
import com.viel.oto.ui.common.OtoPopupWidth
import com.viel.oto.ui.common.aPlayerTextPopupItem
import com.viel.oto.ui.common.layout.LocalAppWindowSizeClass
import dev.chrisbanes.haze.HazeState

/**
 * Own multi-choice Settings controls.
 * Segmented controls are split from simple rows because they carry option ordering, selected-state rendering, and explanatory copy together.
 */


/**
 * Renders sleep countdown strategy choices through the shared dropdown.
 * Keeps the historical function boundary for Settings call sites while replacing the former segmented row with OtoPopupSelector.
 * Haze parameters are passed through explicitly so the trailing dropdown samples the same settings-page backdrop as the rest of the page chrome.
 */
@Composable
fun SettingsSegmentedSleepModeItem(
    title: String,
    subtitle: String,
    icon: ImageVector,
    selectedMode: SleepMode,
    onModeSelected: (SleepMode) -> Unit,
    glassEffectMode: GlassEffectMode = GlassEffectMode.Material,
    hazeState: HazeState? = null
) {
    val modes = listOf(SleepMode.Regular, SleepMode.MotionTracking, SleepMode.SleepTracking)
    val rowHorizontalPadding = LocalAppWindowSizeClass.current.screenHorizontalPadding
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = rowHorizontalPadding, vertical = 16.dp),
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
            Spacer(modifier = Modifier.height(4.dp))
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
        Spacer(modifier = Modifier.width(12.dp))
        SettingsDropdownControl(
            options = modes.map { mode ->
                SettingsDropdownOption(
                    value = mode,
                    label = when (mode) {
                        SleepMode.Regular -> stringResource(R.string.settings_sleep_mode_regular)
                        SleepMode.MotionTracking -> stringResource(R.string.settings_sleep_mode_motion_tracking)
                        SleepMode.SleepTracking -> stringResource(R.string.settings_sleep_mode_sleep_tracking)
                    }
                )
            },
            selectedValue = selectedMode,
            onSelected = onModeSelected,
            glassEffectMode = glassEffectMode,
            hazeState = hazeState
        )
    }
}

/**
 * Renders light/dark/system theme choices through the shared dropdown.
 *
 * The control keeps the existing Settings API and delegates option rendering to OtoPopupSelector so
 * appearance mode selection shares the same dropdown treatment and explicit Haze backdrop as the other Settings selectors.
 */
@Composable
fun SettingsSegmentedThemeModeItem(
    title: String,
    subtitle: String,
    icon: ImageVector,
    selectedMode: ThemeMode,
    onModeSelected: (ThemeMode) -> Unit,
    glassEffectMode: GlassEffectMode = GlassEffectMode.Material,
    hazeState: HazeState? = null
) {
    val modes = listOf(ThemeMode.System, ThemeMode.Light, ThemeMode.Dark)
    val rowHorizontalPadding = LocalAppWindowSizeClass.current.screenHorizontalPadding
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = rowHorizontalPadding, vertical = 16.dp),
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
        }
        Spacer(modifier = Modifier.width(12.dp))
        SettingsDropdownControl(
            options = modes.map { mode ->
                SettingsDropdownOption(
                    value = mode,
                    label = when (mode) {
                        ThemeMode.System -> stringResource(R.string.settings_theme_mode_system)
                        ThemeMode.Light -> stringResource(R.string.settings_theme_mode_light)
                        ThemeMode.Dark -> stringResource(R.string.settings_theme_mode_dark)
                    }
                )
            },
            selectedValue = selectedMode,
            onSelected = onModeSelected,
            glassEffectMode = glassEffectMode,
            hazeState = hazeState
        )
    }
}

/**
 * Renders constrained short-seek increments through the shared dropdown.
 * Keeps the 10/20/30-second option set in one reusable component so playback behavior settings cannot drift between backward and forward rows.
 * Haze parameters remain explicit because seek-step dropdowns are page controls, not standalone popups with their own sampled source.
 */
@Composable
fun SettingsSegmentedSeekStepItem(
    title: String,
    subtitle: String,
    icon: ImageVector,
    selectedStep: SeekStepSeconds,
    onStepSelected: (SeekStepSeconds) -> Unit,
    glassEffectMode: GlassEffectMode = GlassEffectMode.Material,
    hazeState: HazeState? = null
) {
    val steps = SeekStepSeconds.supported
    val rowHorizontalPadding = LocalAppWindowSizeClass.current.screenHorizontalPadding
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = rowHorizontalPadding, vertical = 16.dp),
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
        }
        Spacer(modifier = Modifier.width(12.dp))
        SettingsDropdownControl(
            options = steps.map { step ->
                SettingsDropdownOption(
                    value = step,
                    label = stringResource(R.string.settings_seek_step_seconds_option, step.seconds)
                )
            },
            selectedValue = selectedStep,
            onSelected = onStepSelected,
            glassEffectMode = glassEffectMode,
            hazeState = hazeState
        )
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
 * Renders an OtoPopupSelector inside the main settings layout to configure the player's max buffer capacity in memory.
 * Explicit Haze parameters keep cache-capacity dropdown rendering aligned with the active Settings page visual effect mode.
 */
@Composable
fun SettingsSegmentedPlaybackBufferItem(
    selectedBytes: Long,
    onSelected: (Long) -> Unit,
    glassEffectMode: GlassEffectMode = GlassEffectMode.Material,
    hazeState: HazeState? = null
) {
    val options = remember { playbackBufferSizeOptions() }
    val resolvedSelectedBytes = options.firstOrNull { option -> option.bytes == selectedBytes }?.bytes
        ?: com.viel.oto.shared.settings.AppSettings.DEFAULT_PLAYBACK_BUFFER_MAX_BYTES
    val rowHorizontalPadding = LocalAppWindowSizeClass.current.screenHorizontalPadding

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = rowHorizontalPadding, vertical = 16.dp),
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
        }
        Spacer(modifier = Modifier.width(12.dp))
        SettingsDropdownControl(
            options = options.map { option ->
                SettingsDropdownOption(
                    value = option.bytes,
                    label = option.label
                )
            },
            selectedValue = resolvedSelectedBytes,
            onSelected = onSelected,
            glassEffectMode = glassEffectMode,
            hazeState = hazeState
        )
    }
}

/**
 * Dropdown option projection for Settings selectors.
 * Stores the domain value next to its localized label so each settings item can keep enum or byte
 * ownership while sharing one OtoPopupSelector adapter.
 */
private data class SettingsDropdownOption<T : Any>(
    val value: T,
    val label: String
)

/**
 * Shared Settings dropdown adapter.
 * Bridges existing single-choice Settings values to a trailing OtoPopupSelector without changing the
 * caller-facing composable APIs or moving selection persistence out of the existing Settings actions.
 * The visual-effect parameters are kept at this boundary so every converted selector uses the same Haze contract.
 */
@Composable
private fun <T : Any> SettingsDropdownControl(
    options: List<SettingsDropdownOption<T>>,
    selectedValue: T,
    onSelected: (T) -> Unit,
    glassEffectMode: GlassEffectMode,
    hazeState: HazeState?
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedIndex = options.indexOfFirst { option -> option.value == selectedValue }
        .takeIf { index -> index >= 0 }
    val items = options.map { option ->
        aPlayerTextPopupItem(
            key = option.value,
            label = option.label
        )
    }

    OtoPopupSelector(
        items = items,
        expanded = expanded,
        onExpandedChange = { expanded = it },
        onSelect = { index -> options.getOrNull(index)?.value?.let(onSelected) },
        // These settings remain a single-choice control after the visual migration from segmented
        // buttons to a dropdown, so TalkBack should still receive a grouped choice boundary.
        modifier = Modifier.selectableGroup(),
        selectedIndex = selectedIndex,
        panelWidth = OtoPopupWidth.Wrap,
        hazeState = hazeState,
        glassEffectMode = glassEffectMode
    )
}
