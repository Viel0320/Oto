package com.viel.aplayer.ui.settings

import androidx.compose.foundation.clickable
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
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.viel.aplayer.data.store.SleepMode
import com.viel.aplayer.data.store.ThemeMode

/**
 * SettingsSectionHeader Composable: Renders a section header with bold title text to separate settings lists.
 */
@Composable
fun SettingsSectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, top = 24.dp, end = 16.dp, bottom = 8.dp)
    )
}

/**
 * SettingsItem Composable: Renders a basic settings item layout with a click listener, description, and key icon.
 */
@Composable
fun SettingsItem(
    title: String,
    icon: ImageVector,
    subtitle: String? = null,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
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
 * SettingsToggleItem Composable: Renders a settings row containing a toggle Switch element.
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
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { onCheckedChange(!checked) }
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
                text = subtitle, 
                style = MaterialTheme.typography.bodySmall, 
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled
        )
    }
}

/**
 * SettingsSliderItem Composable: Provides a continuous numeric slider control component.
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
                text = "$subtitle: ${valueFormatter(value)}",
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

/**
 * SettingsSegmentedSleepModeItem Composable: Displays segmented buttons for selecting sleep countdown strategies.
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
                                SleepMode.Regular -> "常规模式"
                                SleepMode.MotionTracking -> "运动跟踪"
                                SleepMode.SleepTracking -> "睡眠检测"
                            },
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = when (selectedMode) {
                    SleepMode.Regular -> "说明：倒计时启动后持续计时，并在倒计时结束时暂停播放。"
                    SleepMode.MotionTracking -> "说明：设备静止时才扣减时间，一旦移动手机则自动暂停倒计时一分钟。"
                    SleepMode.SleepTracking -> "说明：设备接近静止 10 分钟（判定入睡，保留微小动作容错）后，启动倒计时。"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.85f)
            )
        }
    }
}

/**
 * SettingsSegmentedThemeModeItem Composable: Displays segmented buttons for selecting theme configurations.
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
                text = if (enabled) subtitle else "开启模糊效果时强制为深色",
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
                                ThemeMode.System -> "跟随系统"
                                ThemeMode.Light -> "浅色"
                                ThemeMode.Dark -> "深色"
                            },
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
            }
        }
    }
}
