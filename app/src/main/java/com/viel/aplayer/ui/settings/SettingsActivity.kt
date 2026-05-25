package com.viel.aplayer.ui.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.alpha
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
// 导入运行时系统安全区 Insets 与 PaddingValues，以支持设置页面横竖屏下的自适应刘海与系统栏避让
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.exclude
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.platform.LocalConfiguration
import android.content.res.Configuration
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.FolderOpen
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
import androidx.compose.material3.Slider
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.viel.aplayer.R
import com.viel.aplayer.data.db.AudiobookSchema
import com.viel.aplayer.data.entity.LibraryRootEntity
import com.viel.aplayer.data.store.AppSettings
import com.viel.aplayer.data.store.GlassEffectMode
import com.viel.aplayer.data.store.SleepMode
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.compose.ui.platform.LocalContext
import com.viel.aplayer.ui.theme.APlayerTheme
// 引入物理返回按键拦截 BackHandler，当处于开源协议展示页时，按下返回键先返回设置主页，而非直接退出本 Activity。
import androidx.activity.compose.BackHandler
// 引入全新的 AnimatedContent 进退场动画容器及 slide/fade 系列平滑滑入过渡，实现类原生导航栈切换的丝滑视觉享受。
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.togetherWith
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
// 引入 Info 信息图标，作为底部开源许可入口的精美前置指示图标。
import androidx.compose.material.icons.rounded.Info

/**
 * 设置功能的独立 Activity。
 *
 * SettingsViewModel 和 LibraryViewModel 均继承自 AndroidViewModel，
 * 生命周期依附于本 Activity，通过 APlayerApplication.container 访问同一套 Repository 单例，
 * 因此数据持久化层与 MainActivity 完全共享，无任何状态隔离问题。
 *
 * 进入/退出动画由调用方 + 本 Activity finish() 时分别通过系统动画 API 驱动。
 */
class SettingsActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 启用全面屏，保持与 MainActivity 一致的边到边体验
        enableEdgeToEdge()

        setContent {
            APlayerTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    // 
                    // SettingsViewModel 是 AndroidViewModel，直接通过 viewModel() 在本 Activity 中实例化，
                    // 其 Repository 实例与 MainActivity 侧共用同一个 LibraryRepository 和 SettingsRepository 单例。
                    val settingsViewModel: SettingsViewModel = viewModel()
                    val settingsState by settingsViewModel.settingsState.collectAsStateWithLifecycle()
                    val libraryRoots by settingsViewModel.libraryRoots.collectAsStateWithLifecycle()

                    // 
                    // 维护一个 Boolean 状态，用于驱动设置主页 (SettingsScreen) 和开源许可面板 (AboutLibrariesScreen) 之间的显示和切换。
                    var showAboutLibraries by remember { mutableStateOf(false) }

                    // 
                    // 运用 BackHandler 物理拦截手势：当处于开源许可面板时，物理返回按键或系统侧滑返回仅会重置 showAboutLibraries = false 返回设置主页，从而避免直接关闭整个设置页面。
                    BackHandler(enabled = showAboutLibraries) {
                        showAboutLibraries = false
                    }

                    // 
                    // 采用 AnimatedContent 高阶转场，当用户点击开源许可时，AboutLibrariesScreen 将优雅地从右滑入，SettingsScreen 从左滑出；点击返回时相反，给用户极致轻盈的操作反馈。
                    AnimatedContent(
                        targetState = showAboutLibraries,
                        transitionSpec = {
                            if (targetState) {
                                (slideInHorizontally { width -> width } + fadeIn())
                                    .togetherWith(slideOutHorizontally { width -> -width } + fadeOut())
                            } else {
                                (slideInHorizontally { width -> -width } + fadeIn())
                                    .togetherWith(slideOutHorizontally { width -> width } + fadeOut())
                            }
                        },
                        label = "AboutLibrariesTransition"
                    ) { showAbout ->
                        if (showAbout) {
                            AboutLibrariesScreen(
                                onBack = { showAboutLibraries = false }
                            )
                        } else {
                            SettingsScreen(
                                // 返回按钮关闭当前 Activity，系统自动播放退出动画。
                                onBack = { finish() },
                                // 
                                // 直接通过 SettingsViewModel 添加媒体库根目录，与其共享同一套 SAF 授权流程。
                                // 彻底剥离对 LibraryViewModel 的依赖，防止进入设置页时重新创建该 ViewModel 而导致重复触发其 init 块中的 COLD_START 冷启动同步。
                                onLibraryRootSelected = { uri -> settingsViewModel.onLibraryRootSelected(uri) },
                                // WebDAV 入口同样委托给 SettingsViewModel，保持设置页独立管理媒体库来源。
                                onWebDavRootSubmitted = { url, username, password, displayName, basePath ->
                                    settingsViewModel.onWebDavRootSubmitted(url, username, password, displayName, basePath)
                                },

                                onClearHistory = { settingsViewModel.clearSearchHistory() },
                                onRescan = { settingsViewModel.triggerRescan() },
                                libraryRoots = libraryRoots,
                                isChapterProgressMode = settingsState.isChapterProgressMode,
                                onChapterProgressModeChange = { settingsViewModel.toggleChapterProgressMode(it) },
                                isCleartextTrafficAllowed = settingsState.isCleartextTrafficAllowed,
                                onCleartextTrafficAllowedChange = { settingsViewModel.toggleCleartextTrafficAllowed(it) },
                                onDeleteLibraryRoot = { settingsViewModel.deleteLibraryRoot(it) },
                                isSkipSilenceEnabled = settingsState.isSkipSilenceEnabled,
                                onSkipSilenceEnabledChange = { settingsViewModel.toggleSkipSilenceEnabled(it) },
                                skipSilenceDurationThreshold = settingsState.skipSilenceDurationThreshold,
                                onSkipSilenceDurationThresholdChange = { settingsViewModel.updateSkipSilenceDurationThreshold(it) },
                                isSkipSilenceNotificationEnabled = settingsState.isSkipSilenceNotificationEnabled,
                                onSkipSilenceNotificationEnabledChange = { settingsViewModel.toggleSkipSilenceNotificationEnabled(it) },
                                isSleepFadeOutEnabled = settingsState.isSleepFadeOutEnabled,
                                onSleepFadeOutEnabledChange = { settingsViewModel.toggleSleepFadeOutEnabled(it) },
                                isShakeToResetEnabled = settingsState.isShakeToResetEnabled,
                                onShakeToResetEnabledChange = { settingsViewModel.toggleShakeToResetEnabled(it) },
                                // 将 DataStore 中的睡眠模式传入设置页，并把用户选择回写到 SettingsViewModel。
                                sleepMode = settingsState.sleepMode,
                                onSleepModeChange = { settingsViewModel.updateSleepMode(it) },
                                // 将 DataStore 中的玻璃效果模式传入设置页，并把用户选择回写到 SettingsViewModel。
                                glassEffectMode = settingsState.glassEffectMode,
                                onGlassEffectModeChange = { settingsViewModel.updateGlassEffectMode(it) },
                                // 将 DataStore 中的自动回退时长传入设置页，并把用户选择回写到 SettingsViewModel。
                                autoRewindSeconds = settingsState.autoRewindSeconds,
                                onAutoRewindSecondsChange = { settingsViewModel.updateAutoRewindSeconds(it) },
                                // 将 DataStore 中的通知避让状态传入设置页，并把用户开关选择回写到 SettingsViewModel 进行持久化。
                                isNotificationAvoidanceEnabled = settingsState.isNotificationAvoidanceEnabled,
                                onNotificationAvoidanceEnabledChange = { settingsViewModel.toggleNotificationAvoidanceEnabled(it) },
                                // 将“开源许可页面”点击项的触发回调透传，用户点击时修改 showAboutLibraries = true 以触发梦幻滑入转场。
                                onAboutLibrariesClick = { showAboutLibraries = true }
                            )
                        }
                    }
                }
            }
        }
    }

    companion object {
        /**
                 * 构建用于启动 SettingsActivity 的 Intent 的工厂方法。
         */
        fun createIntent(context: Context): Intent =
            Intent(context, SettingsActivity::class.java)
    }
}

/**
 * 设置主页 Composable，负责渲染全部配置项条目。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onLibraryRootSelected: (Uri) -> Unit,
    // WebDAV 表单提交回调只传标准连接字段，UI 不直接写数据库或凭据存储。
    onWebDavRootSubmitted: (url: String, username: String, password: String, displayName: String, basePath: String) -> Unit,
    onClearHistory: () -> Unit,
    onRescan: () -> Unit,
    libraryRoots: List<LibraryRootEntity>,
    isChapterProgressMode: Boolean,
    onChapterProgressModeChange: (Boolean) -> Unit,
    // 是否允许明文 HTTP 流量标志及对应的触发方法。
    isCleartextTrafficAllowed: Boolean,
    onCleartextTrafficAllowedChange: (Boolean) -> Unit,
    // 删除库根目录并释放物理授权的触发接口方法。
    onDeleteLibraryRoot: (LibraryRootEntity) -> Unit,
    // 自动跳过静音（Skip Silence）功能是否启用的全局状态标志。
    isSkipSilenceEnabled: Boolean,
    // 切换自动跳过静音全局开关状态的回调事件。
    onSkipSilenceEnabledChange: (Boolean) -> Unit,
    // 静音判定最小时长值（秒，默认2.0f）的状态。
    skipSilenceDurationThreshold: Float,
    // 修改静音判定最小时长值的回调事件。
    onSkipSilenceDurationThresholdChange: (Float) -> Unit,
    // 静音跳过时是否弹出 Toast 提示的全局状态标志。
    isSkipSilenceNotificationEnabled: Boolean,
    // 切换静音跳过 Toast 提示开关的回调事件。
    onSkipSilenceNotificationEnabledChange: (Boolean) -> Unit,
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

    val context = LocalContext.current

    // 获取设备当前的屏幕配置信息，用于自适应判定
    val configuration = LocalConfiguration.current
    // 判定当前设备是否为横屏方向
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    // 判定当前设备是否为大屏平板或大折叠屏（宽度 >= 600dp）
    val isWideScreen = configuration.screenWidthDp >= 600
    // 如果处于横屏或者大屏状态，则启用尊贵的容器化集中布局，使内容左右两侧各自空出 20% 的宽度（总计填充 60% 宽度，即 0.6f 比例）
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
                                // 由顶栏自带的 windowInsets 完美托管侧向刘海物理避让，故在此安全移除手动 padding
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
            // 
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

            // 
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

            // 
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
                // 开启后，播留在失去音频焦点（如收到通知、导航播报、来电等）时暂停，并在重获焦点时自动恢复播放，避免默认降音避让时漏听内容，且不会触发自动回退。
                SettingsToggleItem(
                    title = "通知避让",
                    subtitle = "开启后，播留在失去焦点（如收到通知、导航播报、来电等）时暂停，重获焦点时恢复，避免降音避让时漏听内容。",
                    icon = Icons.Rounded.LinearScale,
                    checked = isNotificationAvoidanceEnabled,
                    onCheckedChange = onNotificationAvoidanceEnabledChange
                )
            }

            // 
            // === 第四分节：自动跳过静音 ===
            item {
                SettingsSectionHeader(title = "自动跳过静音")
            }
            item {
                // 新增“自动跳过静音期”全局控制开关项。
                SettingsToggleItem(
                    title = "自动跳过静音期",
                    subtitle = "在播放有声书时，自动跳过主播停顿、换气及章节末尾等无声片段以提高收听效率。",
                    icon = Icons.Rounded.LinearScale,
                    checked = isSkipSilenceEnabled,
                    onCheckedChange = onSkipSilenceEnabledChange
                )
            }
            item {
                // 判定最小时长滑块调节项。子选项常态渲染，但在全局静音跳过禁用时置灰。
                SettingsSliderItem(
                    title = "静音判定最小时长",
                    subtitle = "判定静音的持续时间",
                    icon = Icons.Rounded.LinearScale,
                    value = skipSilenceDurationThreshold,
                    onValueChange = onSkipSilenceDurationThresholdChange,
                    valueRange = 0.5f..5.0f,
                    steps = 8,
                    valueFormatter = { String.format(java.util.Locale.US, "%.1f 秒", it) },
                    enabled = isSkipSilenceEnabled
                )
            }
            item {
                // 跳过静音时弹出 Toast 温馨提醒开关项。子选项常态渲染，但在全局静音跳过禁用时置灰。
                SettingsToggleItem(
                    title = "跳过静音时弹出通知",
                    subtitle = "当应用跳过静音时，以 Toast 形式在底部进行温馨提示。",
                    icon = Icons.Rounded.LinearScale,
                    checked = isSkipSilenceNotificationEnabled,
                    onCheckedChange = onSkipSilenceNotificationEnabledChange,
                    enabled = isSkipSilenceEnabled
                )
            }

            // 
            // === 第五分节：睡眠定时器 ===
            item {
                SettingsSectionHeader(title = "睡眠定时器")
            }
            item {
                // 新增“睡眠模式三态选择”组件，允许用户在常规倒计时、基于动作静止检测的运动跟踪及睡眠跟踪之间自主切换。
                SettingsSegmentedSleepModeItem(
                    title = "睡眠模式",
                    subtitle = "选择计时触发机制（常规: 设定时间即计时；运动跟踪: 静止才计时，运动则暂停；睡眠跟踪: 熟睡才计时）",
                    icon = Icons.Rounded.LinearScale,
                    selectedMode = sleepMode,
                    onModeSelected = { selectedMode ->
                        // 重构优化 — 睡眠跟踪模式纯基于加速度传感器（免除高隐私级别的活动识别权限 ACTIVITY_RECOGNITION），直接应用新模式状态以实现极简的用户体验。
                        onSleepModeChange(selectedMode)
                    }
                )
            }
            item {
                // 新增“睡眠倒计时音量渐隐”全局控制开关项。
                SettingsToggleItem(
                    title = "睡眠倒计时音量渐隐",
                    subtitle = "当倒计时走到最后 10 秒（或章节快结束前 10 秒）时，音量将柔和对数式递减到静音，避免突然的无声惊醒您。",
                    icon = Icons.Rounded.LinearScale,
                    checked = isSleepFadeOutEnabled,
                    onCheckedChange = onSleepFadeOutEnabledChange
                )
            }
            item {
                // 新增“摇晃手机重置睡眠定时器”全局控制开关项。
                SettingsToggleItem(
                    title = "摇晃手机重置睡眠定时器",
                    subtitle = "当进入睡眠定时器最后 10 秒音量渐隐阶段时，轻轻摇晃手机即可触发轻微震动反馈并重置定时器。若为“章节结束停止模式”，摇晃重置时若有下一章将自动顺延为 15 分钟常规倒计时并顺延至下一个章节继续播放，免去夜间亮屏解锁的繁琐。",
                    icon = Icons.Rounded.LinearScale,
                    checked = isShakeToResetEnabled,
                    onCheckedChange = onShakeToResetEnabledChange
                )
            }

            // 
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
                    steps = 29, // 0s到30s共有31个刻度点，除去两端，所以 steps = 31 - 2 = 29，能够极其精准完美对齐整数秒
                    valueFormatter = {
                        if (it.toInt() == 0) "已关闭" else String.format(java.util.Locale.US, "%d 秒", it.toInt())
                    },
                    enabled = true
                )
            }

            // 
            // === 第七分节：数据清理 ===
            item {
                SettingsSectionHeader(title = "数据清理")
            }
            item {
                SettingsItem(
                    title = stringResource(R.string.clear_history_title),
                    subtitle = stringResource(R.string.clear_history_subtitle),
                    icon = Icons.Rounded.DeleteSweep,
                    onClick = onClearHistory
                )
            }

            // 
            // === 第八分节：关于信息 ===
            // 在设置页底栏新增“关于”分节与“开源许可”条目，完美适配 safe drawing padding 安全边距避让。
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
    // 该弹窗极其直白明晰地向用户警示操作影响（相关书籍被移出库、物理文件不被删除、物理授权物理释放），确认后执行 onDeleteLibraryRoot 并优雅释放 SAF 句柄。
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
 * 使用 Material 3 SingleChoiceSegmentedButtonRow 呈现互斥选项，避免用单个 Switch 表达两种命名模式造成语义歧义。
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
    // 将选项列表中的 miuix 改为全新更名且没有旧模糊机制影子残留的 MiuixBlur
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
            // 在说明文字与分段按钮之间保留 8.dp 呼吸间距，避免控件显得拥挤。
            Spacer(modifier = Modifier.height(8.dp))
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                modes.forEachIndexed { index, mode ->
                    SegmentedButton(
                        selected = selectedMode == mode,
                        onClick = { onModeSelected(mode) },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = modes.size)
                    ) {
                        // 按钮显示文案，Material 表示原生实色层，MiuixBlur 对应显示为更为尊贵的 "miuix-blur" 模糊磨砂效果。
                        Text(text = if (mode == GlassEffectMode.Material) "Material" else "MiuixBlur")
                    }
                }
            }
        }
    }
}

/**
 * 睡眠模式分段选择设置项。
 * 运用 SingleChoiceSegmentedButtonRow 支持常规、运动跟踪、睡眠跟踪三种模式互斥切换，设计高保真且科技感十足。
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
            // 修正睡眠模式的动态提示说明。将睡眠检测的说明更新为 10 分钟接近静止且包含微小动作容错，与底层物理算法设计完美对齐。
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
 * Switch 切换状态组件。新增了 enabled 选项控制，
 * 当处于禁用状态（enabled = false）时，外层 Row 不可点击，并通过 alpha(0.38f) 将整行内容置灰。
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

// WebDAV 添加弹窗集中维护远程连接表单，后续 SMB/S3 可按同一形态扩展独立表单。
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
                // URL 字段允许填写 http/https 端点；路径会在入库时规范化到 basePath。
                OutlinedTextField(
                    value = url,
                    onValueChange = onUrlChange,
                    label = { Text("服务器 URL") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                // 显示名仅影响本地 UI 展示，不参与 WebDAV 鉴权或路径解析。
                OutlinedTextField(
                    value = displayName,
                    onValueChange = onDisplayNameChange,
                    label = { Text("显示名") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                // basePath 独立于服务器端点保存，支持同一 WebDAV 主机挂多个书库目录。
                OutlinedTextField(
                    value = basePath,
                    onValueChange = onBasePathChange,
                    label = { Text("库内路径") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                // 用户名只提交到凭据仓库，Room 的 library_roots 表不会保存明文账号。
                OutlinedTextField(
                    value = username,
                    onValueChange = onUsernameChange,
                    label = { Text("用户名") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                // 密码输入框使用 PasswordVisualTransformation，避免设置页明文回显凭据。
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
 * 设置界面预览。
 */
@Preview(showBackground = true, apiLevel = 36)
@Composable
fun SettingsScreenPreview() {
    APlayerTheme {
        SettingsScreen(
            onBack = {},
            onLibraryRootSelected = {},
            // Preview 不执行 WebDAV 持久化，只验证设置页参数链路完整。
            onWebDavRootSubmitted = { _, _, _, _, _ -> },
            onClearHistory = {},
            onRescan = {},
            libraryRoots = emptyList(),
            isChapterProgressMode = false,
            onChapterProgressModeChange = {},
            isCleartextTrafficAllowed = false,
            onCleartextTrafficAllowedChange = {},
            onDeleteLibraryRoot = {},
            isSkipSilenceEnabled = false,
            onSkipSilenceEnabledChange = {},
            skipSilenceDurationThreshold = 2.0f,
            onSkipSilenceDurationThresholdChange = {},
            isSkipSilenceNotificationEnabled = true,
            onSkipSilenceNotificationEnabledChange = {},
            isSleepFadeOutEnabled = true,
            onSleepFadeOutEnabledChange = {},
            isShakeToResetEnabled = true,
            onShakeToResetEnabledChange = {},
            sleepMode = SleepMode.Regular,
            onSleepModeChange = {},
            // Preview 显式引用设置模型里的默认玻璃效果，避免设置页预览另行硬编码默认值。
            glassEffectMode = AppSettings.DEFAULT_GLASS_EFFECT_MODE,
            onGlassEffectModeChange = {},
            // Preview 中传入默认的自动回退秒数（0秒，已关闭）。
            autoRewindSeconds = 0,
            onAutoRewindSecondsChange = {},
            // Preview 中传入默认的通知避让开关状态，默认设置为未开启（false）。
            isNotificationAvoidanceEnabled = false,
            onNotificationAvoidanceEnabledChange = {},
            // Preview 中传入空实现的开源许可点击回调。
            onAboutLibrariesClick = {}
        )
    }
}

/**
 * 新增 Slider 专用的设置项辅助组件，便于展示带数值的可滑动条目。
 * 增加了 enabled 属性，在禁用时运用 alpha(0.38f) 将整行内容置灰，同时同步禁用内部的 Slider。
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
 *
 * 采用 Material 3 的 labelLarge 排版样式，辅以 Bold 加粗和 primary 主题色，
 * 左右对称 16.dp 边距，上下分别配置 24.dp 与 8.dp 外边距以实现精美的排版质感。
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
