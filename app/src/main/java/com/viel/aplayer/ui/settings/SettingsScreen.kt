package com.viel.aplayer.ui.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.LinearScale
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.viel.aplayer.R
import com.viel.aplayer.data.entity.LibraryRootEntity
import com.viel.aplayer.ui.theme.APlayerTheme
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.material3.Slider

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onLibraryRootSelected: (Uri) -> Unit,
    onClearHistory: () -> Unit,
    onRescan: () -> Unit,
    libraryRoots: List<com.viel.aplayer.data.entity.LibraryRootEntity>,
    isChapterProgressMode: Boolean,
    onChapterProgressModeChange: (Boolean) -> Unit,
    // 详尽的中文注释：新增是否允许明文 HTTP 流量标志及对应的触发方法。
    isCleartextTrafficAllowed: Boolean,
    onCleartextTrafficAllowedChange: (Boolean) -> Unit,
    // 详尽的中文注释：新增删除库根目录并释放物理授权的触发接口方法。
    onDeleteLibraryRoot: (com.viel.aplayer.data.entity.LibraryRootEntity) -> Unit,
    // 为每一次改动添加详尽的中文注释：新增自动跳过静音（Skip Silence）功能是否启用的全局状态标志。
    isSkipSilenceEnabled: Boolean,
    // 为每一次改动添加详尽的中文注释：新增切换自动跳过静音全局开关状态的回调事件。
    onSkipSilenceEnabledChange: (Boolean) -> Unit,
    // 为每一次改动添加详尽的中文注释：新增静音判定最小时长阈值（秒，默认2.0f）的状态。
    skipSilenceDurationThreshold: Float,
    // 为每一次改动添加详尽的中文注释：新增修改静音判定最小时长阈值的回调事件。
    onSkipSilenceDurationThresholdChange: (Float) -> Unit,
    // 为每一次改动添加详尽的中文注释：新增静音跳过时是否弹出 Toast 提示的全局状态标志。
    isSkipSilenceNotificationEnabled: Boolean,
    // 为每一次改动添加详尽的中文注释：新增切换静音跳过 Toast 提示开关的回调事件。
    onSkipSilenceNotificationEnabledChange: (Boolean) -> Unit,
    // 为每一次改动添加详尽的中文注释：新增睡眠倒计时音量渐隐功能是否启用的全局状态标志。
    isSleepFadeOutEnabled: Boolean,
    // 为每一次改动添加详尽的中文注释：新增切换睡眠倒计时音量渐隐开关的回调事件。
    onSleepFadeOutEnabledChange: (Boolean) -> Unit,
    // 为每一次改动添加详尽的中文注释：新增摇晃手机重置睡眠定时器功能是否启用的全局状态标志。
    isShakeToResetEnabled: Boolean,
    // 为每一次改动添加详尽的中文注释：新增切换摇晃手机重置睡眠定时器开关的回调事件。
    onShakeToResetEnabledChange: (Boolean) -> Unit
) {
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        uri?.let(onLibraryRootSelected)
    }

    // 详尽的中文注释：定义用于记录用户即将触发删除动作的媒体库目录 State 变量，用来拉起强提醒的 AlertDialog 二次确认弹窗。
    var rootToDelete by remember { mutableStateOf<LibraryRootEntity?>(null) }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
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
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            item {
                SettingsItem(
                    title = stringResource(R.string.library_folder_title),
                    subtitle = stringResource(R.string.library_folder_subtitle),
                    icon = Icons.Rounded.FolderOpen,
                    onClick = { launcher.launch(null) }
                )
            }

            // 详尽中文注释：M-20 修复 — 添加模式 key，使用库根 treeUri 作为唯一标识，
            // 防止列表刷新时 item 状态错位复用导致 UI 错乱。
            items(libraryRoots.size, key = { libraryRoots[it].treeUri }) { index ->
                val root = libraryRoots[index]
                val decodedPath = try {
                    Uri.decode(root.treeUri).substringAfterLast(":")
                } catch (_: Exception) {
                    root.treeUri
                }
                
                // 详尽的中文注释：重构媒体库卡片单行，右侧渲染删除图标。
                // 用户点击垃圾桶删除图标后，将当前 root 赋给 rootToDelete 从而异步弹窗让用户进行二次安全确认。
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Rounded.FolderOpen,
                        contentDescription = null,
                        modifier = Modifier.size(24.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = decodedPath, style = MaterialTheme.typography.titleMedium)
                        Text(
                            text = "状态: ${root.status}", 
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
                SettingsToggleItem(
                    title = stringResource(R.string.chapter_progress_title),
                    subtitle = stringResource(R.string.chapter_progress_subtitle),
                    icon = Icons.Rounded.LinearScale,
                    checked = isChapterProgressMode,
                    onCheckedChange = onChapterProgressModeChange
                )
            }
            item {
                // 为每一次改动添加详尽的中文注释：新增“睡眠倒计时音量渐隐”全局控制开关项。
                SettingsToggleItem(
                    title = "睡眠倒计时音量渐隐",
                    subtitle = "当倒计时走到最后 10 秒（或章节快结束前 10 秒）时，音量将柔和对数式递减到静音，避免突然的无声惊醒您。",
                    icon = Icons.Rounded.LinearScale,
                    checked = isSleepFadeOutEnabled,
                    onCheckedChange = onSleepFadeOutEnabledChange
                )
            }
            item {
                // 为每一次改动添加详尽的中文注释：新增“摇晃手机重置睡眠定时器”全局控制开关项。
                SettingsToggleItem(
                    title = "摇晃手机重置睡眠定时器",
                    subtitle = "当进入睡眠定时器最后 10 秒音量渐隐阶段时，轻轻摇晃手机即可触发轻微震动反馈并重置定时器。若为“章节结束停止模式”，摇晃重置时若有下一章将自动顺延为 15 分钟常规倒计时并顺延至下一个章节继续播放，免去夜间亮屏解锁的繁琐。",
                    icon = Icons.Rounded.LinearScale,
                    checked = isShakeToResetEnabled,
                    onCheckedChange = onShakeToResetEnabledChange
                )
            }
            item {
                // 详尽的中文注释：新增“允许明文 HTTP 流量”持久化持久化控制开关。
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
                // 为每一次改动添加详尽的中文注释：新增“自动跳过静音期”全局控制开关项。
                SettingsToggleItem(
                    title = "自动跳过静音期",
                    subtitle = "在播放有声书时，自动跳过主播停顿、换气及章节末尾等无声片段以提高收听效率。",
                    icon = Icons.Rounded.LinearScale,
                    checked = isSkipSilenceEnabled,
                    onCheckedChange = onSkipSilenceEnabledChange
                )
            }
            if (isSkipSilenceEnabled) {
                item {
                    // 为每一次改动添加详尽的中文注释：判定最小时长滑块调节项。通过 steps = 8 将范围限制在 0.5s-5.0s，步长精准为 0.5s。
                    SettingsSliderItem(
                        title = "静音判定最小时长",
                        subtitle = "判定静音的持续时间",
                        icon = Icons.Rounded.LinearScale,
                        value = skipSilenceDurationThreshold,
                        onValueChange = onSkipSilenceDurationThresholdChange,
                        valueRange = 0.5f..5.0f,
                        steps = 8,
                        valueFormatter = { String.format(java.util.Locale.US, "%.1f 秒", it) }
                    )
                }
                item {
                    // 为每一次改动添加详尽的中文注释：跳过静音时弹出 Toast 温馨提醒开关项。
                    SettingsToggleItem(
                        title = "跳过静音时弹出通知",
                        subtitle = "当应用跳过静音时，以 Toast 形式在底部进行温馨提示。",
                        icon = Icons.Rounded.LinearScale,
                        checked = isSkipSilenceNotificationEnabled,
                        onCheckedChange = onSkipSilenceNotificationEnabledChange
                    )
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
            item {
                SettingsItem(
                    title = stringResource(R.string.clear_history_title),
                    subtitle = stringResource(R.string.clear_history_subtitle),
                    icon = Icons.Rounded.DeleteSweep,
                    onClick = onClearHistory
                )
            }
        }
    }

    // 详尽的中文注释：渲染用户确定移除库根目录时的警示 AlertDialog 弹窗。
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

@Composable
private fun SettingsToggleItem(
    title: String,
    subtitle: String,
    icon: ImageVector,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
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
            onCheckedChange = onCheckedChange
        )
    }
}

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

@Preview(showBackground = true, apiLevel = 36)
@Composable
fun SettingsScreenPreview() {
    APlayerTheme {
        SettingsScreen(
            onBack = {},
            onLibraryRootSelected = {},
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
            // 为每一次改动添加详尽的中文注释：为预览界面注入默认开启的睡眠定时音量渐隐状态。
            isSleepFadeOutEnabled = true,
            onSleepFadeOutEnabledChange = {},
            // 为每一次改动添加详尽的中文注释：为预览界面注入默认开启的摇晃重置睡眠定时器状态。
            isShakeToResetEnabled = true,
            onShakeToResetEnabledChange = {}
        )
    }
}

// 为每一次改动添加详尽的中文注释：新增 Slider 专用的设置项辅助组件，便于展示带数值的可滑动条目。
@Composable
private fun SettingsSliderItem(
    title: String,
    subtitle: String,
    icon: ImageVector,
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    valueFormatter: (Float) -> String
) {
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
                text = "$subtitle: ${valueFormatter(value)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Slider(
                value = value,
                onValueChange = onValueChange,
                valueRange = valueRange,
                steps = steps,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}