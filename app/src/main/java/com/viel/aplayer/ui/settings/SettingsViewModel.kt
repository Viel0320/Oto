package com.viel.aplayer.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import com.viel.aplayer.APlayerApplication
import com.viel.aplayer.data.entity.LibraryRootEntity
import com.viel.aplayer.data.store.AppSettings

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
    val libraryRoots: StateFlow<List<com.viel.aplayer.data.entity.LibraryRootEntity>> = libraryRepository.observeLibraryRoots()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    init {
        viewModelScope.launch {
            // Settings entry should show current SAF grant status, including revoked roots.
            libraryRepository.refreshLibraryRootStatuses()
        }
    }

    fun refreshLibraryRootStatuses() {
        viewModelScope.launch {
            // Route entry calls this explicitly because the SettingsViewModel may be created before navigation.
            libraryRepository.refreshLibraryRootStatuses()
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
}