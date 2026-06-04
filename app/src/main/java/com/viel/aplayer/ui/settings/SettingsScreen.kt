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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.LinearScale
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.viel.aplayer.R
import com.viel.aplayer.data.db.AudiobookSchema
import com.viel.aplayer.data.entity.LibraryRootEntity
import com.viel.aplayer.data.store.AppSettings
import com.viel.aplayer.data.store.GlassEffectMode
import com.viel.aplayer.data.store.SleepMode
import com.viel.aplayer.ui.common.theme.APlayerTheme
import com.viel.aplayer.ui.common.theme.LocalWindowClass
import com.viel.aplayer.ui.common.theme.WindowClass

/**
 * Stateless settings view (Stateless composable rendering settings configurations)
 * Represents the main settings layout. Separated from SettingsActivity to enforce stateless design.
 * Passes interaction callbacks such as updates, additions, and deletions up to the container.
 * Supports adaptivity across multi-size screens during layout rendering.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onLibraryRootSelected: (Uri) -> Unit,
    // WebDAV submission callback (To delegate credential routing to state container)
    // Passes connection attributes up instead of performing DB transactions locally.
    onWebDavRootSubmitted: (url: String, username: String, password: String, displayName: String, basePath: String) -> Unit,
    onAbsConnectionTest: (baseUrl: String, username: String, password: String) -> Unit,
    onAbsRootSubmitted: (baseUrl: String, username: String, password: String, libraryId: String, libraryName: String) -> Unit,
    onAbsSync: (rootId: String) -> Unit,
    onAbsBackgroundSync: (rootId: String) -> Unit,
    absSyncConfirmationState: AbsSyncConfirmationState?,
    onDismissLargeAbsSync: () -> Unit,
    onRescan: () -> Unit,
    libraryRoots: List<LibraryRootEntity>,
    absServers: List<AbsServerSettingsState>,
    absConnectionState: AbsConnectionUiState,
    isChapterProgressMode: Boolean,
    onChapterProgressModeChange: (Boolean) -> Unit,
    // Cleartext allowance flag (To stream network security preferences)
    isCleartextTrafficAllowed: Boolean,
    onCleartextTrafficAllowedChange: (Boolean) -> Unit,
    // Deregister callback (To handle physical directory authorization removals)
    onDeleteLibraryRoot: (LibraryRootEntity) -> Unit,
    /**
     * Skip silence configuration (To toggle audio silence stripping preference)
     * Tracks the global skip silence setting and its state-transition callbacks.
     */
    isSkipSilenceEnabled: Boolean,
    onSkipSilenceEnabledChange: (Boolean) -> Unit,
    // Sleep fade-out configuration (To verify if volume decay is globally enabled)
    isSleepFadeOutEnabled: Boolean,
    // Toggle volume decay (To delegate sleep volume decay preferences to caller)
    onSleepFadeOutEnabledChange: (Boolean) -> Unit,
    // Shake reset configuration (To verify if motion reset triggers are enabled)
    isShakeToResetEnabled: Boolean,
    // Toggle motion resetting (To delegate shake resetting preference to container)
    onShakeToResetEnabledChange: (Boolean) -> Unit,
    // Active sleep mode (To indicate sleep countdown strategies)
    sleepMode: SleepMode,
    // Change sleep mode (To notify sleep mode adjustments to controller)
    onSleepModeChange: (SleepMode) -> Unit,
    // Backdrop effect configuration (To dictate visual containers style between Material and miuix-blur styles)
    glassEffectMode: GlassEffectMode,
    // Toggle backdrop style (To delegate blur adjustments to controller)
    onGlassEffectModeChange: (GlassEffectMode) -> Unit,
    // Active auto-rewind seconds (To indicate rollback offset values)
    autoRewindSeconds: Int,
    // Change rewind duration (To notify auto-rollback parameter updates)
    onAutoRewindSecondsChange: (Int) -> Unit,
    // Notification avoidance toggle (To cache playback avoidance settings)
    isNotificationAvoidanceEnabled: Boolean,
    // Change avoidance toggle (To notify notification avoidance preferences to caller)
    onNotificationAvoidanceEnabledChange: (Boolean) -> Unit,
    // Licenses trigger (To route navigation events to AboutLibrariesScreen)
    onAboutLibrariesClick: () -> Unit
) {
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        uri?.let(onLibraryRootSelected)
    }

    // Delete dialog selection state (To hold current library root scheduled for deletion check)
    // Triggers confirmation alert dialog showing risk details to prevent user accidents.
    var rootToDelete by remember { mutableStateOf<LibraryRootEntity?>(null) }
    // WebDAV panel visibility state (To toggle display of the dialog popup locally)
    // Delegates network requests and persistence routines to ViewModel upon confirmation.
    var showWebDavDialog by remember { mutableStateOf(false) }
    var webDavUrl by remember { mutableStateOf("") }
    var webDavUsername by remember { mutableStateOf("") }
    var webDavPassword by remember { mutableStateOf("") }
    var webDavDisplayName by remember { mutableStateOf("") }
    var webDavBasePath by remember { mutableStateOf("") }
    var showAbsDialog by remember { mutableStateOf(false) }
    var absBaseUrl by remember { mutableStateOf("") }
    var absUsername by remember { mutableStateOf("") }
    var absPassword by remember { mutableStateOf("") }
    var absLibraryId by remember { mutableStateOf("") }
    var absLibraryName by remember { mutableStateOf("") }

    // Load window parameters (To adapt layout proportions for wide displays)
    // Avoids reading LocalConfiguration attributes directly by subscribing to WindowClass values.
    val windowClass = LocalWindowClass.current
    val isLandscape = windowClass.isLandscape
    val isWideScreen = windowClass.isTablet
    val useWideLayout = windowClass.isWideScreen

    // Safe drawing insets (To avoid hardcoded display margin constants on notched displays)
    // Reads WindowInsets.safeDrawing boundaries to offset items.
    val safeDrawingPadding = WindowInsets.safeDrawing.asPaddingValues()
    val layoutDirection = androidx.compose.ui.platform.LocalLayoutDirection.current
    val settingsStartPadding = safeDrawingPadding.calculateStartPadding(layoutDirection)
    val settingsEndPadding = safeDrawingPadding.calculateEndPadding(layoutDirection)

    if (showWebDavDialog) {
        // WebDAV input layout (To collect connection information)
        // Defers credentials checking to Provider scan routines.
        WebDavRootDialog(
            url = webDavUrl,
            username = webDavUsername,
            password = webDavPassword,
            displayName = webDavDisplayName,
            basePath = webDavBasePath,
            onUrlChange = { webDavUrl = it },
            onUsernameChange = { webDavUsername = it },
            onPasswordChange = { webDavPassword = it },
            onDisplayNameChange = { webDavDisplayName = it },
            onBasePathChange = { webDavBasePath = it },
            onDismiss = { showWebDavDialog = false },
            onConfirm = {
                onWebDavRootSubmitted(
                    webDavUrl.trim(),
                    webDavUsername.trim(),
                    webDavPassword,
                    webDavDisplayName.trim(),
                    webDavBasePath.trim()
                )
                showWebDavDialog = false
            }
        )
    }

    if (showAbsDialog) {
        AbsServerDialog(
            baseUrl = absBaseUrl,
            username = absUsername,
            password = absPassword,
            connectionState = absConnectionState,
            selectedLibraryId = absLibraryId,
            selectedLibraryName = absLibraryName,
            onBaseUrlChange = { absBaseUrl = it },
            onUsernameChange = { absUsername = it },
            onPasswordChange = { absPassword = it },
            onLibrarySelected = { id, name ->
                absLibraryId = id
                absLibraryName = name
            },
            onTestConnection = { onAbsConnectionTest(absBaseUrl.trim(), absUsername.trim(), absPassword) },
            onDismiss = {
                showAbsDialog = false
                absLibraryId = ""
                absLibraryName = ""
            },
            onConfirm = {
                onAbsRootSubmitted(absBaseUrl.trim(), absUsername.trim(), absPassword, absLibraryId.trim(), absLibraryName.trim())
                showAbsDialog = false
                absLibraryId = ""
                absLibraryName = ""
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
                topBar = {
                    CenterAlignedTopAppBar(
                        // Adjust visual boundaries (To render app bar background color fully to margins)
                        // Bypasses container spacing padding configurations.
                        modifier = Modifier,
                        // Calculate top bar margins (To exclude system navigation bars on rotated layouts)
                        // Uses exclude calculations rather than manual padding values.
                        windowInsets = WindowInsets.safeDrawing.exclude(WindowInsets.navigationBars),
                        title = { Text(stringResource(R.string.settings_title)) },
                        navigationIcon = {
                            IconButton(
                                onClick = onBack,
                                modifier = Modifier
                            ) {
                                Icon(
                                    Icons.AutoMirrored.Rounded.ArrowBack,
                                    contentDescription = stringResource(R.string.back_content_description)
                                )
                            }
                        }
                    )
                }
            ) { innerPadding ->
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                    // Apply insets padding (To secure text and buttons visibility from notches or overlays)
                    // Configures lazy column margins using landscape insets calculations.
                    contentPadding = PaddingValues(
                        start = settingsStartPadding,
                        end = settingsEndPadding
                    )
                ) {
                    // === Section 1: Library Directories ===
                    item {
                        SettingsSectionHeader(title = "媒体库管理")
                    }
                    item {
                        SettingsItem(
                            title = stringResource(R.string.library_folder_title),
                            subtitle = stringResource(R.string.library_folder_subtitle),
                            icon = Icons.Rounded.FolderOpen,
                            onClick = { launcher.launch(null) }
                        )
                    }
                    item {
                        // WebDAV entry point (To support remote storage endpoints alongside local ones)
                        SettingsItem(
                            title = "添加 WebDAV 媒体库",
                            subtitle = "连接远程 WebDAV 目录",
                            icon = Icons.Rounded.Cloud,
                            onClick = { showWebDavDialog = true }
                        )
                    }
                    item {
                        SettingsItem(
                            title = "添加 ABS Server",
                            subtitle = "添加 Audiobookshelf 服务器并选择一个 book library",
                            icon = Icons.Rounded.Sync,
                            onClick = { showAbsDialog = true }
                        )
                    }

                    // Configure list key (To assign sourceUri as stable key identifier)
                    // Prevents recycling errors or misplaced widget states in LazyColumn list.
                    items(libraryRoots.size, key = { libraryRoots[it].sourceUri }) { index ->
                        val root = libraryRoots[index]
                        val isWebDavRoot = root.sourceType == AudiobookSchema.LibrarySourceType.WEBDAV
                        val isAbsRoot = root.sourceType == AudiobookSchema.LibrarySourceType.ABS
                        val rootTitle = if (isWebDavRoot) {
                            root.displayName.ifBlank { "${root.sourceUri}${root.basePath}" }
                        } else if (isAbsRoot) {
                            root.displayName.ifBlank { "ABS ${root.basePath}" }
                        } else {
                            // Parse local tree URI (To display human-readable terminal folder name)
                            // Trims structural URI prefixes to avoid confusing users with absolute paths.
                            try {
                                Uri.decode(root.sourceUri).substringAfterLast(":")
                            } catch (_: Exception) {
                                root.sourceUri
                            }
                        }
                        val rootSubtitle = if (isWebDavRoot) {
                            // WebDAV status information (To display remote url parameters and connection state)
                            // Informs users of connection state to isolate network issues from permission issues.
                            "WebDAV: ${root.sourceUri}${root.basePath} · 可用性: ${root.availabilityStatus}"
                        } else if (isAbsRoot) {
                            val sync = absServers.firstOrNull { it.rootId == root.id }
                            "ABS: ${root.sourceUri} · Library=${root.displayName} · 状态=${sync?.syncStatus ?: "IDLE"}"
                        } else {
                            "状态: ${root.status}"
                        }
                        
                        // Render directory layout card (To combine detail metadata labels with deletion actions)
                        // Captures user clicks on trashcan button and prompts verification dialog.
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
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
                                Text(text = rootTitle, style = MaterialTheme.typography.titleMedium)
                                Text(
                                    text = rootSubtitle,
                                    style = MaterialTheme.typography.bodySmall, 
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                if (isAbsRoot) {
                                    val sync = absServers.firstOrNull { it.rootId == root.id }
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Button(onClick = { onAbsSync(root.id) }) {
                                            Text("手动同步")
                                        }
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Button(onClick = { onAbsBackgroundSync(root.id) }) {
                                            Text("后台同步")
                                        }
                                    }
                                    if (sync != null) {
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            text = "最近同步: ${sync.lastFullSyncAt ?: 0} · 版本: ${sync.serverVersion ?: "-"}" +
                                                (sync.lastError?.takeIf { it.isNotBlank() }?.let { " · 错误: $it" } ?: ""),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                            IconButton(onClick = { rootToDelete = root }) {
                                Icon(
                                    imageVector = Icons.Rounded.Delete,
                                    contentDescription = "移除媒体库根目录",
                                    tint = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }

                    item {
                        SettingsItem(
                            title = stringResource(R.string.rescan_library_title),
                            subtitle = stringResource(R.string.rescan_library_subtitle),
                            icon = Icons.Rounded.Refresh,
                            onClick = onRescan
                        )
                    }

                    // === Section 2: Interface Settings ===
                    item {
                        SettingsSectionHeader(title = "界面效果")
                    }
                    item {
                        // Backdrop style selector (To toggle container between Material and miuix-blur styles)
                        SettingsSegmentedItem(
                            title = "悬浮层玻璃效果",
                            subtitle = "控制章节列表 and 书籍操作弹窗的背景效果",
                            icon = Icons.Rounded.LinearScale,
                            // Parameter Alignment (Aligns arguments to match redefined parameters of SettingsSegmentedItem)
                            // Renamed selectedMode and onModeSelected to glassEffectMode and onGlassEffectModeChange.
                            glassEffectMode = glassEffectMode,
                            onGlassEffectModeChange = onGlassEffectModeChange
                        )
                    }

                    // === Section 3: Playback and Network ===
                    item {
                        SettingsSectionHeader(title = "播放与网络")
                    }
                    item {
                        SettingsToggleItem(
                            title = stringResource(R.string.chapter_progress_title),
                            subtitle = stringResource(R.string.chapter_progress_subtitle),
                            icon = Icons.Rounded.LinearScale,
                            checked = isChapterProgressMode,
                            onCheckedChange = onChapterProgressModeChange
                        )
                    }
                    item {
                        // Cleartext permission toggle (To toggle http network streaming permission)
                        // Warns about security standards when http sources are used.
                        SettingsToggleItem(
                            title = "允许明文 HTTP 流量",
                            subtitle = "允许应用播放和加载不安全的 http:// 网络有声书源。建议保持关闭以维持最高安全边界。",
                            icon = Icons.Rounded.LinearScale,
                            checked = isCleartextTrafficAllowed,
                            onCheckedChange = onCleartextTrafficAllowedChange
                        )
                    }
                    item {
                        // Avoidance preference toggle (To coordinate audio pause behavior during focus losses)
                        SettingsToggleItem(
                            title = "通知避让",
                            subtitle = "开启后，播留在失去焦点（如收到通知、导航播报、来电等）时暂停，重获焦点时恢复，避免降音避让时漏听内容。",
                            icon = Icons.Rounded.LinearScale,
                            checked = isNotificationAvoidanceEnabled,
                            onCheckedChange = onNotificationAvoidanceEnabledChange
                        )
                    }

                    // === Section 4: Skip Silence ===
                    item {
                        SettingsSectionHeader(title = "自动跳过静音")
                    }
                    item {
                        SettingsToggleItem(
                            title = "自动跳过静音期",
                            subtitle = "在播放有声书时，自动跳过主播停顿、换气及章节末尾等无声片段以提高收听效率。",
                            icon = Icons.Rounded.LinearScale,
                            checked = isSkipSilenceEnabled,
                            onCheckedChange = onSkipSilenceEnabledChange
                        )
                    }

                    // === Section 5: Sleep Timer ===
                    item {
                        SettingsSectionHeader(title = "睡眠定时器")
                    }
                    item {
                        SettingsSegmentedSleepModeItem(
                            title = "睡眠模式",
                            subtitle = "选择计时触发机制（常规: 设定时间即计时；运动跟踪: 静止才计时，运动则暂停；睡眠跟踪: 熟睡才计时）",
                            icon = Icons.Rounded.LinearScale,
                            selectedMode = sleepMode,
                            onModeSelected = { selectedMode ->
                                onSleepModeChange(selectedMode)
                            }
                        )
                    }
                    item {
                        SettingsToggleItem(
                            title = "睡眠倒计时音量渐隐",
                            subtitle = "当倒计时走到最后 10 秒（或章节快结束前 10 秒）时，音量将柔和对数式递减到静音，避免突然的无声惊醒您。",
                            icon = Icons.Rounded.LinearScale,
                            checked = isSleepFadeOutEnabled,
                            onCheckedChange = onSleepFadeOutEnabledChange
                        )
                    }
                    item {
                        SettingsToggleItem(
                            title = "摇晃手机重置睡眠定时器",
                            subtitle = "当进入睡眠定时器最后 10 秒音量渐隐阶段时，轻轻摇晃手机即可触发轻微震动反馈并重置定时器。若为“章节结束停止模式”，摇晃重置时若有下一章将自动顺延为 15 分钟常规倒计时并顺延至下一个章节继续播放，免去夜间亮屏解锁的繁琐。",
                            icon = Icons.Rounded.LinearScale,
                            checked = isShakeToResetEnabled,
                            onCheckedChange = onShakeToResetEnabledChange
                        )
                    }

                    // === Section 6: Auto Rewind ===
                    item {
                        SettingsSectionHeader(title = "自动回放")
                    }
                    item {
                        SettingsSliderItem(
                            title = "暂停自动回放",
                            subtitle = "暂停或以任何方式（通知避让除外）停止播放时自动回退的时长",
                            icon = Icons.Rounded.LinearScale,
                            value = autoRewindSeconds.toFloat(),
                            onValueChange = { onAutoRewindSecondsChange(it.toInt()) },
                            valueRange = 0f..30f,
                            steps = 29, // Subtract endpoints from 31 distinct tick steps between 0s and 30s.
                            valueFormatter = {
                                if (it.toInt() == 0) "已关闭" else String.format(java.util.Locale.US, "%d 秒", it.toInt())
                            },
                            enabled = true
                        )
                    }

                    // === Section 7: About Information ===
                    item {
                        SettingsSectionHeader(title = "关于")
                    }
                    item {
                        SettingsItem(
                            title = "开源许可",
                            subtitle = "查看应用使用的开源库及许可协议",
                            icon = Icons.Rounded.Info,
                            onClick = onAboutLibrariesClick
                        )
                    }
                }
            }
        }
    }

    // Render deletion confirmation (To warn users about library root deletion risks)
    // Ensures database records will be erased while preserving physical storage files.
    if (rootToDelete != null) {
        val root = rootToDelete!!
        AlertDialog(
            onDismissRequest = { rootToDelete = null },
            title = { Text("移除媒体库根目录") },
            text = { Text("移除此媒体库根目录将使应用失去对该目录的物理文件访问权限，所有相关的书籍记录将会从库中移除，但不会删除您存储卡中的物理音频文件。您确定要移除该目录并释放物理授权吗？") },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDeleteLibraryRoot(root)
                        rootToDelete = null
                    }
                ) {
                    Text("确定", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { rootToDelete = null }) {
                    Text("取消")
                }
            }
        )
    }
}

@Composable
private fun AbsServerDialog(
    baseUrl: String,
    username: String,
    password: String,
    connectionState: AbsConnectionUiState,
    selectedLibraryId: String,
    selectedLibraryName: String,
    onBaseUrlChange: (String) -> Unit,
    onUsernameChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onLibrarySelected: (String, String) -> Unit,
    onTestConnection: () -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加 ABS Server") },
        text = {
            Column {
                OutlinedTextField(
                    value = baseUrl,
                    onValueChange = onBaseUrlChange,
                    label = { Text("Base URL") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = username,
                    onValueChange = onUsernameChange,
                    label = { Text("用户名") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = password,
                    onValueChange = onPasswordChange,
                    label = { Text("密码") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = onTestConnection,
                    enabled = baseUrl.isNotBlank() && username.isNotBlank() && password.isNotBlank()
                ) {
                    Text(if (connectionState.isTesting) "连接中..." else "测试连接")
                }
                if (connectionState.loginSucceeded) {
                     Spacer(modifier = Modifier.height(8.dp))
                     Text(
                        text = "登录成功，请选择一个 book library 再点击添加",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                     )
                }
                if (connectionState.serverVersion != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Server Version: ${connectionState.serverVersion}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (selectedLibraryName.isNotBlank()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "已选 Library: $selectedLibraryName",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                connectionState.lastError?.takeIf { it.isNotBlank() }?.let { error ->
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                if (connectionState.libraries.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("选择 Book Library", style = MaterialTheme.typography.titleSmall)
                    connectionState.libraries.forEach { library ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onLibrarySelected(library.id, library.name) }
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            androidx.compose.material3.RadioButton(
                                selected = selectedLibraryId == library.id,
                                onClick = { onLibrarySelected(library.id, library.name) }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("${library.name} (${library.id})")
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                enabled = baseUrl.isNotBlank() && username.isNotBlank() && password.isNotBlank() &&
                    selectedLibraryId.isNotBlank() && selectedLibraryName.isNotBlank()
            ) {
                Text("添加")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}

/**
 * Segmented selection setting item for overlay glass effect.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsSegmentedItem(
    title: String,
    subtitle: String,
    icon: ImageVector,
    glassEffectMode: GlassEffectMode,
    onGlassEffectModeChange: (GlassEffectMode) -> Unit
) {
    // Define Glass Effect Options (Configure Haze and Material modes) Declare modes list using renamed Haze option.
    val modes = listOf(GlassEffectMode.Material, GlassEffectMode.Haze)
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
                        selected = glassEffectMode == mode,
                        onClick = { onGlassEffectModeChange(mode) },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = modes.size)
                    ) {
                        // Render Segment Option (Display descriptive label for selected mode) Display Haze instead of old MiuixBlur text.
                        Text(text = if (mode == GlassEffectMode.Material) "Material" else "Haze")
                    }
                }
            }
        }
    }
}

/**
 * Segmented selection setting item for sleep mode.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsSegmentedSleepModeItem(
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
 * Switch toggle component (To render settings item containing toggle switch)
 */
@Composable
private fun SettingsToggleItem(
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
 * Clickable settings item (To render settings item acting as trigger button)
 */
@Composable
private fun SettingsItem(
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
 * WebDAV dialog collector (To display form inputs for remote directory parameters)
 */
@Composable
private fun WebDavRootDialog(
    url: String,
    username: String,
    password: String,
    displayName: String,
    basePath: String,
    onUrlChange: (String) -> Unit,
    onUsernameChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onDisplayNameChange: (String) -> Unit,
    onBasePathChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加 WebDAV 媒体库") },
        text = {
            Column {
                OutlinedTextField(
                    value = url,
                    onValueChange = onUrlChange,
                    label = { Text("服务器 URL") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = displayName,
                    onValueChange = onDisplayNameChange,
                    label = { Text("显示名") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = basePath,
                    onValueChange = onBasePathChange,
                    label = { Text("库内路径") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = username,
                    onValueChange = onUsernameChange,
                    label = { Text("用户名") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = password,
                    onValueChange = onPasswordChange,
                    label = { Text("密码") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                enabled = url.isNotBlank()
            ) {
                Text("添加")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}

/**
 * Slider settings item (To adjust float settings values in a formatted range)
 */
@Composable
private fun SettingsSliderItem(
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
 * Section title header (To display category dividers in settings list)
 */
@Composable
private fun SettingsSectionHeader(title: String) {
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

@Preview(showBackground = true, apiLevel = 36)
@Composable
fun SettingsScreenPreview() {
    APlayerTheme {
        // Portrait phone template (To preview settings layout under vertical screens)
        // Uses CompositionLocalProvider to inject standard PortraitPhone window parameters.
        CompositionLocalProvider(
            LocalWindowClass provides WindowClass.PortraitPhone
        ) {
            SettingsScreen(
                onBack = {},
                onLibraryRootSelected = {},
                onWebDavRootSubmitted = { _, _, _, _, _ -> },
                onAbsConnectionTest = { _, _, _ -> },
                onAbsRootSubmitted = { _, _, _, _, _ -> },
                onAbsSync = {},
                onAbsBackgroundSync = {},
                absSyncConfirmationState = null,
                onDismissLargeAbsSync = {},
                onRescan = {},
                libraryRoots = emptyList(),
                absServers = emptyList(),
                absConnectionState = AbsConnectionUiState(),
                isChapterProgressMode = false,
                onChapterProgressModeChange = {},
                isCleartextTrafficAllowed = false,
                onCleartextTrafficAllowedChange = {},
                onDeleteLibraryRoot = {},
                isSkipSilenceEnabled = false,
                onSkipSilenceEnabledChange = {},
                isSleepFadeOutEnabled = true,
                onSleepFadeOutEnabledChange = {},
                isShakeToResetEnabled = true,
                onShakeToResetEnabledChange = {},
                sleepMode = SleepMode.Regular,
                onSleepModeChange = {},
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
