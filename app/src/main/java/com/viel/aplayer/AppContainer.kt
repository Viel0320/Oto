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
import com.viel.aplayer.data.gateway.LibraryRootGateway
import com.viel.aplayer.data.gateway.CoverGateway
import com.viel.aplayer.data.gateway.SearchHistoryGateway
import com.viel.aplayer.data.service.BookQueryService
import com.viel.aplayer.data.service.ProgressService
import com.viel.aplayer.data.service.ScanService
import com.viel.aplayer.data.service.LibraryRootService
import com.viel.aplayer.data.service.CoverService
import com.viel.aplayer.data.service.SearchService
import com.viel.aplayer.data.usecase.DeleteLibraryRootUseCase
import com.viel.aplayer.library.vfs.VfsFileInterface
import com.viel.aplayer.data.db.AppDatabase
import com.viel.aplayer.media.PlaybackFileLookup

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

    /**
     * 书库根目录管理及维护分域网关。
     */
    val libraryRootGateway: LibraryRootGateway

    /**
     * 封面元数据提取及背景色维护分域网关。
     */
    val coverGateway: CoverGateway

    /**
     * 搜索检索词历史维护分域网关。
     */
    val searchHistoryGateway: SearchHistoryGateway

    /**
     * 运行期唯一的底层虚拟文件系统物理 I/O 读取通道单例。
     */
    val vfsFileInterface: VfsFileInterface

    /**
     * 供播放层使用的音频物理文件快速检索接口。
     */
    val playbackFileLookup: PlaybackFileLookup
}

class DefaultAppContainer(private val context: Context) : AppContainer {
    @Suppress("DEPRECATION")
    @Deprecated("请逐步切换到使用更精细的 Gateway 分域网关或高层的 libraryFacade 进行交互")
    override val libraryRepository: LibraryRepository by lazy {
        LibraryRepository.getInstance(context, vfsFileInterface)
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
     * 延迟初始化前后台扫描重扫派关。
     */
    override val scanScheduler: ScanScheduler by lazy {
        ScanService(BookLibraryRepository.getInstance(context))
    }

    // 详尽的中文注释：在 M5a.8 中，延迟实例化新创建的书库根目录网关、封面网关和检索词历史网关实现服务。
    override val libraryRootGateway: LibraryRootGateway by lazy {
        LibraryRootService(BookLibraryRepository.getInstance(context))
    }

    // 详尽的中文注释：延迟初始化物理文件解析器单例以统一运行期 VFS 配置并供分域网关调用
    private val physicalFileResolver: com.viel.aplayer.data.PhysicalFileResolver by lazy {
        com.viel.aplayer.data.PhysicalFileResolver.getInstance(context, vfsFileInterface)
    }

    override val coverGateway: CoverGateway by lazy {
        CoverService(
            BookLibraryRepository.getInstance(context),
            physicalFileResolver
        )
    }

    override val searchHistoryGateway: SearchHistoryGateway by lazy {
        SearchService(BookLibraryRepository.getInstance(context))
    }

    /**
     * 聚合新高层门面组件，注入三大子网关及旧仓库依赖，实现平滑重构。
     */
    override val libraryFacade: LibraryFacade by lazy {
        LibraryFacade(
            bookQueryGateway = bookQueryGateway,
            progressGateway = progressGateway,
            scanScheduler = scanScheduler,
            libraryRootGateway = libraryRootGateway,
            coverGateway = coverGateway,
            searchHistoryGateway = searchHistoryGateway,
            legacyRepository = libraryRepository
        )
    }

    /**
     * 延迟初始化运行期唯一的虚拟文件系统物理数据通道单例，供前台播放与恢复机制共享。
     */
    override val vfsFileInterface: VfsFileInterface by lazy {
        VfsFileInterface(
            context.applicationContext,
            libraryRootDao = AppDatabase.getInstance(context).libraryRootDao()
        )
    }

    /**
     * 延迟初始化供播放器数据源使用的音频文件数据库查询服务。
     */
    override val playbackFileLookup: PlaybackFileLookup by lazy {
        com.viel.aplayer.media.DefaultPlaybackFileLookup(
            AppDatabase.getInstance(context).bookDao()
        )
    }
}