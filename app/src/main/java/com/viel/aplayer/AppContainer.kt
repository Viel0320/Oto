package com.viel.aplayer

import android.content.Context
import com.viel.aplayer.data.AppSettingsRepository
import com.viel.aplayer.data.LibraryRepository

/**
 * 简单的依赖注入容器，用于统一管理与初始化全局仓库。
 */
interface AppContainer {
    val libraryRepository: LibraryRepository
    val settingsRepository: AppSettingsRepository
}

class DefaultAppContainer(private val context: Context) : AppContainer {
    override val libraryRepository: LibraryRepository by lazy {
        LibraryRepository.getInstance(context)
    }
    
    override val settingsRepository: AppSettingsRepository by lazy {
        AppSettingsRepository.getInstance(context)
    }
}