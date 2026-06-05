package com.viel.aplayer.ui.settings

// Import alignment: Add IME insets mapping extension to perform exclude filter during keyboard state changes.
// Import alignment: Add coroutines launch import for async credential loading
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
import androidx.compose.foundation.layout.ime
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import com.viel.aplayer.abs.auth.AbsCredential
import com.viel.aplayer.data.db.AudiobookSchema
import com.viel.aplayer.data.entity.LibraryRootEntity
import com.viel.aplayer.data.store.AppSettings
import com.viel.aplayer.data.store.GlassEffectMode
import com.viel.aplayer.data.store.SleepMode
import com.viel.aplayer.library.vfs.sourceProvider.webdav.WebDavCredential
import com.viel.aplayer.ui.common.theme.APlayerTheme
import com.viel.aplayer.ui.common.theme.LocalWindowClass
import com.viel.aplayer.ui.common.theme.WindowClass
import kotlinx.coroutines.launch

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
    onSafRootRelocated: (String, Uri) -> Unit,
    // WebDAV submission callback (To delegate credential routing to state container)
    // Passes connection attributes up instead of performing DB transactions locally.
    onWebDavRootSubmitted: (url: String, username: String, password: String, displayName: String, basePath: String) -> Unit,
    onWebDavRootUpdated: (id: String, url: String, username: String, password: String, displayName: String, basePath: String) -> Unit,
    // WebDAV connection interface: Add callback endpoints to support verification before persisting credentials.
    webDavConnectionState: WebDavConnectionUiState,
    onWebDavConnectionTest: (url: String, username: String, password: String, basePath: String, editingRootId: String?) -> Unit,
    onResetWebDavConnectionState: () -> Unit,
    // ABS connection status callback: Link status resetting hooks to track input changes.
    onResetAbsConnectionState: () -> Unit,
    // ABS connection testing enhancement: Support forwarding editingRootId to prevent validation errors when password is empty.
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
    var absDisplayName by remember { mutableStateOf("") }

    var showAddLibraryTypeDialog by remember { mutableStateOf(false) }
    var rootForAction by remember { mutableStateOf<LibraryRootEntity?>(null) }
    var editingRootId by remember { mutableStateOf<String?>(null) }

    // Load window parameters (To adapt layout proportions for wide displays)
    // Avoids reading LocalConfiguration attributes directly by subscribing to WindowClass values.
    val windowClass = LocalWindowClass.current
    val isLandscape = windowClass.isLandscape
    val isWideScreen = windowClass.isTablet
    val useWideLayout = windowClass.isWideScreen

    // Safe drawing insets (To avoid hardcoded display margin constants on notched displays)
    // Reads WindowInsets.safeDrawing boundaries to offset items.
    // Window insets performance tuning: Exclude IME insets from safe drawing values to prevent redundant background recomposition when keyboard opens.
    val safeDrawingPadding = WindowInsets.safeDrawing.exclude(WindowInsets.ime).asPaddingValues()
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
            editingRootId = editingRootId,
            connectionState = webDavConnectionState,
            // WebDAV connection tester: Reset connection verification status immediately if any core connection details change.
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
            // WebDAV connection test integration: Link UI interactions to test callbacks and reset states.
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
                showWebDavDialog = false
                // WebDAV dialog reset: Clear WebDAV fields on confirm or dismiss.
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
                showWebDavDialog = false
                // WebDAV dialog reset: Clear WebDAV fields on confirm or dismiss.
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
        // ABS Edit Auto-Test (Automatically validates an existing server as soon as the edit dialog opens)
        // Uses the already-loaded root ID and stored token path so the dialog can fetch book library options without forcing the user to press the test button first.
        LaunchedEffect(editingRootId) {
            if (editingRootId != null) {
                onAbsConnectionTest(absBaseUrl.trim(), absUsername.trim(), absPassword, editingRootId)
            }
        }

        AbsServerDialog(
            baseUrl = absBaseUrl,
            username = absUsername,
            password = absPassword,
            displayName = absDisplayName,
            editingRootId = editingRootId,
            connectionState = absConnectionState,
            selectedLibraryId = absLibraryId,
            selectedLibraryName = absLibraryName,
            // ABS connection tester: Reset testing states when dialog closes or fields modify.
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
            // ABS testing integration: Forward current editingRootId in test connections inside dialog.
            onTestConnection = { onAbsConnectionTest(absBaseUrl.trim(), absUsername.trim(), absPassword, editingRootId) },
            onDismiss = {
                showAbsDialog = false
                // ABS dialog reset: Clear ABS fields on confirm or dismiss.
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
                showAbsDialog = false
                // ABS dialog reset: Clear ABS fields on confirm or dismiss.
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
                topBar = {
                    CenterAlignedTopAppBar(
                        // Adjust visual boundaries (To render app bar background color fully to margins)
                        // Bypasses container spacing padding configurations.
                        modifier = Modifier,
                        // Calculate top bar margins (To exclude system navigation bars on rotated layouts)
                        // Uses exclude calculations rather than manual padding values.
                        // Window insets performance tuning: Exclude both navigationBars and IME insets from topBar safe drawing values.
                        windowInsets = WindowInsets.safeDrawing.exclude(WindowInsets.navigationBars).exclude(WindowInsets.ime),
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
                            title = "添加媒体库",
                            subtitle = "支持本地 (SAF)、WebDAV、Audiobookshelf 服务器",
                            icon = Icons.Rounded.FolderOpen,
                            onClick = { showAddLibraryTypeDialog = true }
                        )
                    }

                    // Library Display Keying (Uses stable root IDs for all registered source rows)
                    // This keeps multiple ABS libraries from the same server distinct even when they share the same source URL.
                    items(libraryRootDisplays.size, key = { libraryRootDisplays[it].root.id }) { index ->
                        val display = libraryRootDisplays[index]
                        val root = display.root
                        val isWebDavRoot = root.sourceType == AudiobookSchema.LibrarySourceType.WEBDAV
                        val isAbsRoot = root.sourceType == AudiobookSchema.LibrarySourceType.ABS
                        val locationLine = display.selectedLibraryText
                            ?.takeIf { it.isNotBlank() }
                            ?.let { libraryName -> "位置：${display.locationText} · 当前书库：$libraryName" }
                            ?: "位置：${display.locationText}"
                        
                        // Render directory layout card (To combine detail metadata labels with click actions)
                        // Captures user clicks on row item to open action popup.
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { rootForAction = root }
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
                                    text = "最近同步：${display.lastSyncText} · 已导入：${display.importedBookCount} 本",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                if (isAbsRoot && display.lastError?.isNotBlank() == true) {
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = "错误：${display.lastError}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.error
                                    )
                                }
                            }
                        }
                    }

                    // === Section 2: Interface Settings ===
                    item {
                        SettingsSectionHeader(title = "界面效果")
                    }
                    item {
                        // Visual effect settings: Change layout controller from segment choice to switch button for experimental blur effect.
                        SettingsToggleItem(
                            title = "实验性模糊效果",
                            subtitle = "控制章节列表和书籍操作弹窗的背景效果",
                            icon = Icons.Rounded.LinearScale,
                            checked = glassEffectMode == GlassEffectMode.Haze,
                            onCheckedChange = { isChecked ->
                                val newMode = if (isChecked) GlassEffectMode.Haze else GlassEffectMode.Material
                                onGlassEffectModeChange(newMode)
                            }
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

    if (showAddLibraryTypeDialog) {
        AlertDialog(
            onDismissRequest = { showAddLibraryTypeDialog = false },
            title = { Text("选择媒体库类别") },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                showAddLibraryTypeDialog = false
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
                                showAddLibraryTypeDialog = false
                                webDavUrl = ""
                                webDavDisplayName = ""
                                webDavBasePath = ""
                                webDavUsername = ""
                                webDavPassword = ""
                                editingRootId = null
                                showWebDavDialog = true
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
                                showAddLibraryTypeDialog = false
                                absBaseUrl = ""
                                absUsername = ""
                                absPassword = ""
                                absLibraryId = ""
                                absLibraryName = ""
                                absDisplayName = ""
                                editingRootId = null
                                showAbsDialog = true
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
                TextButton(onClick = { showAddLibraryTypeDialog = false }) {
                    Text("取消")
                }
            }
        )
    }

    if (rootForAction != null) {
        val root = rootForAction!!
        val isAbsRoot = root.sourceType == AudiobookSchema.LibrarySourceType.ABS
        val isWebDavRoot = root.sourceType == AudiobookSchema.LibrarySourceType.WEBDAV
        val scope = rememberCoroutineScope()
        AlertDialog(
            onDismissRequest = { rootForAction = null },
            title = { Text(root.displayName.ifBlank { "媒体库操作" }) },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                if (root.sourceType == AudiobookSchema.LibrarySourceType.SAF) {
                                    editingSafRootId = root.id
                                    launcher.launch(null)
                                } else if (isWebDavRoot) {
                                    val creds = getWebDavCredentials(root.credentialId ?: "")
                                    webDavUrl = root.sourceUri
                                    webDavDisplayName = root.displayName
                                    webDavBasePath = root.basePath
                                    webDavUsername = creds?.username ?: ""
                                    webDavPassword = creds?.password ?: ""
                                    editingRootId = root.id
                                    showWebDavDialog = true
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
                                        showAbsDialog = true
                                    }
                                }
                                rootForAction = null
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
                                rootForAction = null
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
                                rootToDelete = root
                                rootForAction = null
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
                TextButton(onClick = { rootForAction = null }) {
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
    displayName: String,
    editingRootId: String?,
    connectionState: AbsConnectionUiState,
    selectedLibraryId: String,
    selectedLibraryName: String,
    onBaseUrlChange: (String) -> Unit,
    onUsernameChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onDisplayNameChange: (String) -> Unit,
    onLibrarySelected: (String, String) -> Unit,
    onTestConnection: () -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        // ABS dialog properties: Force clicking explicit confirm/cancel buttons to dismiss instead of clicking outside.
        properties = androidx.compose.ui.window.DialogProperties(dismissOnClickOutside = false),
        // ABS dialog adjustments: Update title dynamically depending on whether it is in editing mode.
        title = { Text(if (editingRootId != null) "编辑 ABS Server" else "添加 ABS Server") },
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
                    label = { Text(if (editingRootId != null) "密码（留空则不修改）" else "密码") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = onTestConnection,
                    // ABS dialog adjustments: Allow testing without re-entering password if we are editing an existing server.
                    enabled = baseUrl.isNotBlank() && username.isNotBlank() && (password.isNotBlank() || editingRootId != null)
                ) {
                    Text(if (connectionState.isTesting) "连接中..." else "测试连接")
                }
                if (connectionState.loginSucceeded) {
                     Spacer(modifier = Modifier.height(8.dp))
                     Text(
                        text = "登录成功，请选择一个 book library 再点击确认",
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
                // ABS dialog adjustments: Require password only when creating a new server configuration.
                enabled = baseUrl.isNotBlank() && username.isNotBlank() && (password.isNotBlank() || editingRootId != null) &&
                    selectedLibraryId.isNotBlank() && selectedLibraryName.isNotBlank()
            ) {
                Text(if (editingRootId != null) "保存" else "添加")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}

// Refactor: Remove unused SettingsSegmentedItem component after changing layout controller to Toggle switch.

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
    editingRootId: String? = null,
    connectionState: WebDavConnectionUiState,
    onUrlChange: (String) -> Unit,
    onUsernameChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onDisplayNameChange: (String) -> Unit,
    onBasePathChange: (String) -> Unit,
    onTestConnection: () -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        // WebDAV dialog properties: Force clicking explicit confirm/cancel buttons to dismiss instead of clicking outside.
        properties = androidx.compose.ui.window.DialogProperties(dismissOnClickOutside = false),
        // WebDAV dialog adjustments: Support dynamically displaying editing titles and placeholder fields.
        title = { Text(if (editingRootId != null) "编辑 WebDAV 媒体库" else "添加 WebDAV 媒体库") },
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
                    label = { Text(if (editingRootId != null) "密码（留空则不修改）" else "密码") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                // WebDAV connection tester UI: Add a test connection button and restrict confirmation availability until the test completes successfully.
                Button(
                    onClick = onTestConnection,
                    enabled = url.isNotBlank() && !connectionState.isTesting
                ) {
                    Text(if (connectionState.isTesting) "测试连接中..." else "测试连接")
                }
                if (connectionState.testSucceeded) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "连接测试成功，可以保存媒体库",
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
            }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                // WebDAV dialog adjustments: Require connectivity test to pass before adding or updating.
                enabled = url.isNotBlank() && connectionState.testSucceeded
            ) {
                Text(if (editingRootId != null) "保存" else "添加")
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
                // Preview layout alignment: Align preview invocation parameters with new WebDAV connectivity test settings.
                onSafRootRelocated = { _, _ -> },
                onWebDavRootSubmitted = { _, _, _, _, _ -> },
                onWebDavRootUpdated = { _, _, _, _, _, _ -> },
                webDavConnectionState = WebDavConnectionUiState(),
                onWebDavConnectionTest = { _, _, _, _, _ -> },
                onResetWebDavConnectionState = {},
                // Preview layout alignment: Inject resetting callbacks to fit SettingsScreen parameter layout.
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
