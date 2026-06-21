package com.viel.aplayer.ui.settings.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.viel.aplayer.R
import com.viel.aplayer.ui.common.layout.LocalAppWindowSizeClass

/**
 * Own reusable single-row controls for Settings sections.
 * These primitives are split from SettingsSections so each functional cluster can compose rows without carrying low-level layout details.
 */

/**
 * Renders a clickable navigation/action row.
 * Used for settings entries that trigger another flow rather than directly mutating a persisted preference.
 * Horizontal row padding follows AppWindowSizeClass so every settings row can share one viewport gutter.
 */
@Composable
fun SettingsItem(
    title: String,
    icon: ImageVector,
    subtitle: String? = null,
    onClick: () -> Unit
) {
    val rowHorizontalPadding = LocalAppWindowSizeClass.current.screenHorizontalPadding

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
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
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * Renders a boolean preference row.
 * Keeps the row click target and Switch callback aligned so touch and switch interactions update the same setting.
 * Horizontal row padding follows AppWindowSizeClass while the vertical density remains fixed for settings rhythm.
 */
@Composable
fun SettingsToggleItem(
    title: String,
    subtitle: String,
    icon: ImageVector,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true
) {
    val rowHorizontalPadding = LocalAppWindowSizeClass.current.screenHorizontalPadding
    val toggleStateDescription = stringResource(
        if (checked) {
            R.string.settings_toggle_state_on
        } else {
            R.string.settings_toggle_state_off
        }
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Switch,
                onValueChange = onCheckedChange
            )
            .semantics(mergeDescendants = true) {
                stateDescription = toggleStateDescription
            }
            .then(if (enabled) Modifier else Modifier.alpha(0.38f))
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
        Spacer(modifier = Modifier.width(4.dp))
        Switch(
            checked = checked,
            onCheckedChange = null,
            enabled = enabled,
            modifier = Modifier.clearAndSetSemantics {}
        )
    }
}

/**
 * Renders a numeric preference row.
 * Keeps label, formatted value, and slider mechanics together for settings that expose bounded numeric controls.
 * Horizontal row padding follows AppWindowSizeClass to stay aligned with adjacent settings controls.
 */
@Composable
fun SettingsSliderItem(
    title: String,
    subtitle: String,
    icon: ImageVector,
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    valueFormatter: (Float) -> String,
    enabled: Boolean = true
) {
    val rowHorizontalPadding = LocalAppWindowSizeClass.current.screenHorizontalPadding
    val valueSummaryText = stringResource(R.string.settings_slider_value_summary, subtitle, valueFormatter(value))

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (enabled) Modifier else Modifier.alpha(0.38f))
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
                text = valueSummaryText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Slider(
                value = value,
                onValueChange = onValueChange,
                valueRange = valueRange,
                steps = steps,
                enabled = enabled,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}
