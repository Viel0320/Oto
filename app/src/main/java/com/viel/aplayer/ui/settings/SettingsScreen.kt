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
import androidx.compose.material3.AlertDialog
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
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
import com.viel.aplayer.ui.theme.APlayerTheme

/**
 * 纯无状态的设置主页面（Stateless）。
 *
 * 本组件承载了应用所有的设置条目绘制，已经与有状态的 SettingsActivity 实现了物理文件的彻底拆分。
 * 所有的媒体库添加、删除、开关状态更改等交互，全部通过高纯度的不可变入参和 Lambda 回调向上传递。
 * 移除了外部 ViewModel 的强引用后，本页面可以轻松支持多尺寸屏幕下的实时自适应 Preview 预览。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onLibraryRootSelected: (Uri) -> Unit,
    // WebDAV 表单提交回调只传标准连接字段，UI 不直接写数据库或凭据存储。
    onWebDavRootSubmitted: (url: String, username: String, password: String, displayName: String, basePath: String) -> Unit,
    onRescan: () -> Unit,
    libraryRoots: List<LibraryRootEntity>,
    isChapterProgressMode: Boolean,
    onChapterProgressModeChange: (Boolean) -> Unit,
    // 是否允许明文 HTTP 流量标志及对应的触发方法。
    isCleartextTrafficAllowed: Boolean,
    onCleartextTrafficAllowedChange: (Boolean) -> Unit,
    // 删除库根目录并释放物理授权的触发接口方法。
    onDeleteLibraryRoot: (LibraryRootEntity) -> Unit,
    /**
     * 自动跳过静音（Skip Silence）功能是否启用的全局状态标志及开关回调。
     */
    isSkipSilenceEnabled: Boolean,
    onSkipSilenceEnabledChange: (Boolean) -> Unit,
    // 睡眠倒计时音量渐隐功能是否启用的全局状态标志。
    isSleepFadeOutEnabled: Boolean,
    // 切换睡眠倒计时音量渐隐开关的回调事件。
    onSleepFadeOutEnabledChange: (Boolean) -> Unit,
    // 摇晃手机重置睡眠定时器功能是否启用的全局状态标志。
    isShakeToResetEnabled: Boolean,
    // 切换摇晃手机重置睡眠定时器开关的回调事件。
    onShakeToResetEnabledChange: (Boolean) -> Unit,
    // 当前睡眠模式状态。
    sleepMode: SleepMode,
    // 睡眠模式状态修改的回调事件。
    onSleepModeChange: (SleepMode) -> Unit,
    // 当前悬浮层视觉效果模式，控制 Material 原生容器与 miuix-blur 毛玻璃之间的切换。
    glassEffectMode: GlassEffectMode,
    // 切换悬浮层视觉效果模式的回调事件。
    onGlassEffectModeChange: (GlassEffectMode) -> Unit,
    // 当前自动回退时长状态。
    autoRewindSeconds: Int,
    // 修改自动回退时长的回调事件。
    onAutoRewindSecondsChange: (Int) -> Unit,
    // 当前通知避让开关的全局启用状态。
    isNotificationAvoidanceEnabled: Boolean,
    // 切换通知避让开关状态的回调事件。
    onNotificationAvoidanceEnabledChange: (Boolean) -> Unit,
    // 点击“开源许可”入口以拉起 AboutLibraries 页面的回调事件。
    onAboutLibrariesClick: () -> Unit
) {
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        uri?.let(onLibraryRootSelected)
    }

    // 定义用于记录用户即将触发删除动作的媒体库目录 State 变量，用来拉起强提醒的 AlertDialog 二次确认弹窗。
    var rootToDelete by remember { mutableStateOf<LibraryRootEntity?>(null) }
    // WebDAV 添加弹窗状态留在 SettingsScreen 内部，提交后再交给 ViewModel 执行持久化与扫描。
    var showWebDavDialog by remember { mutableStateOf(false) }
    var webDavUrl by remember { mutableStateOf("") }
    var webDavUsername by remember { mutableStateOf("") }
    var webDavPassword by remember { mutableStateOf("") }
    var webDavDisplayName by remember { mutableStateOf("") }
    var webDavBasePath by remember { mutableStateOf("") }

    // 获取设备当前的屏幕配置信息，用于自适应判定
    val configuration = LocalConfiguration.current
    // 判定当前设备是否为横屏方向
    val isLandscape = configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
    // 判定当前设备是否为大屏平板或大折叠屏（宽度 >= 600dp）
    val isWideScreen = configuration.screenWidthDp >= 600
    // 如果处于横屏或者大屏状态，则启用尊贵的容器化集中布局，使内容左右两侧各自空出 10% 的宽度（总计填充 80% 宽度，即 0.8f 比例）
    val useWideLayout = isLandscape || isWideScreen

    // 利用 WindowInsets.safeDrawing 动态获取当前横屏侧边物理刘海及导航栏宽度，完全零硬编码
    val safeDrawingPadding = WindowInsets.safeDrawing.asPaddingValues()
    val layoutDirection = androidx.compose.ui.platform.LocalLayoutDirection.current
    val settingsStartPadding = safeDrawingPadding.calculateStartPadding(layoutDirection)
    val settingsEndPadding = safeDrawingPadding.calculateEndPadding(layoutDirection)

    if (showWebDavDialog) {
        // WebDAV 弹窗只采集连接信息，真实鉴权在 Provider 首次可用性检测和扫描时完成。
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
                        // 恢复普通修饰符，不在容器外侧加 Padding，使 AppBar 背景底色优雅拉满至屏幕左右边缘
                        modifier = Modifier,
                        // 将 WindowInsets.statusBars.exclude(navigationBars) 作为顶栏的 safe insets，
                        // 自适应处理状态栏和横屏左右避让，彻底摆脱返回按钮上的手写 padding
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
                    // 注入运行时算出的 start/end 物理避让 padding，保障设置项文字与开关在任何物理刘海/侧边导航栏前完全安全
                    contentPadding = PaddingValues(
                        start = settingsStartPadding,
                        end = settingsEndPadding
                    )
                ) {
                    // === 第一分节：媒体库管理 ===
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
                        // 新增 WebDAV 来源入口，与本地 SAF 入口并列，后续 SMB/S3 可沿用相同设置项模式扩展。
                        SettingsItem(
                            title = "添加 WebDAV 媒体库",
                            subtitle = "连接远程 WebDAV 目录",
                            icon = Icons.Rounded.Cloud,
                            onClick = { showWebDavDialog = true }
                        )
                    }

                    // 添加模式 key，使用通用 sourceUri 作为唯一标识，避免 UI 继续依赖旧库根字段。
                    // 防止列表刷新时 item 状态错位复用导致 UI 错乱。
                    items(libraryRoots.size, key = { libraryRoots[it].sourceUri }) { index ->
                        val root = libraryRoots[index]
                        val isWebDavRoot = root.sourceType == AudiobookSchema.LibrarySourceType.WEBDAV
                        val rootTitle = if (isWebDavRoot) {
                            root.displayName.ifBlank { "${root.sourceUri}${root.basePath}" }
                        } else {
                            // 本地 SAF root 继续显示用户可理解的末级目录名，不暴露完整 tree URI。
                            try {
                                Uri.decode(root.sourceUri).substringAfterLast(":")
                            } catch (_: Exception) {
                                root.sourceUri
                            }
                        }
                        val rootSubtitle = if (isWebDavRoot) {
                            // WebDAV root 显示远程端点与可用性状态，避免用户把网络失败误认为 SAF 授权撤销。
                            "WebDAV: ${root.sourceUri}${root.basePath} · 可用性: ${root.availabilityStatus}"
                        } else {
                            "状态: ${root.status}"
                        }
                        
                        // 重构媒体库卡片单行，右侧渲染删除图标。
                        // 用户点击垃圾桶删除图标后，将当前 root 赋给 rootToDelete 从而异步弹窗让用户进行二次安全确认。
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = if (isWebDavRoot) Icons.Rounded.Cloud else Icons.Rounded.FolderOpen,
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

                    // === 第二分节：界面效果 ===
                    item {
                        SettingsSectionHeader(title = "界面效果")
                    }
                    item {
                        // 新增 Material/miuix-blur 双态分段选择，让用户可在原生 Material 层次与 miuix-blur 毛玻璃之间即时切换。
                        SettingsSegmentedItem(
                            title = "悬浮层玻璃效果",
                            subtitle = "控制章节列表和书籍操作弹窗的背景效果",
                            icon = Icons.Rounded.LinearScale,
                            selectedMode = glassEffectMode,
                            onModeSelected = onGlassEffectModeChange
                        )
                    }

                    // === 第三分节：播放与网络 ===
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
                        // 新增“允许明文 HTTP 流量”持久化控制开关。
                        // 默认关闭，开启时客户端代码侧允许流式播放 http 音频文件。
                        SettingsToggleItem(
                            title = "允许明文 HTTP 流量",
                            subtitle = "允许应用播放和加载不安全的 http:// 网络有声书源。建议保持关闭以维持最高安全边界。",
                            icon = Icons.Rounded.LinearScale,
                            checked = isCleartextTrafficAllowed,
                            onCheckedChange = onCleartextTrafficAllowedChange
                        )
                    }
                    item {
                        // 新增“通知避让”全局控制开关项。
                        SettingsToggleItem(
                            title = "通知避让",
                            subtitle = "开启后，播留在失去焦点（如收到通知、导航播报、来电等）时暂停，重获焦点时恢复，避免降音避让时漏听内容。",
                            icon = Icons.Rounded.LinearScale,
                            checked = isNotificationAvoidanceEnabled,
                            onCheckedChange = onNotificationAvoidanceEnabledChange
                        )
                    }

                    // === 第四分节：自动跳过静音 ===
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

                    // === 第五分节：睡眠定时器 ===
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

                    // === 第六分节：自动回放 ===
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
                            steps = 29, // 0s到30s共有31个刻度点，除去两端，所以 steps = 31 - 2 = 29
                            valueFormatter = {
                                if (it.toInt() == 0) "已关闭" else String.format(java.util.Locale.US, "%d 秒", it.toInt())
                            },
                            enabled = true
                        )
                    }

                    // === 第八分节：关于信息 ===
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

    // 渲染用户确定移除库根目录时的警示 AlertDialog 弹窗。
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

/**
 * 悬浮层玻璃效果分段选择设置项。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsSegmentedItem(
    title: String,
    subtitle: String,
    icon: ImageVector,
    selectedMode: GlassEffectMode,
    onModeSelected: (GlassEffectMode) -> Unit
) {
    val modes = listOf(GlassEffectMode.Material, GlassEffectMode.MiuixBlur)
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
                        Text(text = if (mode == GlassEffectMode.Material) "Material" else "MiuixBlur")
                    }
                }
            }
        }
    }
}

/**
 * 睡眠模式分段选择设置项。
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
 * Switch 切换状态组件。
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
 * 普通点击设置条目。
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
 * WebDAV 添加弹窗。
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
 * 新增 Slider 专用的设置项辅助组件。
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
 * 设置页面的小标题分节头部组件。
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
        SettingsScreen(
            onBack = {},
            onLibraryRootSelected = {},
            onWebDavRootSubmitted = { _, _, _, _, _ -> },
            onRescan = {},
            libraryRoots = emptyList(),
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
