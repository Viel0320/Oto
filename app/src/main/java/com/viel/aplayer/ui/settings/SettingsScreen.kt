package com.viel.aplayer.ui.settings
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.viel.aplayer.R
import com.viel.aplayer.application.library.settings.SettingsCredential
import com.viel.aplayer.application.library.settings.SettingsRootItem
import com.viel.aplayer.shared.settings.AppLanguage
import com.viel.aplayer.shared.settings.AppSettings
import com.viel.aplayer.shared.settings.GlassEffectMode
import com.viel.aplayer.shared.settings.PlaybackSeekStepConfig
import com.viel.aplayer.shared.settings.SeekStepSeconds
import com.viel.aplayer.shared.settings.SleepMode
import com.viel.aplayer.shared.settings.ThemeMode
import com.viel.aplayer.ui.common.APlayerGlassTopBar
import com.viel.aplayer.ui.common.layout.AppWindowSizeClass
import com.viel.aplayer.ui.common.layout.LocalAppWindowSizeClass
import com.viel.aplayer.ui.common.theme.APlayerTheme
import com.viel.aplayer.ui.settings.components.AboutSection
import com.viel.aplayer.ui.settings.components.BackupRestoreSection
import com.viel.aplayer.ui.settings.components.DownloadCacheSection
import com.viel.aplayer.ui.settings.components.InterfaceSettingsSection
import com.viel.aplayer.ui.settings.components.LibraryDirectoriesSection
import com.viel.aplayer.ui.settings.components.NetworkSecuritySection
import com.viel.aplayer.ui.settings.components.PlaybackBehaviorSection
import com.viel.aplayer.ui.settings.components.SectionsColumns
import com.viel.aplayer.ui.settings.components.SleepTimerSection
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import kotlinx.coroutines.launch

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
    playbackBufferMaxBytes: Long,
    onPlaybackBufferMaxBytesChange: (Long) -> Unit,
    onDownloadManagementClick: () -> Unit = {},
    onGlassEffectModeChange: (GlassEffectMode) -> Unit,
    autoRewindSeconds: Int,
    onAutoRewindSecondsChange: (Int) -> Unit,
    playbackSeekStepConfig: PlaybackSeekStepConfig,
    onSeekBackwardStepChange: (SeekStepSeconds) -> Unit,
    onSeekForwardStepChange: (SeekStepSeconds) -> Unit,
    isNotificationAvoidanceEnabled: Boolean,
    onNotificationAvoidanceEnabledChange: (Boolean) -> Unit,
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
                                playbackBufferMaxBytes = playbackBufferMaxBytes,
                                onPlaybackBufferMaxBytesChange = onPlaybackBufferMaxBytesChange,
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
            APlayerGlassTopBar(
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
    webDavConnectionState: WebDavConnectionUiState,
    onWebDavConnectionTest: (url: String, username: String, password: String, basePath: String, editingRootId: String?) -> Unit,
    onResetWebDavConnectionState: () -> Unit,
    onWebDavRootSubmitted: (url: String, username: String, password: String, displayName: String, basePath: String) -> Unit,
    onWebDavRootUpdated: (id: String, url: String, username: String, password: String, displayName: String, basePath: String) -> Unit,
    absConnectionState: AbsConnectionUiState,
    onAbsConnectionTest: (baseUrl: String, username: String, password: String, editingRootId: String?) -> Unit,
    onResetAbsConnectionState: () -> Unit,
    onAbsRootSubmitted: (baseUrl: String, username: String, password: String, libraryId: String, libraryName: String, editingRootId: String?) -> Unit,
    getWebDavCredentials: (credentialId: String) -> SettingsCredential?,
    getAbsCredential: suspend (credentialId: String) -> SettingsCredential?,
    onAbsSync: (rootId: String) -> Unit,
    onRescan: () -> Unit,
    onDeleteLibraryRoot: (SettingsRootItem) -> Unit,
    onLaunchSafRootPicker: () -> Unit,
    onImportConfirm: (Uri) -> Unit = {}
) {
    val dialogState = controller.dialogState
    val rootToDelete = (dialogState as? SettingsDialogState.DeleteRoot)?.root
    val showWebDavDialog = dialogState == SettingsDialogState.WebDavRoot
    val showAbsDialog = dialogState == SettingsDialogState.AbsServer
    val showLanguagePicker = dialogState == SettingsDialogState.LanguagePicker
    val showAddLibraryTypeDialog = dialogState == SettingsDialogState.AddLibraryType
    val rootForAction = (dialogState as? SettingsDialogState.RootActions)?.root
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

    if (showWebDavDialog) {
        WebDavRootDialog(
            url = controller.webDavUrl,
            username = controller.webDavUsername,
            password = controller.webDavPassword,
            displayName = controller.webDavDisplayName,
            basePath = controller.webDavBasePath,
            editingRootId = controller.editingRootId,
            hazeState = resolvedSettingsDialogHazeState,
            glassEffectMode = glassEffectMode,
            connectionState = webDavConnectionState,
            onUrlChange = {
                controller.webDavUrl = it
                onResetWebDavConnectionState()
            },
            onUsernameChange = {
                controller.webDavUsername = it
                onResetWebDavConnectionState()
            },
            onPasswordChange = {
                controller.webDavPassword = it
                onResetWebDavConnectionState()
            },
            onDisplayNameChange = { controller.webDavDisplayName = it },
            onBasePathChange = {
                controller.webDavBasePath = it
                onResetWebDavConnectionState()
            },
            onTestConnection = {
                onWebDavConnectionTest(
                    controller.webDavUrl.trim(),
                    controller.webDavUsername.trim(),
                    controller.webDavPassword,
                    controller.webDavBasePath.trim(),
                    controller.editingRootId
                )
            },
            onDismiss = {
                controller.dialogState = SettingsDialogState.None
                controller.resetWebDavForm()
                onResetWebDavConnectionState()
            },
            onConfirm = {
                val rootId = controller.editingRootId
                if (rootId != null) {
                    onWebDavRootUpdated(
                        rootId,
                        controller.webDavUrl.trim(),
                        controller.webDavUsername.trim(),
                        controller.webDavPassword,
                        controller.webDavDisplayName.trim(),
                        controller.webDavBasePath.trim()
                    )
                } else {
                    onWebDavRootSubmitted(
                        controller.webDavUrl.trim(),
                        controller.webDavUsername.trim(),
                        controller.webDavPassword,
                        controller.webDavDisplayName.trim(),
                        controller.webDavBasePath.trim()
                    )
                }
                controller.dialogState = SettingsDialogState.None
                controller.resetWebDavForm()
                onResetWebDavConnectionState()
            }
        )
    }

    if (showAbsDialog) {
        AbsServerDialog(
            baseUrl = controller.absBaseUrl,
            username = controller.absUsername,
            password = controller.absPassword,
            editingRootId = controller.editingRootId,
            hazeState = resolvedSettingsDialogHazeState,
            glassEffectMode = glassEffectMode,
            connectionState = absConnectionState,
            selectedLibraryId = controller.absLibraryId,
            selectedLibraryName = controller.absLibraryName,
            onBaseUrlChange = {
                controller.absBaseUrl = it
                onResetAbsConnectionState()
            },
            onUsernameChange = {
                controller.absUsername = it
                onResetAbsConnectionState()
            },
            onPasswordChange = {
                controller.absPassword = it
                onResetAbsConnectionState()
            },
            onLibrarySelected = { id, name ->
                controller.absLibraryId = id
                controller.absLibraryName = name
            },
            onTestConnection = {
                onAbsConnectionTest(
                    controller.absBaseUrl.trim(),
                    controller.absUsername.trim(),
                    controller.absPassword,
                    controller.editingRootId
                )
            },
            onDismiss = {
                controller.dialogState = SettingsDialogState.None
                controller.resetAbsForm()
                onResetAbsConnectionState()
            },
            onConfirm = {
                onAbsRootSubmitted(
                    controller.absBaseUrl.trim(),
                    controller.absUsername.trim(),
                    controller.absPassword,
                    controller.absLibraryId.trim(),
                    controller.absLibraryName.trim(),
                    controller.editingRootId
                )
                controller.dialogState = SettingsDialogState.None
                controller.resetAbsForm()
                onResetAbsConnectionState()
            }
        )
    }

    if (rootToDelete != null) {
        SettingsTemplateDialog(
            onDismissRequest = { controller.dialogState = SettingsDialogState.None },
            hazeState = resolvedSettingsDialogHazeState,
            glassEffectMode = glassEffectMode,
            title = { Text(stringResource(R.string.settings_delete_root_title)) },
            text = { Text(stringResource(R.string.settings_delete_root_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDeleteLibraryRoot(rootToDelete)
                        controller.dialogState = SettingsDialogState.None
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text(stringResource(R.string.settings_delete_root_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { controller.dialogState = SettingsDialogState.None }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    if (showAddLibraryTypeDialog) {
        SettingsTemplateDialog(
            onDismissRequest = { controller.dialogState = SettingsDialogState.None },
            hazeState = resolvedSettingsDialogHazeState,
            glassEffectMode = glassEffectMode,
            title = { Text(stringResource(R.string.settings_add_library_type_title)) },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                controller.dialogState = SettingsDialogState.None
                                controller.editingSafRootId = null
                                onLaunchSafRootPicker()
                            }
                            .padding(vertical = 12.dp, horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Rounded.FolderOpen, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(stringResource(R.string.settings_library_type_local_saf), style = MaterialTheme.typography.bodyLarge)
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                controller.dialogState = SettingsDialogState.None
                                controller.resetWebDavForm()
                                controller.dialogState = SettingsDialogState.WebDavRoot
                            }
                            .padding(vertical = 12.dp, horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Rounded.Cloud, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(stringResource(R.string.settings_library_type_webdav), style = MaterialTheme.typography.bodyLarge)
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                controller.dialogState = SettingsDialogState.None
                                controller.resetAbsForm()
                                controller.dialogState = SettingsDialogState.AbsServer
                            }
                            .padding(vertical = 12.dp, horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Rounded.Sync, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(stringResource(R.string.settings_library_type_abs), style = MaterialTheme.typography.bodyLarge)
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { controller.dialogState = SettingsDialogState.None }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    if (rootForAction != null) {
        val isAbsRoot = rootForAction.isAbsRoot
        val isWebDavRoot = rootForAction.isWebDavRoot
        val scope = rememberCoroutineScope()
        SettingsTemplateDialog(
            onDismissRequest = { controller.dialogState = SettingsDialogState.None },
            hazeState = resolvedSettingsDialogHazeState,
            glassEffectMode = glassEffectMode,
            title = { Text(rootForAction.displayName.ifBlank { stringResource(R.string.settings_root_action_title_fallback) }) },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                if (rootForAction.isSafRoot) {
                                    controller.editingSafRootId = rootForAction.rootId
                                    controller.dialogState = SettingsDialogState.None
                                    onLaunchSafRootPicker()
                                } else if (isWebDavRoot) {
                                    val creds =
                                        getWebDavCredentials(rootForAction.credentialId ?: "")
                                    controller.webDavUrl = rootForAction.sourceUri
                                    controller.webDavDisplayName = rootForAction.displayName
                                    controller.webDavBasePath = rootForAction.basePath
                                    controller.webDavUsername = creds?.username ?: ""
                                    controller.webDavPassword = creds?.password ?: ""
                                    controller.editingRootId = rootForAction.rootId
                                    controller.dialogState = SettingsDialogState.WebDavRoot
                                } else if (isAbsRoot) {
                                    scope.launch {
                                        val creds =
                                            getAbsCredential(rootForAction.credentialId ?: "")
                                        controller.absBaseUrl = rootForAction.sourceUri
                                        controller.absUsername = creds?.username ?: ""
                                        controller.absPassword = ""
                                        controller.absLibraryId = rootForAction.basePath
                                        controller.absLibraryName = rootForAction.displayName
                                        controller.absDisplayName = rootForAction.displayName
                                        controller.editingRootId = rootForAction.rootId
                                        controller.dialogState = SettingsDialogState.AbsServer
                                    }
                                }
                            }
                            .padding(vertical = 12.dp, horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Rounded.Info, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            if (rootForAction.isSafRoot) {
                                stringResource(R.string.settings_root_action_relocate_saf)
                            } else {
                                stringResource(R.string.settings_root_action_edit_remote)
                            },
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                if (isAbsRoot) {
                                    onAbsSync(rootForAction.rootId)
                                } else {
                                    onRescan()
                                }
                                controller.dialogState = SettingsDialogState.None
                            }
                            .padding(vertical = 12.dp, horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Rounded.Refresh, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            if (isAbsRoot) {
                                stringResource(R.string.settings_root_action_sync)
                            } else {
                                stringResource(R.string.settings_root_action_rescan)
                            },
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                controller.dialogState = SettingsDialogState.DeleteRoot(
                                    rootForAction
                                )
                            }
                            .padding(vertical = 12.dp, horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Rounded.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(stringResource(R.string.settings_root_action_remove), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { controller.dialogState = SettingsDialogState.None }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
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
    APlayerTheme {
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
                playbackBufferMaxBytes = 64L * 1024L * 1024L,
                onPlaybackBufferMaxBytesChange = {},
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
                onExportClick = {},
                onImportClick = {},
                onAboutLibrariesClick = {}
            )
        }
    }
}
