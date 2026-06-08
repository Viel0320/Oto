package com.viel.aplayer.ui.settings.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.LinearScale
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.viel.aplayer.R
import com.viel.aplayer.data.db.AudiobookSchema
import com.viel.aplayer.data.store.AppLanguage
import com.viel.aplayer.data.store.GlassEffectMode
import com.viel.aplayer.data.store.SleepMode
import com.viel.aplayer.data.store.ThemeMode
import com.viel.aplayer.ui.settings.SettingsRootItem
import com.viel.aplayer.ui.settings.appLanguageLabel

/**
 * LibraryDirectoriesSection Composable: Renders media library folder locations and sync history statuses.
 */
@Composable
fun LibraryDirectoriesSection(
    libraryRootDisplays: List<SettingsRootItem>,
    onRootClick: (SettingsRootItem) -> Unit,
    onAddLibraryClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        SettingsSectionHeader(title = stringResource(R.string.settings_library_management_title))
        libraryRootDisplays.forEach { display ->
            // Settings Root Row Source Flags (Drive row icon and ABS-only error details from the scene item)
            // The list no longer needs persistence root rows because SettingsRootItem carries the storage type and rendered text directly.
            val isWebDavRoot = display.sourceType == AudiobookSchema.LibrarySourceType.WEBDAV
            val isAbsRoot = display.sourceType == AudiobookSchema.LibrarySourceType.ABS
            val locationLine = display.selectedLibraryText
                ?.takeIf { it.isNotBlank() }
                ?.let { libraryName ->
                    stringResource(R.string.settings_library_location_with_abs, display.locationText, libraryName)
                }
                ?: stringResource(R.string.settings_library_location, display.locationText)
            
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onRootClick(display) }
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = if (isWebDavRoot || isAbsRoot) Icons.Rounded.Cloud else Icons.Rounded.FolderOpen,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = display.title,
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            text = display.statusText,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = locationLine,
                        style = MaterialTheme.typography.bodySmall, 
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = stringResource(
                            R.string.settings_library_sync_summary,
                            display.lastSyncText,
                            display.importedBookCount
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (isAbsRoot && display.lastError?.isNotBlank() == true) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = stringResource(R.string.settings_library_error, display.lastError),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        }
        SettingsItem(
            title = stringResource(R.string.settings_add_library_title),
            subtitle = stringResource(R.string.settings_add_library_subtitle),
            // Add Library Icon (Use the Material Design plus symbol for creation)
            // The row opens a new-library flow rather than representing an existing folder, so the plus icon communicates an additive action more directly.
            icon = Icons.Rounded.Add,
            onClick = onAddLibraryClick
        )
    }
}

/**
 * InterfaceSettingsSection Composable: Handles themes, dynamic color settings, and experimental blur effect toggles.
 */
@Composable
fun InterfaceSettingsSection(
    appLanguage: AppLanguage,
    onLanguageClick: () -> Unit,
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
    isDynamicColorEnabled: Boolean,
    onDynamicColorEnabledChange: (Boolean) -> Unit,
    glassEffectMode: GlassEffectMode,
    onGlassEffectModeChange: (GlassEffectMode) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        SettingsSectionHeader(title = stringResource(R.string.settings_interface_effects_title))
        SettingsItem(
            title = stringResource(R.string.settings_language_title),
            subtitle = appLanguageLabel(appLanguage),
            // Language Setting Icon (Use the platform-recognizable translate symbol for locale selection)
            // This row opens the app language picker instead of changing playback or library behavior.
            icon = Icons.Rounded.Translate,
            onClick = onLanguageClick
        )
        val isHaze = glassEffectMode == GlassEffectMode.Haze
        SettingsSegmentedThemeModeItem(
            title = stringResource(R.string.settings_theme_mode_title),
            subtitle = stringResource(R.string.settings_theme_mode_subtitle),
            icon = Icons.Rounded.LinearScale,
            selectedMode = if (isHaze) ThemeMode.Dark else themeMode,
            onModeSelected = onThemeModeChange,
            enabled = !isHaze
        )
        val isDynamicColorSupported = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O
        SettingsToggleItem(
            title = stringResource(R.string.settings_dynamic_color_title),
            subtitle = if (isDynamicColorSupported) {
                stringResource(R.string.settings_dynamic_color_supported_subtitle)
            } else {
                stringResource(R.string.settings_dynamic_color_unsupported_subtitle)
            },
            icon = Icons.Rounded.LinearScale,
            checked = isDynamicColorEnabled,
            onCheckedChange = onDynamicColorEnabledChange,
            enabled = isDynamicColorSupported
        )
        SettingsToggleItem(
            title = stringResource(R.string.settings_haze_effect_title),
            subtitle = stringResource(R.string.settings_haze_effect_subtitle),
            icon = Icons.Rounded.LinearScale,
            checked = glassEffectMode == GlassEffectMode.Haze,
            onCheckedChange = { isChecked ->
                val newMode = if (isChecked) GlassEffectMode.Haze else GlassEffectMode.Material
                onGlassEffectModeChange(newMode)
            }
        )
    }
}

/**
 * PlaybackBehaviorSection Composable (Groups listening-behavior settings)
 * Keeps progress display, silence skipping, auto rewind, and notification focus behavior together because each option changes active playback behavior after a book starts.
 */
@Composable
fun PlaybackBehaviorSection(
    isChapterProgressMode: Boolean,
    onChapterProgressModeChange: (Boolean) -> Unit,
    isSkipSilenceEnabled: Boolean,
    onSkipSilenceEnabledChange: (Boolean) -> Unit,
    autoRewindSeconds: Int,
    onAutoRewindSecondsChange: (Int) -> Unit,
    isNotificationAvoidanceEnabled: Boolean,
    onNotificationAvoidanceEnabledChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        SettingsSectionHeader(title = stringResource(R.string.settings_playback_behavior_title))
        SettingsToggleItem(
            title = stringResource(R.string.chapter_progress_title),
            subtitle = stringResource(R.string.chapter_progress_subtitle),
            icon = Icons.Rounded.LinearScale,
            checked = isChapterProgressMode,
            onCheckedChange = onChapterProgressModeChange
        )
        SettingsToggleItem(
            title = stringResource(R.string.settings_skip_silence_toggle_title),
            subtitle = stringResource(R.string.settings_skip_silence_subtitle),
            icon = Icons.Rounded.LinearScale,
            checked = isSkipSilenceEnabled,
            onCheckedChange = onSkipSilenceEnabledChange
        )
        val disabledText = stringResource(R.string.settings_auto_rewind_disabled)
        val secondsTemplate = stringResource(R.string.settings_seconds_value)
        SettingsSliderItem(
            title = stringResource(R.string.settings_auto_rewind_toggle_title),
            subtitle = stringResource(R.string.settings_auto_rewind_subtitle),
            icon = Icons.Rounded.LinearScale,
            value = autoRewindSeconds.toFloat(),
            onValueChange = { onAutoRewindSecondsChange(it.toInt()) },
            valueRange = 0f..30f,
            steps = 29,
            valueFormatter = {
                if (it.toInt() == 0) {
                    disabledText
                } else {
                    String.format(java.util.Locale.US, secondsTemplate, it.toInt())
                }
            },
            enabled = true
        )
        SettingsToggleItem(
            title = stringResource(R.string.settings_notification_avoidance_title),
            subtitle = stringResource(R.string.settings_notification_avoidance_subtitle),
            icon = Icons.Rounded.LinearScale,
            checked = isNotificationAvoidanceEnabled,
            onCheckedChange = onNotificationAvoidanceEnabledChange
        )
    }
}

/**
 * NetworkSecuritySection Composable (Groups transport-risk permissions)
 * Keeps cleartext HTTP and insecure TLS together because both options weaken remote media transport security and should be reviewed as one risk cluster.
 */
@Composable
fun NetworkSecuritySection(
    isCleartextTrafficAllowed: Boolean,
    onCleartextTrafficAllowedChange: (Boolean) -> Unit,
    // Insecure TLS Config Option: Switch parameter to toggle bypass validation for self-signed certificates.
    isAllowInsecureTls: Boolean,
    onAllowInsecureTlsChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        SettingsSectionHeader(title = stringResource(R.string.settings_network_security_title))
        SettingsToggleItem(
            title = stringResource(R.string.settings_cleartext_title),
            subtitle = stringResource(R.string.settings_cleartext_subtitle),
            icon = Icons.Rounded.LinearScale,
            checked = isCleartextTrafficAllowed,
            onCheckedChange = onCleartextTrafficAllowedChange
        )
        // Insecure TLS Switch (Render the second transport-risk toggle inside the dedicated security cluster)
        // Keeping it next to cleartext HTTP makes the security trade-off explicit instead of hiding it among playback controls.
        SettingsToggleItem(
            title = stringResource(R.string.settings_insecure_tls_title),
            subtitle = stringResource(R.string.settings_insecure_tls_subtitle),
            icon = Icons.Rounded.LinearScale,
            checked = isAllowInsecureTls,
            onCheckedChange = onAllowInsecureTlsChange
        )
    }
}

/**
 * SleepTimerSection Composable: Collects timer mode selections and related shake triggers or volume fading configurations.
 */
@Composable
fun SleepTimerSection(
    sleepMode: SleepMode,
    onSleepModeChange: (SleepMode) -> Unit,
    isSleepFadeOutEnabled: Boolean,
    onSleepFadeOutEnabledChange: (Boolean) -> Unit,
    isShakeToResetEnabled: Boolean,
    onShakeToResetEnabledChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        SettingsSectionHeader(title = stringResource(R.string.settings_sleep_timer_title))
        SettingsSegmentedSleepModeItem(
            title = stringResource(R.string.settings_sleep_mode_title),
            subtitle = stringResource(R.string.settings_sleep_mode_subtitle),
            icon = Icons.Rounded.LinearScale,
            selectedMode = sleepMode,
            onModeSelected = onSleepModeChange
        )
        SettingsToggleItem(
            title = stringResource(R.string.settings_sleep_fade_title),
            subtitle = stringResource(R.string.settings_sleep_fade_subtitle),
            icon = Icons.Rounded.LinearScale,
            checked = isSleepFadeOutEnabled,
            onCheckedChange = onSleepFadeOutEnabledChange
        )
        SettingsToggleItem(
            title = stringResource(R.string.settings_shake_reset_title),
            subtitle = stringResource(R.string.settings_shake_reset_subtitle),
            icon = Icons.Rounded.LinearScale,
            checked = isShakeToResetEnabled,
            onCheckedChange = onShakeToResetEnabledChange
        )
    }
}

/**
 * AboutSection Composable: Displays general application details and opens open source licenses.
 */
@Composable
fun AboutSection(
    onAboutLibrariesClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        SettingsSectionHeader(title = stringResource(R.string.settings_about_title))
        SettingsItem(
            title = stringResource(R.string.settings_open_source_license_title),
            subtitle = stringResource(R.string.settings_open_source_license_subtitle),
            icon = Icons.Rounded.Info,
            onClick = onAboutLibrariesClick
        )
    }
}
