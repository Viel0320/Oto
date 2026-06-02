package com.viel.aplayer.ui.settings

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.viel.aplayer.ui.common.UiEvent
import com.viel.aplayer.ui.settings.about.AboutLibrariesScreen
import com.viel.aplayer.ui.theme.APlayerTheme

/**
 * 设置功能的独立 Activity（Stateful 容器外壳）。
 *
 * 经过物理拆分重构，具体的配置绘制逻辑已被完整剥离到独立的 [SettingsScreen.kt] 中，
 * 本 Activity 仅作为纯粹的有状态生命周期载体，专注于 SettingsViewModel 状态流订阅与中转回调。
 * 物理职责聚焦，完美规避了上帝类架构隐患。
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
                    val settingsViewModel: SettingsViewModel = viewModel()
                    val settingsState by settingsViewModel.settingsState.collectAsStateWithLifecycle()
                    val libraryRoots by settingsViewModel.libraryRoots.collectAsStateWithLifecycle()
                    val absServers by settingsViewModel.absServers.collectAsStateWithLifecycle()
                    val absConnectionState by settingsViewModel.absConnectionState.collectAsStateWithLifecycle()
                    val absSyncConfirmationState by settingsViewModel.absSyncConfirmationState.collectAsStateWithLifecycle()
                    val context = LocalContext.current

                    LaunchedEffect(Unit) {
                        settingsViewModel.uiEvents.collect { event ->
                            when (event) {
                                is UiEvent.ShowToast -> Toast.makeText(context, event.message, Toast.LENGTH_SHORT).show()
                                else -> {}
                            }
                        }
                    }

                    // 维护一个 Boolean 状态，用于驱动设置主页 (SettingsScreen) 和开源许可面板 (AboutLibrariesScreen) 之间的显示和切换。
                    var showAboutLibraries by remember { mutableStateOf(false) }

                    // 运用 BackHandler 物理拦截手势：当处于开源许可面板时，物理返回按键或系统侧滑返回仅会重置 showAboutLibraries = false 返回设置主页，从而避免直接关闭整个设置页面。
                    BackHandler(enabled = showAboutLibraries) {
                        showAboutLibraries = false
                    }

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
                                // 直接通过 SettingsViewModel 添加媒体库根目录，与其共享同一套 SAF 授权流程。
                                // 彻底剥离对 LibraryViewModel 的依赖，防止进入设置页时重新创建该 ViewModel 而导致重复触发其 init 块中的 COLD_START 冷启动同步。
                                onLibraryRootSelected = { uri -> settingsViewModel.onLibraryRootSelected(uri) },
                                // WebDAV 入口同样委托给 SettingsViewModel，保持设置页独立管理媒体库来源。
                                onWebDavRootSubmitted = { url, username, password, displayName, basePath ->
                                    settingsViewModel.onWebDavRootSubmitted(url, username, password, displayName, basePath)
                                },
                                onAbsConnectionTest = { baseUrl, username, password ->
                                    settingsViewModel.testAbsConnection(baseUrl, username, password)
                                },
                                onAbsRootSubmitted = { baseUrl, username, password, libraryId, libraryName ->
                                    settingsViewModel.addAbsServerWithPassword(baseUrl, username, password, libraryId, libraryName)
                                },
                                onAbsSync = { rootId ->
                                    settingsViewModel.syncAbsRoot(rootId)
                                },
                                onAbsBackgroundSync = { rootId ->
                                    settingsViewModel.scheduleAbsRootSync(rootId)
                                },
                                absSyncConfirmationState = absSyncConfirmationState,
                                onDismissLargeAbsSync = {
                                    settingsViewModel.dismissLargeAbsSyncConfirmation()
                                },
                                onRescan = { settingsViewModel.triggerRescan() },
                                libraryRoots = libraryRoots,
                                absServers = absServers,
                                absConnectionState = absConnectionState,
                                isChapterProgressMode = settingsState.isChapterProgressMode,
                                onChapterProgressModeChange = { settingsViewModel.toggleChapterProgressMode(it) },
                                isCleartextTrafficAllowed = settingsState.isCleartextTrafficAllowed,
                                onCleartextTrafficAllowedChange = { settingsViewModel.toggleCleartextTrafficAllowed(it) },
                                onDeleteLibraryRoot = { settingsViewModel.deleteLibraryRoot(it) },
                                isSkipSilenceEnabled = settingsState.isSkipSilenceEnabled,
                                onSkipSilenceEnabledChange = { settingsViewModel.toggleSkipSilenceEnabled(it) },
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
