package com.viel.aplayer.ui.settings

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
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.LinearScale
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.viel.aplayer.R
import com.viel.aplayer.data.db.AudiobookSchema
import com.viel.aplayer.data.entity.LibraryRootEntity
import com.viel.aplayer.data.store.GlassEffectMode
import com.viel.aplayer.data.store.SleepMode
import com.viel.aplayer.data.store.ThemeMode

/**
 * LibraryDirectoriesSection Composable: Renders media library folder locations and sync history statuses.
 */
@Composable
fun LibraryDirectoriesSection(
    libraryRootDisplays: List<LibraryRootDisplayState>,
    onRootClick: (LibraryRootEntity) -> Unit,
    onAddLibraryClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        SettingsSectionHeader(title = "媒体库管理")
        libraryRootDisplays.forEach { display ->
            val root = display.root
            val isWebDavRoot = root.sourceType == AudiobookSchema.LibrarySourceType.WEBDAV
            val isAbsRoot = root.sourceType == AudiobookSchema.LibrarySourceType.ABS
            val locationLine = display.selectedLibraryText
                ?.takeIf { it.isNotBlank() }
                ?.let { libraryName -> "位置：${display.locationText} · 当前书库：$libraryName" }
                ?: "位置：${display.locationText}"
            
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onRootClick(root) }
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
        SettingsItem(
            title = "添加媒体库",
            subtitle = "支持本地 (SAF)、WebDAV、Audiobookshelf 服务器",
            icon = Icons.Rounded.FolderOpen,
            onClick = onAddLibraryClick
        )
    }
}

/**
 * InterfaceSettingsSection Composable: Handles themes, dynamic color settings, and experimental blur effect toggles.
 */
@Composable
fun InterfaceSettingsSection(
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
    isDynamicColorEnabled: Boolean,
    onDynamicColorEnabledChange: (Boolean) -> Unit,
    glassEffectMode: GlassEffectMode,
    onGlassEffectModeChange: (GlassEffectMode) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        SettingsSectionHeader(title = "界面效果")
        val isHaze = glassEffectMode == GlassEffectMode.Haze
        SettingsSegmentedThemeModeItem(
            title = "夜间模式",
            subtitle = "选择界面配色（跟随系统: 自动切换；浅色: 始终使用亮色调；深色: 始终使用暗色调）",
            icon = Icons.Rounded.LinearScale,
            selectedMode = if (isHaze) ThemeMode.Dark else themeMode,
            onModeSelected = onThemeModeChange,
            enabled = !isHaze
        )
        val isDynamicColorSupported = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O
        SettingsToggleItem(
            title = "Monet 动态取色",
            subtitle = if (isDynamicColorSupported) "开启后，应用主题配色将自动从您的系统壁纸中提取" else "Monet 动态取色需要 Android 8.0 及以上系统版本",
            icon = Icons.Rounded.LinearScale,
            checked = isDynamicColorEnabled,
            onCheckedChange = onDynamicColorEnabledChange,
            enabled = isDynamicColorSupported
        )
        SettingsToggleItem(
            title = "实验性模糊效果",
            subtitle = "实验性支持部分界面的 background 模糊效果",
            icon = Icons.Rounded.LinearScale,
            checked = glassEffectMode == GlassEffectMode.Haze,
            onCheckedChange = { isChecked ->
                val newMode = if (isChecked) GlassEffectMode.Haze else GlassEffectMode.Material
                onGlassEffectModeChange(newMode)
            }
        )
    }
}

/**
 * PlaybackNetworkSection Composable: Bundles chapter progress display parameters, plain text allowance toggles, and notification avoidance setup.
 */
@Composable
fun PlaybackNetworkSection(
    isChapterProgressMode: Boolean,
    onChapterProgressModeChange: (Boolean) -> Unit,
    isCleartextTrafficAllowed: Boolean,
    onCleartextTrafficAllowedChange: (Boolean) -> Unit,
    // Insecure TLS Config Option: Switch parameter to toggle bypass validation for self-signed certificates.
    isAllowInsecureTls: Boolean,
    onAllowInsecureTlsChange: (Boolean) -> Unit,
    isNotificationAvoidanceEnabled: Boolean,
    onNotificationAvoidanceEnabledChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        SettingsSectionHeader(title = "播放与网络")
        SettingsToggleItem(
            title = stringResource(R.string.chapter_progress_title),
            subtitle = stringResource(R.string.chapter_progress_subtitle),
            icon = Icons.Rounded.LinearScale,
            checked = isChapterProgressMode,
            onCheckedChange = onChapterProgressModeChange
        )
        SettingsToggleItem(
            title = "允许明文 HTTP 流量",
            subtitle = "允许应用播放和加载不安全的 http:// 网络有声书源。建议保持关闭以维持最高安全边界。",
            icon = Icons.Rounded.LinearScale,
            checked = isCleartextTrafficAllowed,
            onCheckedChange = onCleartextTrafficAllowedChange
        )
        // Insecure TLS Switch: Render standard toggle item for insecure TLS settings block below HTTP cleartext settings.
        SettingsToggleItem(
            title = "允许不安全 TLS",
            subtitle = "允许应用忽略自签名证书或不安全服务器的 SSL/TLS 证书校验。建议保持关闭以维持最高安全边界。",
            icon = Icons.Rounded.LinearScale,
            checked = isAllowInsecureTls,
            onCheckedChange = onAllowInsecureTlsChange
        )
        SettingsToggleItem(
            title = "通知避让",
            subtitle = "开启后，播放将在失去焦点（如收到通知、导航播报、来电等）时暂停，重获焦点时恢复，避免降音避让时漏听内容。",
            icon = Icons.Rounded.LinearScale,
            checked = isNotificationAvoidanceEnabled,
            onCheckedChange = onNotificationAvoidanceEnabledChange
        )
    }
}

/**
 * SkipSilenceSection Composable: Toggles silence stripping controls for playing book files.
 */
@Composable
fun SkipSilenceSection(
    isSkipSilenceEnabled: Boolean,
    onSkipSilenceEnabledChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        SettingsSectionHeader(title = "自动跳过静音")
        SettingsToggleItem(
            title = "自动跳过静音期",
            subtitle = "在播放有声书时，自动跳过主播停顿、换气及章节末尾等无声片段以提高收听效率。",
            icon = Icons.Rounded.LinearScale,
            checked = isSkipSilenceEnabled,
            onCheckedChange = onSkipSilenceEnabledChange
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
        SettingsSectionHeader(title = "睡眠定时器")
        SettingsSegmentedSleepModeItem(
            title = "睡眠模式",
            subtitle = "选择计时触发机制（常规: 设定时间即计时；运动跟踪: 静止才计时，运动则暂停；睡眠跟踪: 熟睡才计时）",
            icon = Icons.Rounded.LinearScale,
            selectedMode = sleepMode,
            onModeSelected = onSleepModeChange
        )
        SettingsToggleItem(
            title = "睡眠倒计时音量渐隐",
            subtitle = "当倒计时走到最后 10 秒（或章节快结束前 10 秒）时，音量将柔和对数式递减到静音，避免突然的无声惊醒您。",
            icon = Icons.Rounded.LinearScale,
            checked = isSleepFadeOutEnabled,
            onCheckedChange = onSleepFadeOutEnabledChange
        )
        SettingsToggleItem(
            title = "摇晃手机重置睡眠定时器",
            subtitle = "当进入睡眠定时器最后 10 秒音量渐隐阶段时，轻轻摇晃手机即可触发轻微震动反馈并重置定时器。若为“章节结束停止模式”，摇晃重置时若有下一章将自动顺延为 15 分钟常规倒计时并顺延至下一个章节继续播放，免去夜间亮屏解锁的繁琐。",
            icon = Icons.Rounded.LinearScale,
            checked = isShakeToResetEnabled,
            onCheckedChange = onShakeToResetEnabledChange
        )
    }
}

/**
 * AutoRewindSection Composable: Provides control slider mapping rewind duration values in seconds.
 */
@Composable
fun AutoRewindSection(
    autoRewindSeconds: Int,
    onAutoRewindSecondsChange: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        SettingsSectionHeader(title = "自动回放")
        SettingsSliderItem(
            title = "暂停自动回放",
            subtitle = "暂停或以任何方式（通知避让除外）停止播放时自动回退的时长",
            icon = Icons.Rounded.LinearScale,
            value = autoRewindSeconds.toFloat(),
            onValueChange = { onAutoRewindSecondsChange(it.toInt()) },
            valueRange = 0f..30f,
            steps = 29,
            valueFormatter = {
                if (it.toInt() == 0) "已关闭" else String.format(java.util.Locale.US, "%d 秒", it.toInt())
            },
            enabled = true
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
        SettingsSectionHeader(title = "关于")
        SettingsItem(
            title = "开源许可",
            subtitle = "查看应用使用的开源库及许可协议",
            icon = Icons.Rounded.Info,
            onClick = onAboutLibrariesClick
        )
    }
}
