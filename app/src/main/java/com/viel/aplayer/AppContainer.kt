package com.viel.aplayer

import android.content.Context
import com.viel.aplayer.data.AppSettingsRepository
import com.viel.aplayer.data.LibraryRepository
import com.viel.aplayer.data.BookLibraryRepository
import com.viel.aplayer.data.usecase.DeleteLibraryRootUseCase

/**
 * 简单的依赖注入容器，用于统一管理与初始化全局仓库与高层跨域用例。
 */
interface AppContainer {
    val libraryRepository: LibraryRepository
    val settingsRepository: AppSettingsRepository
    
    /**
     * 跨域编排用例：注销并清理书库根目录（安全协调播放状态与物理数据擦除）
     */
    val deleteLibraryRootUseCase: DeleteLibraryRootUseCase
}

class DefaultAppContainer(private val context: Context) : AppContainer {
    override val libraryRepository: LibraryRepository by lazy {
        LibraryRepository.getInstance(context)
    }
    
    override val settingsRepository: AppSettingsRepository by lazy {
        AppSettingsRepository.getInstance(context)
    }

    /**
     * 延迟实例化注销书库根目录用例，通过构造注入同时关联媒体播放与底层数据源仓储。
     */
    override val deleteLibraryRootUseCase: DeleteLibraryRootUseCase by lazy {
        DeleteLibraryRootUseCase(
            playbackManager = com.viel.aplayer.media.PlaybackManager.getInstance(context),
            bookLibraryRepository = BookLibraryRepository.getInstance(context)
        )
    }
}