package com.viel.oto.ui.settings
import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.exclude
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.viel.oto.application.library.settings.SettingsRootItem
import com.viel.oto.shared.R
import com.viel.oto.shared.model.AppLanguage
import com.viel.oto.shared.model.AppSettings
import com.viel.oto.shared.model.GlassEffectMode
import com.viel.oto.shared.model.PlaybackSeekStepConfig
import com.viel.oto.shared.model.SeekStepSeconds
import com.viel.oto.shared.model.SleepMode
import com.viel.oto.shared.model.ThemeMode
import com.viel.oto.ui.common.OtoGlassTopBar
import com.viel.oto.ui.common.layout.AppWindowSizeClass
import com.viel.oto.ui.common.layout.LocalAppWindowSizeClass
import com.viel.oto.ui.common.theme.OtoTheme
import com.viel.oto.ui.settings.components.AboutSection
import com.viel.oto.ui.settings.components.BackupRestoreSection
import com.viel.oto.ui.settings.components.DownloadCacheSection
import com.viel.oto.ui.settings.components.InterfaceSettingsSection
import com.viel.oto.ui.settings.components.LibraryDirectoriesSection
import com.viel.oto.ui.settings.components.NetworkSecuritySection
import com.viel.oto.ui.settings.components.PlaybackBehaviorSection
import com.viel.oto.ui.settings.components.SectionsColumns
import com.viel.oto.ui.settings.components.SleepTimerSection
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource

/**
 * SettingsScreen Composable: Defines the top-level settings controller view, handling local inputs, dialog visibility, and adaptive setting sections.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    libraryRootDisplays: List<SettingsRootItem>,
    isChapterProgressMode: Boolean,
    onChapterProgressModeChange: (Boolean) -> Unit,
    isCleartextTrafficAllowed: Boolean,
    onCleartextTrafficAllowedChange: (Boolean) -> Unit,
    isAllowInsecureTls: Boolean,
    onAllowInsecureTlsChange: (Boolean) -> Unit,
    isSkipSilenceEnabled: Boolean,
    onSkipSilenceEnabledChange: (Boolean) -> Unit,
    isSleepFadeOutEnabled: Boolean,
    onSleepFadeOutEnabledChange: (Boolean) -> Unit,
    isShakeToResetEnabled: Boolean,
    onShakeToResetEnabledChange: (Boolean) -> Unit,
    sleepMode: SleepMode,
    onSleepModeChange: (SleepMode) -> Unit,
    appLanguage: AppLanguage,
    onLanguageClick: () -> Unit = {},
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
    isDynamicColorEnabled: Boolean,
    onDynamicColorEnabledChange: (Boolean) -> Unit,
    isAmoledEnabled: Boolean,
    onAmoledEnabledChange: (Boolean) -> Unit,
    glassEffectMode: GlassEffectMode,
    settingsHazeState: HazeState? = null,
    onRootClick: (SettingsRootItem) -> Unit = {},
    onAddLibraryClick: () -> Unit = {},
    onDeletedBookRecoveryClick: () -> Unit = {},
    downloadTaskCount: Int = 0,
    isDownloadWifiOnly: Boolean,
    onDownloadWifiOnlyChange: (Boolean) -> Unit,
    onDownloadManagementClick: () -> Unit = {},
    onGlassEffectModeChange: (GlassEffectMode) -> Unit,
    autoRewindSeconds: Int,
    onAutoRewindSecondsChange: (Int) -> Unit,
    playbackSeekStepConfig: PlaybackSeekStepConfig,
    onSeekBackwardStepChange: (SeekStepSeconds) -> Unit,
    onSeekForwardStepChange: (SeekStepSeconds) -> Unit,
    isNotificationAvoidanceEnabled: Boolean,
    onNotificationAvoidanceEnabledChange: (Boolean) -> Unit,
    onAddWidgetClick: () -> Unit,
    onExportClick: () -> Unit,
    onImportClick: () -> Unit,
    onAboutLibrariesClick: () -> Unit
) {

    val safeDrawingPadding = WindowInsets.safeDrawing.exclude(WindowInsets.ime).asPaddingValues()
    val layoutDirection = androidx.compose.ui.platform.LocalLayoutDirection.current
    val windowClass = LocalAppWindowSizeClass.current
    val settingsStartPadding = safeDrawingPadding.calculateStartPadding(layoutDirection)
    val settingsEndPadding = safeDrawingPadding.calculateEndPadding(layoutDirection)
    var settingsTopBarHeightPx by remember { mutableIntStateOf(0) }
    val resolvedSettingsHazeState = settingsHazeState.takeIf { glassEffectMode == GlassEffectMode.Haze }
    val settingsTopBarHeight = safeDrawingPadding.calculateTopPadding() + 64.dp


    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth()
        ) {
            Scaffold(
                modifier = Modifier
                    .fillMaxSize()
                    .then(
                        if (resolvedSettingsHazeState != null) {
                            Modifier.hazeSource(resolvedSettingsHazeState)
                        } else {
                            Modifier
                        }
                    ),
                containerColor = if (resolvedSettingsHazeState != null) MaterialTheme.colorScheme.background else Color.Transparent,
                contentWindowInsets = WindowInsets.safeDrawing.exclude(WindowInsets.ime)
            ) { innerPadding ->
                SectionsColumns(
                    columnsCount = windowClass.columnsCount,
                    itemCount = 8,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = settingsStartPadding,
                        end = settingsEndPadding,
                        top = settingsTopBarHeight,
                        bottom = innerPadding.calculateBottomPadding()
                    )
                ) { sectionIndex ->
                    when (sectionIndex) {
                        0 -> LibraryDirectoriesSection(
                            libraryRootDisplays = libraryRootDisplays,
                            onRootClick = onRootClick,
                            onAddLibraryClick = onAddLibraryClick,
                            onDeletedBookRecoveryClick = onDeletedBookRecoveryClick,
                            modifier = Modifier.fillMaxWidth()
                        )

                        1 -> {
                            DownloadCacheSection(
                                downloadTaskCount = downloadTaskCount,
                                isDownloadWifiOnly = isDownloadWifiOnly,
                                onDownloadWifiOnlyChange = onDownloadWifiOnlyChange,
                                onDownloadManagementClick = onDownloadManagementClick,
                                glassEffectMode = glassEffectMode,
                                hazeState = resolvedSettingsHazeState,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }

                        2 -> InterfaceSettingsSection(
                            appLanguage = appLanguage,
                            onLanguageClick = onLanguageClick,
                            themeMode = themeMode,
                            onThemeModeChange = onThemeModeChange,
                            isDynamicColorEnabled = isDynamicColorEnabled,
                            onDynamicColorEnabledChange = onDynamicColorEnabledChange,
                            isAmoledEnabled = isAmoledEnabled,
                            onAmoledEnabledChange = onAmoledEnabledChange,
                            glassEffectMode = glassEffectMode,
                            onGlassEffectModeChange = onGlassEffectModeChange,
                            hazeState = resolvedSettingsHazeState,
                            modifier = Modifier.fillMaxWidth()
                        )

                        3 -> PlaybackBehaviorSection(
                            isChapterProgressMode = isChapterProgressMode,
                            onChapterProgressModeChange = onChapterProgressModeChange,
                            isSkipSilenceEnabled = isSkipSilenceEnabled,
                            onSkipSilenceEnabledChange = onSkipSilenceEnabledChange,
                            autoRewindSeconds = autoRewindSeconds,
                            onAutoRewindSecondsChange = onAutoRewindSecondsChange,
                            playbackSeekStepConfig = playbackSeekStepConfig,
                            onSeekBackwardStepChange = onSeekBackwardStepChange,
                            onSeekForwardStepChange = onSeekForwardStepChange,
                            isNotificationAvoidanceEnabled = isNotificationAvoidanceEnabled,
                            onNotificationAvoidanceEnabledChange = onNotificationAvoidanceEnabledChange,
                            onAddWidgetClick = onAddWidgetClick,
                            glassEffectMode = glassEffectMode,
                            hazeState = resolvedSettingsHazeState,
                            modifier = Modifier.fillMaxWidth()
                        )

                        4 -> SleepTimerSection(
                            sleepMode = sleepMode,
                            onSleepModeChange = onSleepModeChange,
                            isSleepFadeOutEnabled = isSleepFadeOutEnabled,
                            onSleepFadeOutEnabledChange = onSleepFadeOutEnabledChange,
                            isShakeToResetEnabled = isShakeToResetEnabled,
                            onShakeToResetEnabledChange = onShakeToResetEnabledChange,
                            glassEffectMode = glassEffectMode,
                            hazeState = resolvedSettingsHazeState,
                            modifier = Modifier.fillMaxWidth()
                        )

                        5 -> NetworkSecuritySection(
                            isCleartextTrafficAllowed = isCleartextTrafficAllowed,
                            onCleartextTrafficAllowedChange = onCleartextTrafficAllowedChange,
                            isAllowInsecureTls = isAllowInsecureTls,
                            onAllowInsecureTlsChange = onAllowInsecureTlsChange,
                            modifier = Modifier.fillMaxWidth()
                        )

                        6 -> BackupRestoreSection(
                            onExportClick = onExportClick,
                            onImportClick = onImportClick,
                            modifier = Modifier.fillMaxWidth()
                        )

                        7 -> AboutSection(
                            onAboutLibrariesClick = onAboutLibrariesClick,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
            OtoGlassTopBar(
                glassEffectMode = glassEffectMode,
                hazeState = resolvedSettingsHazeState,
                onHeightChanged = { settingsTopBarHeightPx = it },
                modifier = Modifier.align(Alignment.TopCenter),
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = stringResource(R.string.back_content_description)
                        )
                    }
                }
            )
        }
    }
}

@Composable
fun SettingsDialogHost(
    controller: SettingsDialogController,
    glassEffectMode: GlassEffectMode,
    settingsDialogHazeState: HazeState? = null,
    appLanguage: AppLanguage,
    onAppLanguageChange: (AppLanguage) -> Unit,
    onImportConfirm: (Uri) -> Unit = {}
) {
    val dialogState = controller.dialogState
    val showLanguagePicker = dialogState == SettingsDialogState.LanguagePicker
    val resolvedSettingsDialogHazeState = settingsDialogHazeState.takeIf { glassEffectMode == GlassEffectMode.Haze }

    if (showLanguagePicker) {
        LanguagePickerDialog(
            selectedLanguage = appLanguage,
            hazeState = resolvedSettingsDialogHazeState,
            glassEffectMode = glassEffectMode,
            onLanguageSelected = onAppLanguageChange,
            onDismiss = { controller.dialogState = SettingsDialogState.None }
        )
    }

    val importConfirm = dialogState as? SettingsDialogState.ImportConfirm
    if (importConfirm != null) {
        SettingsTemplateDialog(
            onDismissRequest = { controller.dialogState = SettingsDialogState.None },
            hazeState = resolvedSettingsDialogHazeState,
            glassEffectMode = glassEffectMode,
            title = { Text(stringResource(R.string.settings_import_confirm_title)) },
            text = {
                Column {
                    importConfirm.manifest?.let { m ->
                        Text(
                            text = stringResource(
                                R.string.settings_import_manifest_info,
                                m.appName,
                                m.packageName,
                                m.versionName,
                                m.versionCode,
                                m.exportedAt
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (m.libraryRoots.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = stringResource(
                                    R.string.settings_import_manifest_roots,
                                    m.libraryRoots.joinToString("\n")
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                    Text(stringResource(R.string.settings_import_confirm_body))
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        controller.dialogState = SettingsDialogState.None
                        onImportConfirm(importConfirm.uri)
                    }
                ) {
                    Text(stringResource(R.string.action_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { controller.dialogState = SettingsDialogState.None }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }
}

/**
 * SettingsScreenPreview Composable: Renders a preview display of SettingsScreen within standard PortraitPhone window bounds.
 */
@Preview(showBackground = true, apiLevel = 36)
@Composable
fun SettingsScreenPreview() {
    OtoTheme {
        CompositionLocalProvider(
            LocalAppWindowSizeClass provides AppWindowSizeClass.PortraitPhone
        ) {
            SettingsScreen(
                onBack = {},
                libraryRootDisplays = emptyList(),
                isChapterProgressMode = false,
                onChapterProgressModeChange = {},
                isCleartextTrafficAllowed = false,
                onCleartextTrafficAllowedChange = {},
                isAllowInsecureTls = false,
                onAllowInsecureTlsChange = {},
                isSkipSilenceEnabled = false,
                onSkipSilenceEnabledChange = {},
                isSleepFadeOutEnabled = true,
                onSleepFadeOutEnabledChange = {},
                isShakeToResetEnabled = true,
                onShakeToResetEnabledChange = {},
                sleepMode = SleepMode.Regular,
                onSleepModeChange = {},
                appLanguage = AppLanguage.System,
                onLanguageClick = {},
                onDeletedBookRecoveryClick = {},
                isDownloadWifiOnly = false,
                onDownloadWifiOnlyChange = {},
                themeMode = ThemeMode.System,
                onThemeModeChange = {},
                isDynamicColorEnabled = true,
                onDynamicColorEnabledChange = {},
                isAmoledEnabled = false,
                onAmoledEnabledChange = {},
                glassEffectMode = AppSettings.DEFAULT_GLASS_EFFECT_MODE,
                onGlassEffectModeChange = {},
                autoRewindSeconds = 0,
                onAutoRewindSecondsChange = {},
                playbackSeekStepConfig = PlaybackSeekStepConfig(),
                onSeekBackwardStepChange = {},
                onSeekForwardStepChange = {},
                isNotificationAvoidanceEnabled = false,
                onNotificationAvoidanceEnabledChange = {},
                onAddWidgetClick = {},
                onExportClick = {},
                onImportClick = {},
                onAboutLibrariesClick = {}
            )
        }
    }
}
