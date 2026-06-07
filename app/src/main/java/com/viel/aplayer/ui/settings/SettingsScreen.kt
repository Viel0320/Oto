package com.viel.aplayer.ui.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.viel.aplayer.R
import com.viel.aplayer.abs.auth.AbsCredential
import com.viel.aplayer.data.db.AudiobookSchema
import com.viel.aplayer.data.entity.LibraryRootEntity
import com.viel.aplayer.data.store.AppSettings
import com.viel.aplayer.data.store.GlassEffectMode
import com.viel.aplayer.data.store.SleepMode
import com.viel.aplayer.data.store.ThemeMode
import com.viel.aplayer.library.vfs.sourceProvider.webdav.WebDavCredential
import com.viel.aplayer.ui.common.APlayerGlassTopBar
import com.viel.aplayer.ui.common.theme.APlayerTheme
import com.viel.aplayer.ui.common.theme.LocalWindowClass
import com.viel.aplayer.ui.common.theme.WindowClass
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
    onLibraryRootSelected: (Uri) -> Unit,
    onSafRootRelocated: (String, Uri) -> Unit,
    onWebDavRootSubmitted: (url: String, username: String, password: String, displayName: String, basePath: String) -> Unit,
    onWebDavRootUpdated: (id: String, url: String, username: String, password: String, displayName: String, basePath: String) -> Unit,
    webDavConnectionState: WebDavConnectionUiState,
    onWebDavConnectionTest: (url: String, username: String, password: String, basePath: String, editingRootId: String?) -> Unit,
    onResetWebDavConnectionState: () -> Unit,
    onResetAbsConnectionState: () -> Unit,
    onAbsConnectionTest: (baseUrl: String, username: String, password: String, editingRootId: String?) -> Unit,
    onAbsRootSubmitted: (baseUrl: String, username: String, password: String, libraryId: String, libraryName: String, editingRootId: String?) -> Unit,
    getWebDavCredentials: (credentialId: String) -> WebDavCredential?,
    getAbsCredential: suspend (credentialId: String) -> AbsCredential?,
    onAbsSync: (rootId: String) -> Unit,
    onRescan: () -> Unit,
    libraryRootDisplays: List<LibraryRootDisplayState>,
    absConnectionState: AbsConnectionUiState,
    isChapterProgressMode: Boolean,
    onChapterProgressModeChange: (Boolean) -> Unit,
    isCleartextTrafficAllowed: Boolean,
    onCleartextTrafficAllowedChange: (Boolean) -> Unit,
    // Insecure TLS Config: Expose insecure TLS parameter flag and its status modification callback.
    isAllowInsecureTls: Boolean,
    onAllowInsecureTlsChange: (Boolean) -> Unit,
    onDeleteLibraryRoot: (LibraryRootEntity) -> Unit,
    isSkipSilenceEnabled: Boolean,
    onSkipSilenceEnabledChange: (Boolean) -> Unit,
    isSleepFadeOutEnabled: Boolean,
    onSleepFadeOutEnabledChange: (Boolean) -> Unit,
    isShakeToResetEnabled: Boolean,
    onShakeToResetEnabledChange: (Boolean) -> Unit,
    sleepMode: SleepMode,
    onSleepModeChange: (SleepMode) -> Unit,
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
    isDynamicColorEnabled: Boolean,
    onDynamicColorEnabledChange: (Boolean) -> Unit,
    glassEffectMode: GlassEffectMode,
    settingsHazeState: HazeState? = null,
    onGlassEffectModeChange: (GlassEffectMode) -> Unit,
    autoRewindSeconds: Int,
    onAutoRewindSecondsChange: (Int) -> Unit,
    isNotificationAvoidanceEnabled: Boolean,
    onNotificationAvoidanceEnabledChange: (Boolean) -> Unit,
    onAboutLibrariesClick: () -> Unit
) {
    var editingSafRootId by remember { mutableStateOf<String?>(null) }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        uri?.let {
            if (editingSafRootId != null) {
                onSafRootRelocated(editingSafRootId!!, it)
                editingSafRootId = null
            } else {
                onLibraryRootSelected(it)
            }
        }
    }

    var dialogState by remember { mutableStateOf<SettingsDialogState>(SettingsDialogState.None) }
    val rootToDelete = (dialogState as? SettingsDialogState.DeleteRoot)?.root
    val showWebDavDialog = dialogState == SettingsDialogState.WebDavRoot
    val showAbsDialog = dialogState == SettingsDialogState.AbsServer
    val showAddLibraryTypeDialog = dialogState == SettingsDialogState.AddLibraryType
    val rootForAction = (dialogState as? SettingsDialogState.RootActions)?.root

    var webDavUrl by remember { mutableStateOf("") }
    var webDavUsername by remember { mutableStateOf("") }
    var webDavPassword by remember { mutableStateOf("") }
    var webDavDisplayName by remember { mutableStateOf("") }
    var webDavBasePath by remember { mutableStateOf("") }
    var absBaseUrl by remember { mutableStateOf("") }
    var absUsername by remember { mutableStateOf("") }
    var absPassword by remember { mutableStateOf("") }
    var absLibraryId by remember { mutableStateOf("") }
    var absLibraryName by remember { mutableStateOf("") }
    var absDisplayName by remember { mutableStateOf("") }
    var editingRootId by remember { mutableStateOf<String?>(null) }

    val windowClass = LocalWindowClass.current
    val useWideLayout = windowClass.isWideScreen

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

    if (showWebDavDialog) {
        WebDavRootDialog(
            url = webDavUrl,
            username = webDavUsername,
            password = webDavPassword,
            displayName = webDavDisplayName,
            basePath = webDavBasePath,
            editingRootId = editingRootId,
            hazeState = settingsHazeState,
            glassEffectMode = glassEffectMode,
            connectionState = webDavConnectionState,
            onUrlChange = { 
                webDavUrl = it
                onResetWebDavConnectionState()
            },
            onUsernameChange = { 
                webDavUsername = it
                onResetWebDavConnectionState()
            },
            onPasswordChange = { 
                webDavPassword = it
                onResetWebDavConnectionState()
            },
            onDisplayNameChange = { webDavDisplayName = it },
            onBasePathChange = { 
                webDavBasePath = it
                onResetWebDavConnectionState()
            },
            onTestConnection = {
                onWebDavConnectionTest(
                    webDavUrl.trim(),
                    webDavUsername.trim(),
                    webDavPassword,
                    webDavBasePath.trim(),
                    editingRootId
                )
            },
            onDismiss = {
                dialogState = SettingsDialogState.None
                webDavUrl = ""
                webDavUsername = ""
                webDavPassword = ""
                webDavDisplayName = ""
                webDavBasePath = ""
                editingRootId = null
                onResetWebDavConnectionState()
            },
            onConfirm = {
                if (editingRootId != null) {
                    onWebDavRootUpdated(
                        editingRootId!!,
                        webDavUrl.trim(),
                        webDavUsername.trim(),
                        webDavPassword,
                        webDavDisplayName.trim(),
                        webDavBasePath.trim()
                    )
                } else {
                    onWebDavRootSubmitted(
                        webDavUrl.trim(),
                        webDavUsername.trim(),
                        webDavPassword,
                        webDavDisplayName.trim(),
                        webDavBasePath.trim()
                    )
                }
                dialogState = SettingsDialogState.None
                webDavUrl = ""
                webDavUsername = ""
                webDavPassword = ""
                webDavDisplayName = ""
                webDavBasePath = ""
                editingRootId = null
                onResetWebDavConnectionState()
            }
        )
    }

    if (showAbsDialog) {
        AbsServerDialog(
            baseUrl = absBaseUrl,
            username = absUsername,
            password = absPassword,
            displayName = absDisplayName,
            editingRootId = editingRootId,
            hazeState = settingsHazeState,
            glassEffectMode = glassEffectMode,
            connectionState = absConnectionState,
            selectedLibraryId = absLibraryId,
            selectedLibraryName = absLibraryName,
            onBaseUrlChange = { 
                absBaseUrl = it
                onResetAbsConnectionState()
            },
            onUsernameChange = { 
                absUsername = it
                onResetAbsConnectionState()
            },
            onPasswordChange = { 
                absPassword = it
                onResetAbsConnectionState()
            },
            onDisplayNameChange = { absDisplayName = it },
            onLibrarySelected = { id, name ->
                absLibraryId = id
                absLibraryName = name
            },
            onTestConnection = { onAbsConnectionTest(absBaseUrl.trim(), absUsername.trim(), absPassword, editingRootId) },
            onDismiss = {
                dialogState = SettingsDialogState.None
                absBaseUrl = ""
                absUsername = ""
                absPassword = ""
                absLibraryId = ""
                absLibraryName = ""
                absDisplayName = ""
                editingRootId = null
                onResetAbsConnectionState()
            },
            onConfirm = {
                onAbsRootSubmitted(
                    absBaseUrl.trim(),
                    absUsername.trim(),
                    absPassword,
                    absLibraryId.trim(),
                    absLibraryName.trim(),
                    editingRootId
                )
                dialogState = SettingsDialogState.None
                absBaseUrl = ""
                absUsername = ""
                absPassword = ""
                absLibraryId = ""
                absLibraryName = ""
                absDisplayName = ""
                editingRootId = null
                onResetAbsConnectionState()
            }
        )
    }

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth(if (useWideLayout) 0.8f else 1f)
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
                    item {
                        LibraryDirectoriesSection(
                            libraryRootDisplays = libraryRootDisplays,
                            onRootClick = { dialogState = SettingsDialogState.RootActions(it) },
                            onAddLibraryClick = { dialogState = SettingsDialogState.AddLibraryType }
                        )
                    }
                    item {
                        InterfaceSettingsSection(
                            themeMode = themeMode,
                            onThemeModeChange = onThemeModeChange,
                            isDynamicColorEnabled = isDynamicColorEnabled,
                            onDynamicColorEnabledChange = onDynamicColorEnabledChange,
                            glassEffectMode = glassEffectMode,
                            onGlassEffectModeChange = onGlassEffectModeChange
                        )
                    }
                    item {
                        PlaybackNetworkSection(
                            isChapterProgressMode = isChapterProgressMode,
                            onChapterProgressModeChange = onChapterProgressModeChange,
                            isCleartextTrafficAllowed = isCleartextTrafficAllowed,
                            onCleartextTrafficAllowedChange = onCleartextTrafficAllowedChange,
                            // Insecure TLS Callback: Forward isAllowInsecureTls configuration and its toggler downstream.
                            isAllowInsecureTls = isAllowInsecureTls,
                            onAllowInsecureTlsChange = onAllowInsecureTlsChange,
                            isNotificationAvoidanceEnabled = isNotificationAvoidanceEnabled,
                            onNotificationAvoidanceEnabledChange = onNotificationAvoidanceEnabledChange
                        )
                    }
                    item {
                        SkipSilenceSection(
                            isSkipSilenceEnabled = isSkipSilenceEnabled,
                            onSkipSilenceEnabledChange = onSkipSilenceEnabledChange
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
                        AutoRewindSection(
                            autoRewindSeconds = autoRewindSeconds,
                            onAutoRewindSecondsChange = onAutoRewindSecondsChange
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

    if (rootToDelete != null) {
        val root = rootToDelete
        SettingsTemplateDialog(
            onDismissRequest = { dialogState = SettingsDialogState.None },
            title = { Text("移除媒体库根目录") },
            text = { Text("移除此媒体库根目录将使应用失去对该目录的物理文件访问权限，所有相关的书籍记录将会从库中移除，但不会删除您存储卡中的物理音频文件。您确定要移除该目录并释放物理授权吗？") },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDeleteLibraryRoot(root)
                        dialogState = SettingsDialogState.None
                    }
                ) {
                    Text("确定", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { dialogState = SettingsDialogState.None }) {
                    Text("取消")
                }
            }
        )
    }

    if (showAddLibraryTypeDialog) {
        SettingsTemplateDialog(
            onDismissRequest = { dialogState = SettingsDialogState.None },
            title = { Text("选择媒体库类别") },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                dialogState = SettingsDialogState.None
                                editingSafRootId = null
                                launcher.launch(null)
                            }
                            .padding(vertical = 12.dp, horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Rounded.FolderOpen, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.width(12.dp))
                        Text("本地（SAF）", style = MaterialTheme.typography.bodyLarge)
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                dialogState = SettingsDialogState.None
                                webDavUrl = ""
                                webDavDisplayName = ""
                                webDavBasePath = ""
                                webDavUsername = ""
                                webDavPassword = ""
                                editingRootId = null
                                dialogState = SettingsDialogState.WebDavRoot
                            }
                            .padding(vertical = 12.dp, horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Rounded.Cloud, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.width(12.dp))
                        Text("WebDAV 媒体库", style = MaterialTheme.typography.bodyLarge)
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                dialogState = SettingsDialogState.None
                                absBaseUrl = ""
                                absUsername = ""
                                absPassword = ""
                                absLibraryId = ""
                                absLibraryName = ""
                                absDisplayName = ""
                                editingRootId = null
                                dialogState = SettingsDialogState.AbsServer
                            }
                            .padding(vertical = 12.dp, horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Rounded.Sync, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.width(12.dp))
                        Text("Audiobookshelf 服务器 (absserver)", style = MaterialTheme.typography.bodyLarge)
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { dialogState = SettingsDialogState.None }) {
                    Text("取消")
                }
            }
        )
    }

    if (rootForAction != null) {
        val root = rootForAction
        val isAbsRoot = root.sourceType == AudiobookSchema.LibrarySourceType.ABS
        val isWebDavRoot = root.sourceType == AudiobookSchema.LibrarySourceType.WEBDAV
        val scope = rememberCoroutineScope()
        SettingsTemplateDialog(
            onDismissRequest = { dialogState = SettingsDialogState.None },
            title = { Text(root.displayName.ifBlank { "媒体库操作" }) },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                if (root.sourceType == AudiobookSchema.LibrarySourceType.SAF) {
                                    editingSafRootId = root.id
                                    dialogState = SettingsDialogState.None
                                    launcher.launch(null)
                                } else if (isWebDavRoot) {
                                    val creds = getWebDavCredentials(root.credentialId ?: "")
                                    webDavUrl = root.sourceUri
                                    webDavDisplayName = root.displayName
                                    webDavBasePath = root.basePath
                                    webDavUsername = creds?.username ?: ""
                                    webDavPassword = creds?.password ?: ""
                                    editingRootId = root.id
                                    dialogState = SettingsDialogState.WebDavRoot
                                } else if (isAbsRoot) {
                                    scope.launch {
                                        val creds = getAbsCredential(root.credentialId ?: "")
                                        absBaseUrl = root.sourceUri
                                        absUsername = creds?.username ?: ""
                                        absPassword = ""
                                        absLibraryId = root.basePath
                                        absLibraryName = root.displayName
                                        absDisplayName = root.displayName
                                        editingRootId = root.id
                                        dialogState = SettingsDialogState.AbsServer
                                    }
                                }
                            }
                            .padding(vertical = 12.dp, horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Rounded.Info, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(if (root.sourceType == AudiobookSchema.LibrarySourceType.SAF) "重新定位书库位置" else "编辑媒体库配置", style = MaterialTheme.typography.bodyLarge)
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                if (isAbsRoot) {
                                    onAbsSync(root.id)
                                } else {
                                    onRescan()
                                }
                                dialogState = SettingsDialogState.None
                            }
                            .padding(vertical = 12.dp, horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Rounded.Refresh, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(if (isAbsRoot) "同步" else "重新扫描", style = MaterialTheme.typography.bodyLarge)
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                dialogState = SettingsDialogState.DeleteRoot(root)
                            }
                            .padding(vertical = 12.dp, horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Rounded.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                        Spacer(modifier = Modifier.width(12.dp))
                        Text("移除媒体库", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { dialogState = SettingsDialogState.None }) {
                    Text("取消")
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
            LocalWindowClass provides WindowClass.PortraitPhone
        ) {
            SettingsScreen(
                onBack = {},
                onLibraryRootSelected = {},
                onSafRootRelocated = { _, _ -> },
                onWebDavRootSubmitted = { _, _, _, _, _ -> },
                onWebDavRootUpdated = { _, _, _, _, _, _ -> },
                webDavConnectionState = WebDavConnectionUiState(),
                onWebDavConnectionTest = { _, _, _, _, _ -> },
                onResetWebDavConnectionState = {},
                onResetAbsConnectionState = {},
                onAbsConnectionTest = { _, _, _, _ -> },
                onAbsRootSubmitted = { _, _, _, _, _, _ -> },
                getWebDavCredentials = { _ -> null },
                getAbsCredential = { _ -> null },
                onAbsSync = {},
                onRescan = {},
                libraryRootDisplays = emptyList(),
                absConnectionState = AbsConnectionUiState(),
                isChapterProgressMode = false,
                onChapterProgressModeChange = {},
                isCleartextTrafficAllowed = false,
                onCleartextTrafficAllowedChange = {},
                // Preview Bypass Parameter: Provide dummy values for isAllowInsecureTls settings fields.
                isAllowInsecureTls = false,
                onAllowInsecureTlsChange = {},
                onDeleteLibraryRoot = {},
                isSkipSilenceEnabled = false,
                onSkipSilenceEnabledChange = {},
                isSleepFadeOutEnabled = true,
                onSleepFadeOutEnabledChange = {},
                isShakeToResetEnabled = true,
                onShakeToResetEnabledChange = {},
                sleepMode = SleepMode.Regular,
                onSleepModeChange = {},
                themeMode = ThemeMode.System,
                onThemeModeChange = {},
                isDynamicColorEnabled = true,
                onDynamicColorEnabledChange = {},
                glassEffectMode = AppSettings.DEFAULT_GLASS_EFFECT_MODE,
                onGlassEffectModeChange = {},
                autoRewindSeconds = 0,
                onAutoRewindSecondsChange = {},
                isNotificationAvoidanceEnabled = false,
                onNotificationAvoidanceEnabledChange = {},
                onAboutLibrariesClick = {}
            )
        }
    }
}
