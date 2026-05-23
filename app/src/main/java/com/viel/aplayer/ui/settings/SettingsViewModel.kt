package com.viel.aplayer.ui.settings

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import com.viel.aplayer.APlayerApplication
import com.viel.aplayer.data.entity.LibraryRootEntity
import com.viel.aplayer.data.store.AppSettings
import com.viel.aplayer.data.store.GlassEffectMode
import com.viel.aplayer.data.store.SleepMode

/**
 * 设置页面的 ViewModel，负责管理持久化配置的交互。
 */
class SettingsViewModel(application: Application) : AndroidViewModel(application) {
    private val container = (application as APlayerApplication).container
    private val settingsRepository = container.settingsRepository
    private val libraryRepository = container.libraryRepository

    /** 暴露给 UI 的设置状态流 */
    val settingsState: StateFlow<AppSettings> = settingsRepository.settingsFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = AppSettings()
        )

    /** 暴露给 UI 的媒体库根目录流 */
    val libraryRoots: StateFlow<List<LibraryRootEntity>> = libraryRepository.observeLibraryRoots()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = libraryRepository.getCachedLibraryRoots() // 详尽的中文注释：使用全局单例 LibraryRepository 内存中的最新缓存作为初始值，确保首帧瞬间加载出目录，杜绝首帧空白引起的布局抖动
        )

    init {
        viewModelScope.launch {
            // Settings entry should show current SAF grant status, including revoked roots.
            // 详尽的中文注释：延迟 500 毫秒后再触发系统的 SAF 物理授权检测，以完美避开 Activity 启动转场动画及首帧绘制的核心渲染时间，保证界面展示绝对丝滑
            kotlinx.coroutines.delay(500)
            libraryRepository.refreshLibraryRootStatuses()
        }
    }

    fun refreshLibraryRootStatuses() {
        viewModelScope.launch {
            // Route entry calls this explicitly because the SettingsViewModel may be created before navigation.
            // 详尽的中文注释：手动触发的刷新依然走异步协程检测，保证物理可达性更新。
            libraryRepository.refreshLibraryRootStatuses()
        }
    }

    // 为每一次改动添加详尽的中文注释：
    // 在设置页中选择媒体库目录后的回调逻辑，负责获取持久化 SAF 授权，将其存入数据库并异步触发 USER 手动增量扫描任务。
    // 这能让设置页完全独立处理 SAF 动作，从而将 LibraryViewModel 彻底解耦，消除 Activity 切换时的冷启动扫描问题。
    fun onLibraryRootSelected(uri: Uri) {
        viewModelScope.launch {
            try {
                getApplication<Application>().contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
                // 写入数据库，并等待写入完成以保证扫描的即时性
                libraryRepository.setLibraryRoot(uri)
                // 触发工作器进行手动媒体库扫描同步
                libraryRepository.syncLibrary("USER")
            } catch (e: SecurityException) {
                // 详尽的中文注释：对捕获的 SecurityException 赋予脱敏日志信息输出，便于追踪定位 SAF 授权失效事件而不吞掉关键错误。
                android.util.Log.e("SettingsViewModel", "SecurityException occurred while taking persistable URI permission for tree: ${uri.hashCode().toString(16)}", e)
            }
        }
    }

    fun clearSearchHistory() {
        viewModelScope.launch {
            libraryRepository.clearHistory()
        }
    }

    fun triggerRescan() {
        viewModelScope.launch {
            libraryRepository.syncLibrary("USER")
        }
    }

    fun toggleChapterProgressMode(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.updateChapterProgressMode(enabled)
        }
    }

    // 详尽的中文注释：新增切换是否允许 HTTP 明文流量持久化配置的交互方法。
    fun toggleCleartextTrafficAllowed(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.updateCleartextTrafficAllowed(enabled)
        }
    }

    // 删除库根目录并释放 SAF 授权。返回结果通过 Toast 通知用户。
    fun deleteLibraryRoot(root: LibraryRootEntity) {
        viewModelScope.launch {
            val playbackWasStopped = libraryRepository.deleteLibraryRoot(root)
            val message = if (playbackWasStopped) {
                "媒体库已移除，当前播放已停止"
            } else {
                "媒体库已移除"
            }
            android.widget.Toast.makeText(
                getApplication(),
                message,
                android.widget.Toast.LENGTH_SHORT
            ).show()
        }
    }

    // 为每一次改动添加详尽的中文注释：新增切换自动跳过静音（Skip Silence）功能全局总开关的交互方法。
    fun toggleSkipSilenceEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.updateSkipSilenceEnabled(enabled)
        }
    }

    // 为每一次改动添加详尽的中文注释：新增修改自动跳过静音的判定最小时长值（0.5s-5.0s）的交互方法。
    fun updateSkipSilenceDurationThreshold(duration: Float) {
        viewModelScope.launch {
            settingsRepository.updateSkipSilenceDurationThreshold(duration)
        }
    }

    // 为每一次改动添加详尽的中文注释：新增切换是否在自动跳过静音发生时弹出 Toast 提示的全局开关交互方法。
    fun toggleSkipSilenceNotificationEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.updateSkipSilenceNotificationEnabled(enabled)
        }
    }

    // 为每一次改动添加详尽的中文注释：新增切换睡眠定时音量渐隐功能全局总开关的交互方法。
    fun toggleSleepFadeOutEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.updateSleepFadeOutEnabled(enabled)
        }
    }

    // 为每一次改动添加详尽的中文注释：新增切换“摇晃手机重置睡眠定时器 (Shake-to-Reset)”全局总开关的交互方法。
    fun toggleShakeToResetEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.updateShakeToResetEnabled(enabled)
        }
    }

    // 为每一次改动添加详尽的中文注释：新增设置页切换睡眠模式的交互方法，通过协程异步更新 DataStore 持久化配置，由 UI 组件触发。
    fun updateSleepMode(mode: SleepMode) {
        viewModelScope.launch {
            settingsRepository.updateSleepMode(mode)
        }
    }

    // 为每一次改动添加详尽的中文注释：新增设置页切换悬浮层视觉效果模式的交互方法，统一写入 DataStore 供主页 and 播放器实时响应。
    fun updateGlassEffectMode(mode: GlassEffectMode) {
        viewModelScope.launch {
            settingsRepository.updateGlassEffectMode(mode)
        }
    }
}
