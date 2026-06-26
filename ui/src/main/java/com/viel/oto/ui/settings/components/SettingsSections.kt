package com.viel.oto.ui.settings.components

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
import androidx.compose.material.icons.automirrored.rounded.VolumeDown
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.BlurOn
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.rounded.Contrast
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material.icons.rounded.FastRewind
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.FormatListNumbered
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Http
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.NotificationsOff
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material.icons.rounded.Vibration
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.viel.oto.shared.R
import com.viel.oto.application.library.settings.SettingsRootItem
import com.viel.oto.shared.model.AppLanguage
import com.viel.oto.shared.model.GlassEffectMode
import com.viel.oto.shared.model.PlaybackSeekStepConfig
import com.viel.oto.shared.model.SeekStepSeconds
import com.viel.oto.shared.model.SleepMode
import com.viel.oto.shared.model.ThemeMode
import com.viel.oto.ui.common.layout.LocalAppWindowSizeClass
import com.viel.oto.ui.settings.appLanguageLabel
import dev.chrisbanes.haze.HazeState

/**
 * LibraryDirectoriesSection Composable: Renders media library folder locations and sync history statuses.
 */
@Composable
fun LibraryDirectoriesSection(
    libraryRootDisplays: List<SettingsRootItem>,
    onRootClick: (SettingsRootItem) -> Unit,
    onAddLibraryClick: () -> Unit,
    onDeletedBookRecoveryClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val rowHorizontalPadding = LocalAppWindowSizeClass.current.screenHorizontalPadding

    Column(modifier = modifier) {
        SettingsSectionHeader(title = stringResource(R.string.settings_library_management_title))
        libraryRootDisplays.forEach { display ->
            val isWebDavRoot = display.isWebDavRoot
            val isAbsRoot = display.isAbsRoot
            val lastError = display.lastError
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
                    .padding(horizontal = rowHorizontalPadding, vertical = 8.dp),
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
                        text = pluralStringResource(
                            R.plurals.settings_library_sync_summary,
                            display.importedBookCount,
                            display.lastSyncText,
                            display.importedBookCount
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (isAbsRoot && lastError?.isNotBlank() == true) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = stringResource(R.string.settings_library_error, lastError),
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
            icon = Icons.Rounded.Add,
            onClick = onAddLibraryClick
        )
        SettingsItem(
            title = stringResource(R.string.deleted_book_recovery_title),
            subtitle = stringResource(R.string.deleted_book_recovery_settings_subtitle),
            icon = Icons.Rounded.Restore,
            onClick = onDeletedBookRecoveryClick
        )
    }
}

/**
 * Provides settings navigation for manual downloads, Wi-Fi only download policy, and playback buffer size selector.
 * Consolidates download and cache configurations on the main settings screen instead of keeping cache settings on a separate sub-page.
 * Receives the page Haze context because the playback-buffer selector renders a floating dropdown surface inside this section.
 */
@Composable
fun DownloadCacheSection(
    downloadTaskCount: Int,
    isDownloadWifiOnly: Boolean,
    onDownloadWifiOnlyChange: (Boolean) -> Unit,
    onDownloadManagementClick: () -> Unit,
    glassEffectMode: GlassEffectMode,
    hazeState: HazeState?,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        SettingsSectionHeader(title = stringResource(R.string.settings_download_cache_title))
        SettingsItem(
            title = stringResource(R.string.settings_download_management_title),
            subtitle = stringResource(R.string.settings_download_management_subtitle, downloadTaskCount),
            icon = Icons.Rounded.CloudDownload,
            onClick = onDownloadManagementClick
        )
        SettingsToggleItem(
            title = stringResource(R.string.settings_download_wifi_only_title),
            subtitle = stringResource(R.string.settings_download_wifi_only_subtitle),
            icon = Icons.Rounded.Wifi,
            checked = isDownloadWifiOnly,
            onCheckedChange = onDownloadWifiOnlyChange
        )
    }
}


/**
 * InterfaceSettingsSection Composable: Handles themes, dynamic color settings, and experimental blur effect toggles.
 * Threads the active Haze context into the theme-mode dropdown so visual-effect changes do not split Settings controls across different surface policies.
 */
@Composable
fun InterfaceSettingsSection(
    appLanguage: AppLanguage,
    onLanguageClick: () -> Unit,
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
    isDynamicColorEnabled: Boolean,
    onDynamicColorEnabledChange: (Boolean) -> Unit,
    isAmoledEnabled: Boolean,
    onAmoledEnabledChange: (Boolean) -> Unit,
    glassEffectMode: GlassEffectMode,
    onGlassEffectModeChange: (GlassEffectMode) -> Unit,
    hazeState: HazeState?,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        SettingsSectionHeader(title = stringResource(R.string.settings_interface_effects_title))
        SettingsItem(
            title = stringResource(R.string.settings_language_title),
            subtitle = appLanguageLabel(appLanguage),
            icon = Icons.Rounded.Translate,
            onClick = onLanguageClick
        )
        SettingsSegmentedThemeModeItem(
            title = stringResource(R.string.settings_theme_mode_title),
            subtitle = stringResource(R.string.settings_theme_mode_subtitle),
            icon = Icons.Rounded.DarkMode,
            selectedMode = themeMode,
            onModeSelected = onThemeModeChange,
            glassEffectMode = glassEffectMode,
            hazeState = hazeState
        )
        val isDynamicColorSupported = true
        SettingsToggleItem(
            title = stringResource(R.string.settings_dynamic_color_title),
            subtitle = if (isDynamicColorSupported) {
                stringResource(R.string.settings_dynamic_color_supported_subtitle)
            } else {
                stringResource(R.string.settings_dynamic_color_unsupported_subtitle)
            },
            icon = Icons.Rounded.Palette,
            checked = isDynamicColorEnabled,
            onCheckedChange = onDynamicColorEnabledChange,
            enabled = isDynamicColorSupported
        )
        SettingsToggleItem(
            title = stringResource(R.string.settings_amoled_title),
            subtitle = stringResource(R.string.settings_amoled_subtitle),
            icon = Icons.Rounded.Contrast,
            checked = isAmoledEnabled,
            onCheckedChange = onAmoledEnabledChange
        )
        SettingsToggleItem(
            title = stringResource(R.string.settings_haze_effect_title),
            subtitle = stringResource(R.string.settings_haze_effect_subtitle),
            icon = Icons.Rounded.BlurOn,
            checked = glassEffectMode == GlassEffectMode.Haze,
            onCheckedChange = { isChecked ->
                val newMode = if (isChecked) GlassEffectMode.Haze else GlassEffectMode.Material
                onGlassEffectModeChange(newMode)
            }
        )
    }
}

/**
 * Groups listening-behavior settings.
 * Keeps progress display, silence skipping, auto rewind, and notification focus behavior together because each option changes active playback behavior after a book starts.
 * Receives the active Haze context for seek-step dropdowns while leaving playback persistence in the existing settings commands.
 */
@Composable
fun PlaybackBehaviorSection(
    isChapterProgressMode: Boolean,
    onChapterProgressModeChange: (Boolean) -> Unit,
    isSkipSilenceEnabled: Boolean,
    onSkipSilenceEnabledChange: (Boolean) -> Unit,
    autoRewindSeconds: Int,
    onAutoRewindSecondsChange: (Int) -> Unit,
    playbackSeekStepConfig: PlaybackSeekStepConfig,
    onSeekBackwardStepChange: (SeekStepSeconds) -> Unit,
    onSeekForwardStepChange: (SeekStepSeconds) -> Unit,
    isNotificationAvoidanceEnabled: Boolean,
    onNotificationAvoidanceEnabledChange: (Boolean) -> Unit,
    glassEffectMode: GlassEffectMode,
    hazeState: HazeState?,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        SettingsSectionHeader(title = stringResource(R.string.settings_playback_behavior_title))
        SettingsToggleItem(
            title = stringResource(R.string.chapter_progress_title),
            subtitle = stringResource(R.string.chapter_progress_subtitle),
            icon = Icons.Rounded.FormatListNumbered,
            checked = isChapterProgressMode,
            onCheckedChange = onChapterProgressModeChange
        )
        SettingsToggleItem(
            title = stringResource(R.string.settings_skip_silence_toggle_title),
            subtitle = stringResource(R.string.settings_skip_silence_subtitle),
            icon = Icons.Rounded.GraphicEq,
            checked = isSkipSilenceEnabled,
            onCheckedChange = onSkipSilenceEnabledChange
        )
        SettingsSegmentedSeekStepItem(
            title = stringResource(R.string.settings_seek_backward_step_title),
            subtitle = stringResource(R.string.settings_seek_backward_step_subtitle),
            icon = Icons.Rounded.FastRewind,
            selectedStep = playbackSeekStepConfig.backward,
            onStepSelected = onSeekBackwardStepChange,
            glassEffectMode = glassEffectMode,
            hazeState = hazeState
        )
        SettingsSegmentedSeekStepItem(
            title = stringResource(R.string.settings_seek_forward_step_title),
            subtitle = stringResource(R.string.settings_seek_forward_step_subtitle),
            icon = Icons.Rounded.FastForward,
            selectedStep = playbackSeekStepConfig.forward,
            onStepSelected = onSeekForwardStepChange,
            glassEffectMode = glassEffectMode,
            hazeState = hazeState
        )
        val disabledText = stringResource(R.string.settings_auto_rewind_disabled)
        val resources = LocalResources.current
        SettingsSliderItem(
            title = stringResource(R.string.settings_auto_rewind_toggle_title),
            subtitle = stringResource(R.string.settings_auto_rewind_subtitle),
            icon = Icons.Rounded.Replay,
            value = autoRewindSeconds.toFloat(),
            onValueChange = { onAutoRewindSecondsChange(it.toInt()) },
            valueRange = 0f..30f,
            steps = 29,
            valueFormatter = {
                val seconds = it.toInt()
                if (seconds == 0) {
                    disabledText
                } else {
                    resources.getQuantityString(R.plurals.settings_seconds_value, seconds, seconds)
                }
            },
            enabled = true
        )
        SettingsToggleItem(
            title = stringResource(R.string.settings_notification_avoidance_title),
            subtitle = stringResource(R.string.settings_notification_avoidance_subtitle),
            icon = Icons.Rounded.NotificationsOff,
            checked = isNotificationAvoidanceEnabled,
            onCheckedChange = onNotificationAvoidanceEnabledChange
        )
    }
}

/**
 * Groups transport-risk permissions.
 * Keeps cleartext HTTP and insecure TLS together because both options weaken remote media transport security and should be reviewed as one risk cluster.
 */
@Composable
fun NetworkSecuritySection(
    isCleartextTrafficAllowed: Boolean,
    onCleartextTrafficAllowedChange: (Boolean) -> Unit,
    isAllowInsecureTls: Boolean,
    onAllowInsecureTlsChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        SettingsSectionHeader(title = stringResource(R.string.settings_network_security_title))
        SettingsToggleItem(
            title = stringResource(R.string.settings_cleartext_title),
            subtitle = stringResource(R.string.settings_cleartext_subtitle),
            icon = Icons.Rounded.Http,
            checked = isCleartextTrafficAllowed,
            onCheckedChange = onCleartextTrafficAllowedChange
        )
        SettingsToggleItem(
            title = stringResource(R.string.settings_insecure_tls_title),
            subtitle = stringResource(R.string.settings_insecure_tls_subtitle),
            icon = Icons.Rounded.Security,
            checked = isAllowInsecureTls,
            onCheckedChange = onAllowInsecureTlsChange
        )
    }
}

/**
 * SleepTimerSection Composable: Collects timer mode selections and related shake triggers or volume fading configurations.
 * Passes the page Haze context to the sleep-mode dropdown so timer controls render with the same backdrop sampling as the Settings surface.
 */
@Composable
fun SleepTimerSection(
    sleepMode: SleepMode,
    onSleepModeChange: (SleepMode) -> Unit,
    isSleepFadeOutEnabled: Boolean,
    onSleepFadeOutEnabledChange: (Boolean) -> Unit,
    isShakeToResetEnabled: Boolean,
    onShakeToResetEnabledChange: (Boolean) -> Unit,
    glassEffectMode: GlassEffectMode,
    hazeState: HazeState?,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        SettingsSectionHeader(title = stringResource(R.string.settings_sleep_timer_title))
        SettingsSegmentedSleepModeItem(
            title = stringResource(R.string.settings_sleep_mode_title),
            subtitle = stringResource(R.string.settings_sleep_mode_subtitle),
            icon = Icons.Rounded.Bedtime,
            selectedMode = sleepMode,
            onModeSelected = onSleepModeChange,
            glassEffectMode = glassEffectMode,
            hazeState = hazeState
        )
        SettingsToggleItem(
            title = stringResource(R.string.settings_sleep_fade_title),
            subtitle = stringResource(R.string.settings_sleep_fade_subtitle),
            icon = Icons.AutoMirrored.Rounded.VolumeDown,
            checked = isSleepFadeOutEnabled,
            onCheckedChange = onSleepFadeOutEnabledChange
        )
        SettingsToggleItem(
            title = stringResource(R.string.settings_shake_reset_title),
            subtitle = stringResource(R.string.settings_shake_reset_subtitle),
            icon = Icons.Rounded.Vibration,
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

@Composable
fun BackupRestoreSection(
    onExportClick: () -> Unit,
    onImportClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        SettingsSectionHeader(title = stringResource(R.string.settings_backup_restore_title))
        SettingsItem(
            title = stringResource(R.string.settings_export_data_title),
            subtitle = stringResource(R.string.settings_export_data_subtitle),
            icon = Icons.Rounded.CloudUpload,
            onClick = onExportClick
        )
        SettingsItem(
            title = stringResource(R.string.settings_import_data_title),
            subtitle = stringResource(R.string.settings_import_data_subtitle),
            icon = Icons.Rounded.CloudDownload,
            onClick = onImportClick
        )
    }
}
