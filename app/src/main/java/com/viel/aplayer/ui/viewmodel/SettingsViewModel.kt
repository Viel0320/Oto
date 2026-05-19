package com.viel.aplayer.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.viel.aplayer.APlayerApplication
import com.viel.aplayer.data.AppSettings
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

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
    val libraryRoots: StateFlow<List<com.viel.aplayer.data.LibraryRootEntity>> = libraryRepository.observeLibraryRoots()
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
}
