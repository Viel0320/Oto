package com.viel.aplayer

import android.content.Context
import com.viel.aplayer.data.AppSettingsRepository
import com.viel.aplayer.data.LibraryRepository
import com.viel.aplayer.data.BookLibraryRepository
import com.viel.aplayer.data.PlaybackHistoryRepository
import com.viel.aplayer.data.LibraryFacade
import com.viel.aplayer.data.gateway.BookQueryGateway
import com.viel.aplayer.data.gateway.ProgressGateway
import com.viel.aplayer.data.gateway.ScanScheduler
import com.viel.aplayer.data.service.BookQueryService
import com.viel.aplayer.data.service.ProgressService
import com.viel.aplayer.data.service.ScanService
import com.viel.aplayer.data.usecase.DeleteLibraryRootUseCase

/**
 * 简单的依赖注入容器，用于统一管理与初始化全局仓库与高层跨域用例。
 */
interface AppContainer {
    @Deprecated("请逐步切换到使用更精细的 Gateway 分域网关或高层的 libraryFacade 进行交互")
    val libraryRepository: LibraryRepository
    val settingsRepository: AppSettingsRepository
    
    /**
     * 跨域编排用例：注销并清理书库根目录（安全协调播放状态与物理数据擦除）
     */
    val deleteLibraryRootUseCase: DeleteLibraryRootUseCase

    /**
     * 新的精细化业务门面：聚合书籍查询、播放进度及后台扫描子系统。
     */
    val libraryFacade: LibraryFacade

    /**
     * 有声书及书签查询与维护分域网关。
     */
    val bookQueryGateway: BookQueryGateway

    /**
     * 播放位置与多音轨物理状态网关。
     */
    val progressGateway: ProgressGateway

    /**
     * 媒体重扫触发及定时任务调度网关。
     */
    val scanScheduler: ScanScheduler
}

class DefaultAppContainer(private val context: Context) : AppContainer {
    @Suppress("DEPRECATION")
    @Deprecated("请逐步切换到使用更精细的 Gateway 分域网关或高层的 libraryFacade 进行交互")
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

    /**
     * 延迟初始化有声书及章节书签只读只写网关。
     */
    override val bookQueryGateway: BookQueryGateway by lazy {
        BookQueryService(BookLibraryRepository.getInstance(context))
    }

    /**
     * 延迟初始化播放位置与进度落库服务网关。
     */
    override val progressGateway: ProgressGateway by lazy {
        ProgressService(PlaybackHistoryRepository.getInstance(context))
    }

    /**
     * 延迟初始化前后台扫描重扫派发网关。
     */
    override val scanScheduler: ScanScheduler by lazy {
        ScanService(BookLibraryRepository.getInstance(context))
    }

    /**
     * 聚合新高层门面组件，通过接口委托注入三大子网关实例，实现低耦合的跨领域调用。
     */
    override val libraryFacade: LibraryFacade by lazy {
        LibraryFacade(
            bookQueryGateway = bookQueryGateway,
            progressGateway = progressGateway,
            scanScheduler = scanScheduler
        )
    }
}