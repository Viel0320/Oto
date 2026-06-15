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
import androidx.compose.material.icons.automirrored.rounded.VolumeDown
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.BlurOn
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.CloudUpload
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
import com.viel.aplayer.R
import com.viel.aplayer.application.library.settings.SettingsRootItem
import com.viel.aplayer.data.store.AppLanguage
import com.viel.aplayer.data.store.GlassEffectMode
import com.viel.aplayer.data.store.PlaybackSeekStepConfig
import com.viel.aplayer.data.store.SeekStepSeconds
import com.viel.aplayer.data.store.SleepMode
import com.viel.aplayer.data.store.ThemeMode
import com.viel.aplayer.ui.settings.appLanguageLabel

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
    Column(modifier = modifier) {
        SettingsSectionHeader(title = stringResource(R.string.settings_library_management_title))
        libraryRootDisplays.forEach { display ->
            // Settings Root Row Source Flags (Drive row icon and ABS-only error details from the scene item)
            // The list no longer needs persistence root rows because SettingsRootItem carries the storage type and rendered text directly.
            // Title: UI branching decoupling (Bypass AudiobookSchema in SettingsSections using computed item flags)
            val isWebDavRoot = display.isWebDavRoot
            val isAbsRoot = display.isAbsRoot
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
                        text = pluralStringResource(
                            R.plurals.settings_library_sync_summary,
                            display.importedBookCount,
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
        SettingsItem(
            title = stringResource(R.string.deleted_book_recovery_title),
            subtitle = stringResource(R.string.deleted_book_recovery_settings_subtitle),
            // Deleted Book Recovery Icon (Use the restore symbol for manual soft-delete reversal)
            // This row opens a dedicated recovery scene instead of scanning roots or registering new media sources.
            icon = Icons.Rounded.Restore,
            onClick = onDeletedBookRecoveryClick
        )
    }
}

/**
 * Download Cache Section (Provides settings navigation for manual downloads, Wi-Fi only download policy, and playback buffer size selector)
 * Consolidates download and cache configurations on the main settings screen instead of keeping cache settings on a separate sub-page.
 */
@Composable
fun DownloadCacheSection(
    downloadTaskCount: Int,
    isDownloadWifiOnly: Boolean,
    onDownloadWifiOnlyChange: (Boolean) -> Unit,
    playbackBufferMaxBytes: Long,
    onPlaybackBufferMaxBytesChange: (Long) -> Unit,
    onDownloadManagementClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        SettingsSectionHeader(title = stringResource(R.string.settings_download_cache_title))
        SettingsItem(
            title = stringResource(R.string.settings_download_management_title),
            subtitle = stringResource(R.string.settings_download_management_subtitle, downloadTaskCount),
            // Manual Download Management Icon (Use cloud-download to represent user-requested remote cache tasks)
            icon = Icons.Rounded.CloudDownload,
            onClick = onDownloadManagementClick
        )
        // Wi-Fi Only Download Switch (Moved from the old independent cache page to the outer settings screen)
        SettingsToggleItem(
            title = stringResource(R.string.settings_download_wifi_only_title),
            subtitle = stringResource(R.string.settings_download_wifi_only_subtitle),
            icon = Icons.Rounded.Wifi,
            checked = isDownloadWifiOnly,
            onCheckedChange = onDownloadWifiOnlyChange
        )
        // Segmented Playback Buffer Capacity Selector (Moved from the old independent cache page to the outer settings screen)
        SettingsSegmentedPlaybackBufferItem(
            selectedBytes = playbackBufferMaxBytes,
            onSelected = onPlaybackBufferMaxBytesChange
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
            // Theme Mode Icon (Represent light, dark, and system appearance selection)
            // A dark-mode glyph is more recognizable than the previous generic scale symbol for this segmented appearance control.
            icon = Icons.Rounded.DarkMode,
            selectedMode = if (isHaze) ThemeMode.Dark else themeMode,
            onModeSelected = onThemeModeChange,
            enabled = !isHaze
        )
        val isDynamicColorSupported = true
        SettingsToggleItem(
            title = stringResource(R.string.settings_dynamic_color_title),
            subtitle = if (isDynamicColorSupported) {
                stringResource(R.string.settings_dynamic_color_supported_subtitle)
            } else {
                stringResource(R.string.settings_dynamic_color_unsupported_subtitle)
            },
            // Dynamic Color Icon (Use a Material palette symbol for wallpaper-driven color sampling)
            // The setting changes the app color source, so the palette icon matches the visual customization domain.
            icon = Icons.Rounded.Palette,
            checked = isDynamicColorEnabled,
            onCheckedChange = onDynamicColorEnabledChange,
            enabled = isDynamicColorSupported
        )
        SettingsToggleItem(
            title = stringResource(R.string.settings_haze_effect_title),
            subtitle = stringResource(R.string.settings_haze_effect_subtitle),
            // Blur Effect Icon (Use the Material blur glyph for the experimental Haze rendering path)
            // This distinguishes the visual blur toggle from ordinary theme controls while keeping it inside the interface section.
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
    playbackSeekStepConfig: PlaybackSeekStepConfig,
    onSeekBackwardStepChange: (SeekStepSeconds) -> Unit,
    onSeekForwardStepChange: (SeekStepSeconds) -> Unit,
    isNotificationAvoidanceEnabled: Boolean,
    onNotificationAvoidanceEnabledChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        SettingsSectionHeader(title = stringResource(R.string.settings_playback_behavior_title))
        SettingsToggleItem(
            title = stringResource(R.string.chapter_progress_title),
            subtitle = stringResource(R.string.chapter_progress_subtitle),
            // Chapter Progress Icon (Use a numbered-list glyph for chapter-scoped progress display)
            // The setting changes progress reporting from whole-book scope to chapter scope, so a structured list better suggests chapter boundaries.
            icon = Icons.Rounded.FormatListNumbered,
            checked = isChapterProgressMode,
            onCheckedChange = onChapterProgressModeChange
        )
        SettingsToggleItem(
            title = stringResource(R.string.settings_skip_silence_toggle_title),
            subtitle = stringResource(R.string.settings_skip_silence_subtitle),
            // Skip Silence Icon (Use the audio waveform glyph for narrator-pause detection)
            // The row controls playback analysis rather than visual scale, so the waveform icon points to audio content processing.
            icon = Icons.Rounded.GraphicEq,
            checked = isSkipSilenceEnabled,
            onCheckedChange = onSkipSilenceEnabledChange
        )
        SettingsSegmentedSeekStepItem(
            title = stringResource(R.string.settings_seek_backward_step_title),
            subtitle = stringResource(R.string.settings_seek_backward_step_subtitle),
            // Rewind Step Icon (Use the standard media rewind glyph for backward seek distance)
            // The configured seconds are variable, so the generic rewind control avoids implying a fixed 10-second preset.
            icon = Icons.Rounded.FastRewind,
            selectedStep = playbackSeekStepConfig.backward,
            onStepSelected = onSeekBackwardStepChange
        )
        SettingsSegmentedSeekStepItem(
            title = stringResource(R.string.settings_seek_forward_step_title),
            subtitle = stringResource(R.string.settings_seek_forward_step_subtitle),
            // Fast-Forward Step Icon (Use the standard media forward glyph for forward seek distance)
            // The control configures the player skip button, making the media-forward symbol more direct than a generic settings scale.
            icon = Icons.Rounded.FastForward,
            selectedStep = playbackSeekStepConfig.forward,
            onStepSelected = onSeekForwardStepChange
        )
        val disabledText = stringResource(R.string.settings_auto_rewind_disabled)
        val resources = LocalResources.current
        SettingsSliderItem(
            title = stringResource(R.string.settings_auto_rewind_toggle_title),
            subtitle = stringResource(R.string.settings_auto_rewind_subtitle),
            // Auto-Rewind Icon (Use the replay glyph for rewind-after-pause behavior)
            // The slider controls how far playback rolls back after interruption, so the replay arrow communicates that automatic return.
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
                    // Counted Slider Value (Resolve plural text inside the ordinary formatter callback)
                    // The formatter is not composable, so it uses the current Resources instance to keep second labels on Android plural rules without changing the reusable slider row API.
                    resources.getQuantityString(R.plurals.settings_seconds_value, seconds, seconds)
                }
            },
            enabled = true
        )
        SettingsToggleItem(
            title = stringResource(R.string.settings_notification_avoidance_title),
            subtitle = stringResource(R.string.settings_notification_avoidance_subtitle),
            // Notification Avoidance Icon (Use the muted-notification glyph for focus-loss interruption handling)
            // The setting protects spoken content from notification ducking, so the notification-off symbol matches the user-facing behavior.
            icon = Icons.Rounded.NotificationsOff,
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
            // Cleartext HTTP Icon (Use the HTTP glyph for insecure plaintext endpoint permission)
            // This makes the transport protocol explicit before the user enables a weaker network boundary.
            icon = Icons.Rounded.Http,
            checked = isCleartextTrafficAllowed,
            onCheckedChange = onCleartextTrafficAllowedChange
        )
        // Insecure TLS Switch (Render the second transport-risk toggle inside the dedicated security cluster)
        // Keeping it next to cleartext HTTP makes the security trade-off explicit instead of hiding it among playback controls.
        SettingsToggleItem(
            title = stringResource(R.string.settings_insecure_tls_title),
            subtitle = stringResource(R.string.settings_insecure_tls_subtitle),
            // Insecure TLS Icon (Use the security shield glyph for certificate-validation risk)
            // The row controls a security exception, so the shield keeps it visually tied to transport trust decisions.
            icon = Icons.Rounded.Security,
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
            // Sleep Mode Icon (Use the bedtime glyph for timer modes that infer or manage sleep)
            // The row selects how the app counts down before resting playback, so the bedtime icon matches the listening-at-night workflow.
            icon = Icons.Rounded.Bedtime,
            selectedMode = sleepMode,
            onModeSelected = onSleepModeChange
        )
        SettingsToggleItem(
            title = stringResource(R.string.settings_sleep_fade_title),
            subtitle = stringResource(R.string.settings_sleep_fade_subtitle),
            // Sleep Fade Icon (Use the volume-down glyph for countdown fade-out behavior)
            // The toggle gradually lowers audio before pausing, so volume-down describes the audible effect directly.
            icon = Icons.AutoMirrored.Rounded.VolumeDown,
            checked = isSleepFadeOutEnabled,
            onCheckedChange = onSleepFadeOutEnabledChange
        )
        SettingsToggleItem(
            title = stringResource(R.string.settings_shake_reset_title),
            subtitle = stringResource(R.string.settings_shake_reset_subtitle),
            // Shake Reset Icon (Use the vibration glyph for motion-triggered timer reset)
            // The setting listens for phone movement and gives haptic feedback, making vibration the closest Material symbol.
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

// Title: Backup and Restore UI Section (Add backup/restore options composable layout to settings screen)
// Renders options for database/settings backup ZIP export and import via SAF.
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

