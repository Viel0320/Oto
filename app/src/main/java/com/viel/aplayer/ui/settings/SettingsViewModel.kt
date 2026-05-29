package com.viel.aplayer.ui.settings

import android.app.Application
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
    
    // 
    // 在 M5b.1 迁移中，将 SettingsViewModel 中对旧仓库 libraryRepository 的依赖彻底剥离，
    // 降级解耦为书库根网关 libraryRootGateway 与增量扫描网关 scanScheduler。
    private val libraryRootGateway = container.libraryRootGateway
    private val scanScheduler = container.scanScheduler
    
    /**
     * 跨域书库根目录删除协调器用例，用于替代原有的门面仓库调用，以符合领域解耦规范。
     */
    private val deleteLibraryRootUseCase = container.deleteLibraryRootUseCase

    /** 暴露给 UI 的设置状态流 */
    val settingsState: StateFlow<AppSettings> = settingsRepository.settingsFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = AppSettings()
        )

    /** 暴露给 UI 的媒体库根目录流 */
    // 使用 libraryRootGateway 网关响应式观察观察注册的书库目录并获取内存快照初始缓存值，以消解首帧空白和布局闪烁
    val libraryRoots: StateFlow<List<LibraryRootEntity>> = libraryRootGateway.observeLibraryRoots()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = libraryRootGateway.getCachedLibraryRoots()
        )

    init {
        viewModelScope.launch {
            // Settings entry should show current SAF grant status, including revoked roots.
            // 延迟 500 毫秒后再触发系统的 SAF 物理授权检测，以完美避开 Activity 启动转场动画及首帧绘制的核心渲染时间，保证界面展示绝对丝滑
            kotlinx.coroutines.delay(500)
            // 使用 libraryRootGateway 的 refreshLibraryRootStatuses 校验书库目录可达性
            libraryRootGateway.refreshLibraryRootStatuses()
        }
    }

    fun refreshLibraryRootStatuses() {
        viewModelScope.launch {
            // Route entry calls this explicitly because the SettingsViewModel may be created before navigation.
            // 手动触发的刷新依然走异步协程检测，保证物理可达性更新。
            // 通过 libraryRootGateway 异步校验当前全部已添加的 SAF 目录授权可达状态
            libraryRootGateway.refreshLibraryRootStatuses()
        }
    }

    // 
    // 在设置页中选择媒体库目录后的回调逻辑，负责获取持久化 SAF 授权，将其存入数据库并异步触发 USER 手动增量扫描任务。
    // 这能让设置页完全独立处理 SAF 动作，从而将 LibraryViewModel 彻底解耦，消除 Activity 切换时的冷启动扫描问题。
    fun onLibraryRootSelected(uri: Uri) {
        // 利用 libraryRootGateway 写入新选择的本地 SAF 授权目录，并在应用级后台执行同步
        libraryRootGateway.addLibraryRootAndScheduleSync(uri)
    }

    fun onWebDavRootSubmitted(
        url: String,
        username: String,
        password: String,
        displayName: String,
        basePath: String
    ) {
        // 使用 libraryRootGateway 在后台注册并立即调度 WebDAV 网络书库目录的文件同步
        libraryRootGateway.addWebDavLibraryRootAndScheduleSync(
            url = url,
            username = username,
            password = password,
            displayName = displayName,
            basePath = basePath
        )
    }

    fun triggerRescan() {
        // 调用 scanScheduler 网关异步提交 USER 手动增量重扫指令
        scanScheduler.scheduleLibrarySync("USER")
    }

    fun toggleChapterProgressMode(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.updateChapterProgressMode(enabled)
        }
    }

    // 新增切换是否允许 HTTP 明文流量持久化配置的交互方法。
    fun toggleCleartextTrafficAllowed(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.updateCleartextTrafficAllowed(enabled)
        }
    }

    // 删除库根目录并释放 SAF 授权。通过跨域协调用例执行，安全地处理停播与文件清理，然后通过 Toast 通知用户结果。
    fun deleteLibraryRoot(root: LibraryRootEntity) {
        viewModelScope.launch {
            // 调用高层用例执行删除，该用例会智能判断是否需要在此之前触发紧急停播
            val playbackWasStopped = deleteLibraryRootUseCase.invoke(root)
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

    /**
     * 切换自动跳过静音（Skip Silence）功能全局总开关的交互方法。
     * 经过重构，移除了自定义判定最小时长和温馨通知提示开关的交互逻辑。
     */
    fun toggleSkipSilenceEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.updateSkipSilenceEnabled(enabled)
        }
    }

    // 新增切换睡眠定时音量渐隐功能全局总开关的交互方法。
    fun toggleSleepFadeOutEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.updateSleepFadeOutEnabled(enabled)
        }
    }

    // 新增切换“摇晃手机重置睡眠定时器 (Shake-to-Reset)”全局总开关的交互方法。
    fun toggleShakeToResetEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.updateShakeToResetEnabled(enabled)
        }
    }

    // 新增设置页切换睡眠模式的交互方法，通过协程异步更新 DataStore 持久化配置，由 UI 组件触发。
    fun updateSleepMode(mode: SleepMode) {
        viewModelScope.launch {
            settingsRepository.updateSleepMode(mode)
        }
    }

    // 新增设置页切换悬浮层视觉效果模式的交互方法，统一写入 DataStore 供主页 and 播放器实时响应。
    fun updateGlassEffectMode(mode: GlassEffectMode) {
        viewModelScope.launch {
            settingsRepository.updateGlassEffectMode(mode)
        }
    }

    // 新增设置页修改自动回退播放进度秒数（0-30s）的交互方法，通过协程异步写入持久化 DataStore。
    fun updateAutoRewindSeconds(seconds: Int) {
        viewModelScope.launch {
            settingsRepository.updateAutoRewindSeconds(seconds)
        }
    }

    // 新增设置页切换通知避让（Notification Avoidance）功能全局开关的交互方法，通过协程异步写入持久化 DataStore。
    fun toggleNotificationAvoidanceEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.updateNotificationAvoidanceEnabled(enabled)
        }
    }
}
