package com.viel.aplayer.ui.settings
// Title: Clean up unused imports (Remove unused references to AbsCredential and WebDavCredential to resolve layering violations)
// Presentation layer should not import infrastructure authentication and virtual file system entities.
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
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Sync
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.viel.aplayer.R
import com.viel.aplayer.application.library.settings.SettingsCredential
import com.viel.aplayer.application.library.settings.SettingsRootItem
import com.viel.aplayer.data.store.AppLanguage
import com.viel.aplayer.data.store.AppSettings
import com.viel.aplayer.data.store.GlassEffectMode
import com.viel.aplayer.data.store.PlaybackSeekStepConfig
import com.viel.aplayer.data.store.SeekStepSeconds
import com.viel.aplayer.data.store.SleepMode
import com.viel.aplayer.data.store.ThemeMode
import com.viel.aplayer.ui.common.APlayerGlassTopBar
import android.net.Uri
import com.viel.aplayer.ui.common.layout.AppWindowSizeClass
import com.viel.aplayer.ui.common.layout.LocalAppWindowSizeClass
import com.viel.aplayer.ui.common.theme.APlayerTheme
import com.viel.aplayer.ui.settings.components.AboutSection
import com.viel.aplayer.ui.settings.components.BackupRestoreSection
import com.viel.aplayer.ui.settings.components.InterfaceSettingsSection
import com.viel.aplayer.ui.settings.components.LibraryDirectoriesSection
import com.viel.aplayer.ui.settings.components.NetworkSecuritySection
import com.viel.aplayer.ui.settings.components.PlaybackBehaviorSection
import com.viel.aplayer.ui.settings.components.SleepTimerSection
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import kotlinx.coroutines.launch

/**
 * SettingsScreen Composable: Defines the top-level settings controller view, handling local inputs, dialog visibility, and composing setting sections.
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
    // Insecure TLS Config: Expose insecure TLS parameter flag and its status modification callback.
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
    glassEffectMode: GlassEffectMode,
    settingsHazeState: HazeState? = null,
    // Settings Dialog Intent Routing (Let the overlay own modal surfaces instead of the sampled page content)
    // SettingsScreen emits root and add-library intents so SettingsOverlay can render dialogs beside the page hazeSource.
    onRootClick: (SettingsRootItem) -> Unit = {},
    onAddLibraryClick: () -> Unit = {},
    onDeletedBookRecoveryClick: () -> Unit = {},
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
    // Resolve Window Layout: Retrieve current viewport details via LocalAppWindowSizeClass
    val windowClass = LocalAppWindowSizeClass.current

    val safeDrawingPadding = WindowInsets.safeDrawing.exclude(WindowInsets.ime).asPaddingValues()
    val layoutDirection = androidx.compose.ui.platform.LocalLayoutDirection.current
    val settingsStartPadding = safeDrawingPadding.calculateStartPadding(layoutDirection)
    val settingsEndPadding = safeDrawingPadding.calculateEndPadding(layoutDirection)
    val density = LocalDensity.current
    var settingsTopBarHeightPx by remember { mutableIntStateOf(0) }
    val resolvedSettingsHazeState = settingsHazeState.takeIf { glassEffectMode == GlassEffectMode.Haze }
    // Settings Top Bar Height Resolution (Reserve space for overlay chrome)
    // A measured value keeps the list aligned with the real header, while the fallback protects first-frame and preview layouts before measurement arrives.
    val measuredSettingsTopBarHeight = if (settingsTopBarHeightPx > 0) {
        with(density) { settingsTopBarHeightPx.toDp() }
    } else {
        safeDrawingPadding.calculateTopPadding() + 64.dp
    }

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                // Remove Landscape Width Constraint: Fill maximum width regardless of wide layout to support edge-to-edge settings display
                .fillMaxWidth()
        ) {
            Scaffold(
                modifier = Modifier
                    // Settings Content Surface Bounds (Keep the sampled content layer full size)
                    // The Haze source must cover the whole settings panel so the overlay top bar always has a complete backdrop to sample.
                    .fillMaxSize()
                    .then(
                        if (resolvedSettingsHazeState != null) {
                            // Settings Content Haze Source (Expose the full settings content surface to overlay chrome)
                            // Registering the Scaffold content layer preserves background sampling behind the top bar while keeping the top bar itself outside the source.
                            Modifier.hazeSource(resolvedSettingsHazeState)
                        } else {
                            Modifier
                        }
                    ),
                // Title: Dynamic Scaffold Background Color (Set containerColor to background when Haze is active)
                // Overrides containerColor to background color under Haze mode so that hazeSource has a solid background to sample from,
                // while keeping it transparent under Material mode to avoid duplicate draws.
                containerColor = if (resolvedSettingsHazeState != null) MaterialTheme.colorScheme.background else Color.Transparent,
                // Settings Content Insets (Let overlay top bar own top spacing)
                // The list reads bottom system padding from Scaffold, while the measured overlay header supplies its own top content padding.
                contentWindowInsets = WindowInsets.safeDrawing.exclude(WindowInsets.ime)
            ) { innerPadding ->
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = settingsStartPadding,
                        end = settingsEndPadding,
                        top = measuredSettingsTopBarHeight,
                        bottom = innerPadding.calculateBottomPadding()
                    )
                ) {
                    // Settings Functional Cluster Order (Render settings by user-facing capability)
                    // Media sources, appearance, playback behavior, sleep automation, network safety, and app info stay in separate clusters so unrelated controls no longer share one section.
                    item {
                        LibraryDirectoriesSection(
                            libraryRootDisplays = libraryRootDisplays,
                            onRootClick = onRootClick,
                            onAddLibraryClick = onAddLibraryClick,
                            // Deleted Book Recovery Entry (Forward settings row clicks to overlay-owned sub-navigation)
                            // The page remains stateless; SettingsOverlay decides which settings sub-screen is currently active.
                            onDeletedBookRecoveryClick = onDeletedBookRecoveryClick
                        )
                    }
                    item {
                        InterfaceSettingsSection(
                            appLanguage = appLanguage,
                            onLanguageClick = onLanguageClick,
                            themeMode = themeMode,
                            onThemeModeChange = onThemeModeChange,
                            isDynamicColorEnabled = isDynamicColorEnabled,
                            onDynamicColorEnabledChange = onDynamicColorEnabledChange,
                            glassEffectMode = glassEffectMode,
                            onGlassEffectModeChange = onGlassEffectModeChange
                        )
                    }
                    item {
                        PlaybackBehaviorSection(
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
                            onNotificationAvoidanceEnabledChange = onNotificationAvoidanceEnabledChange
                        )
                    }
                    item {
                        SleepTimerSection(
                            sleepMode = sleepMode,
                            onSleepModeChange = onSleepModeChange,
                            isSleepFadeOutEnabled = isSleepFadeOutEnabled,
                            onSleepFadeOutEnabledChange = onSleepFadeOutEnabledChange,
                            isShakeToResetEnabled = isShakeToResetEnabled,
                            onShakeToResetEnabledChange = onShakeToResetEnabledChange
                        )
                    }
                    item {
                        NetworkSecuritySection(
                            isCleartextTrafficAllowed = isCleartextTrafficAllowed,
                            onCleartextTrafficAllowedChange = onCleartextTrafficAllowedChange,
                            // Insecure TLS Callback (Forward transport-risk setting to the dedicated security section)
                            // Keeping this callback near cleartext HTTP mirrors the new functional grouping in SettingsSections.
                            isAllowInsecureTls = isAllowInsecureTls,
                            onAllowInsecureTlsChange = onAllowInsecureTlsChange
                        )
                    }
                    item {
                        BackupRestoreSection(
                            onExportClick = onExportClick,
                            onImportClick = onImportClick
                        )
                    }
                    item {
                        AboutSection(
                            onAboutLibrariesClick = onAboutLibrariesClick
                        )
                    }
                }
            }
            APlayerGlassTopBar(
                glassEffectMode = glassEffectMode,
                hazeState = resolvedSettingsHazeState,
                onHeightChanged = { settingsTopBarHeightPx = it },
                // Settings Top Bar Overlay Placement (Match Home's chrome layering)
                // Drawing the header above Scaffold content lets settings rows scroll beneath the glass surface instead of being clipped below a Scaffold topBar slot.
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
    // Title: Decouple credentials signature (Use SettingsCredential instead of concrete VFS/ABS credential types)
    // Presentation boundaries should only pass the decoupled SettingsCredential projection.
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
    // Resolve Settings Dialog Haze Source (Gate app-level dialog sampling by current glass mode)
    // All settings modal dialogs use this single stable source instead of the page-local top-bar source.
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
                // UI State Race Prevention: Safe unwrap editingRootId to prevent NullPointerException during transient state updates.
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
        val root = rootToDelete
        SettingsTemplateDialog(
            onDismissRequest = { controller.dialogState = SettingsDialogState.None },
            hazeState = resolvedSettingsDialogHazeState,
            glassEffectMode = glassEffectMode,
            title = { Text(stringResource(R.string.settings_delete_root_title)) },
            text = { Text(stringResource(R.string.settings_delete_root_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDeleteLibraryRoot(root)
                        controller.dialogState = SettingsDialogState.None
                    }
                ) {
                    Text(stringResource(R.string.settings_delete_root_confirm), color = MaterialTheme.colorScheme.error)
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
        val root = rootForAction
        // Settings Root Action Projection (Use scene item fields for all root-specific dialogs)
        // Dialog actions no longer dereference persistence root rows; edit, sync, relocate, and delete flows consume only rootId plus provider-specific form fields.
        // Title: UI branching decoupling (Bypass AudiobookSchema dependency in SettingsScreen using computed root kind flags)
        val isAbsRoot = root.isAbsRoot
        val isWebDavRoot = root.isWebDavRoot
        val scope = rememberCoroutineScope()
        SettingsTemplateDialog(
            onDismissRequest = { controller.dialogState = SettingsDialogState.None },
            hazeState = resolvedSettingsDialogHazeState,
            glassEffectMode = glassEffectMode,
            title = { Text(root.displayName.ifBlank { stringResource(R.string.settings_root_action_title_fallback) }) },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                // Title: SAF Relocate check (Check isSafRoot to trigger storage relocations)
                                if (root.isSafRoot) {
                                    controller.editingSafRootId = root.rootId
                                    controller.dialogState = SettingsDialogState.None
                                    onLaunchSafRootPicker()
                                } else if (isWebDavRoot) {
                                    val creds = getWebDavCredentials(root.credentialId ?: "")
                                    controller.webDavUrl = root.sourceUri
                                    controller.webDavDisplayName = root.displayName
                                    controller.webDavBasePath = root.basePath
                                    controller.webDavUsername = creds?.username ?: ""
                                    controller.webDavPassword = creds?.password ?: ""
                                    controller.editingRootId = root.rootId
                                    controller.dialogState = SettingsDialogState.WebDavRoot
                                } else if (isAbsRoot) {
                                    scope.launch {
                                        val creds = getAbsCredential(root.credentialId ?: "")
                                        controller.absBaseUrl = root.sourceUri
                                        controller.absUsername = creds?.username ?: ""
                                        controller.absPassword = ""
                                        controller.absLibraryId = root.basePath
                                        controller.absLibraryName = root.displayName
                                        controller.absDisplayName = root.displayName
                                        controller.editingRootId = root.rootId
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
                            // Title: SAF Relocate action label (Check isSafRoot to show relocation text versus edit credentials text)
                            if (root.isSafRoot) {
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
                                    onAbsSync(root.rootId)
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
                                controller.dialogState = SettingsDialogState.DeleteRoot(root)
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

    val importUri = (dialogState as? SettingsDialogState.ImportConfirm)?.uri
    if (importUri != null) {
        // Title: Render Import Confirm Dialog (Show overwrite alert before replacing databases and preferences)
        // Instructs user that current audio bookmarks/settings will be replaced and SAF folders require re-granting.
        SettingsTemplateDialog(
            onDismissRequest = { controller.dialogState = SettingsDialogState.None },
            hazeState = resolvedSettingsDialogHazeState,
            glassEffectMode = glassEffectMode,
            title = { Text(stringResource(R.string.settings_import_confirm_title)) },
            text = { Text(stringResource(R.string.settings_import_confirm_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        controller.dialogState = SettingsDialogState.None
                        onImportConfirm(importUri)
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
                // Preview Bypass Parameter: Provide dummy values for isAllowInsecureTls settings fields.
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
                themeMode = ThemeMode.System,
                onThemeModeChange = {},
                isDynamicColorEnabled = true,
                onDynamicColorEnabledChange = {},
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
