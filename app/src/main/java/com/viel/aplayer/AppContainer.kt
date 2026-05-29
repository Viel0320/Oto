package com.viel.aplayer

import android.content.Context
import com.viel.aplayer.data.AppSettingsRepository
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
import com.viel.aplayer.media.parser.CoverExtractor
import com.viel.aplayer.media.parser.CoverRecoveryHelper
import com.viel.aplayer.media.parser.MetadataResolver
import com.viel.aplayer.media.subtitle.SubtitleFileResolver
import com.viel.aplayer.library.availability.DetailAvailabilityChecker
import com.viel.aplayer.library.availability.AvailabilityChecker
import com.viel.aplayer.library.availability.PlaybackReachabilityManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * 简单的依赖注入容器，用于统一管理与初始化全局仓库与高层跨域用例。
 */
interface AppContainer {
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

    private val database: AppDatabase by lazy {
        AppDatabase.getInstance(context)
    }

    // 详尽的中文注释：延迟实例化用于运行期音轨物理可读性检测与异常跳轨处理的就绪自愈管理器单例
    private val playbackReachabilityManager: PlaybackReachabilityManager by lazy {
        PlaybackReachabilityManager(
            context,
            database.bookDao(),
            database.libraryRootDao()
        )
    }

    override val settingsRepository: AppSettingsRepository by lazy {
        AppSettingsRepository.getInstance(context)
    }

    /**
     * 延迟实例化注销书库根目录用例，通过构造注入同时关联媒体播放与底层数据源仓储。
     */
    /**
     * 详尽的中文注释：延迟实例化注销书库根目录用例。
     * 向其直接注入播放管理器、书籍查询网关和书库根管理网关，彻底消除了对旧仓库的直接依赖。
     */
    override val deleteLibraryRootUseCase: DeleteLibraryRootUseCase by lazy {
        DeleteLibraryRootUseCase(
            playbackManager = com.viel.aplayer.media.PlaybackManager.getInstance(context),
            bookQueryGateway = bookQueryGateway,
            libraryRootGateway = libraryRootGateway
        )
    }

    // 详尽的中文注释：延迟实例化物理封面图沙盒物理提取及裁剪器单例以直接提供封面处理能力
    private val coverExtractor: CoverExtractor by lazy {
        CoverExtractor(context.applicationContext)
    }

    // 详尽的中文注释：延迟实例化多媒体音频物理元数据标签解析提取器单例，注入运行期 VFS 单例以避免 MetadataResolver 自行获取 AppDatabase
    private val metadataResolver: MetadataResolver by lazy {
        MetadataResolver(vfsFileInterface)
    }

    // 详尽的中文注释：延迟实例化有声书详情物理授权及可达性验证器单例以直接提供详情可用性判断
    private val detailAvailabilityChecker: DetailAvailabilityChecker by lazy {
        DetailAvailabilityChecker(context.applicationContext)
    }

    // 详尽的中文注释：延迟实例化音频分轨单文件可用性物理验证器单例以直接提供单轨可用性探测
    private val availabilityChecker: AvailabilityChecker by lazy {
        AvailabilityChecker(context.applicationContext)
    }

    // 详尽的中文注释：延迟实例化字幕定位与流式 VFS 检索解析门面单例以直接提供字幕检索与解析
    private val subtitleFileResolver: SubtitleFileResolver by lazy {
        SubtitleFileResolver(
            context = context.applicationContext,
            bookDao = database.bookDao(),
            fileReader = vfsFileInterface
        )
    }

    // 详尽的中文注释：延迟实例化封面物理丢失自动重构与自愈助手单例。
    // 在内部创建专属的后台 IO 协程作用域以防主线程卡死，并注入共享的虚拟文件系统接口。
    private val coverRecoveryHelper: CoverRecoveryHelper by lazy {
        CoverRecoveryHelper(
            context = context.applicationContext,
            bookDao = database.bookDao(),
            libraryRootDao = database.libraryRootDao(),
            coverExtractor = coverExtractor,
            scope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
            fileReader = vfsFileInterface
        )
    }

    /**
     * 详尽的中文注释：延迟初始化有声书及章节书签只读只写网关服务。
     * 直接向其注入所需的数据库各个精细 DAO 接口与全局封面丢失自愈助手单例，解耦对旧有上帝类仓库与物理文件解析器的依赖。
     */
    override val bookQueryGateway: BookQueryGateway by lazy {
        BookQueryService(
            bookDao = database.bookDao(),
            chapterDao = database.chapterDao(),
            bookmarkDao = database.bookmarkDao(),
            scanSessionDao = database.scanSessionDao(),
            coverRecoveryHelper = coverRecoveryHelper
        )
    }

    /**
     * 详尽的中文注释：延迟初始化播放位置与进度落库服务网关。
     * 向其直接注入 database.bookDao() 以及就绪自愈管理器，解耦对旧有 PlaybackHistoryRepository 仓库的直接依赖。
     */
    override val progressGateway: ProgressGateway by lazy {
        ProgressService(
            bookDao = database.bookDao(),
            reachabilityManager = playbackReachabilityManager
        )
    }

    /**
     * 详尽的中文注释：延迟初始化前后台物理重扫与扫描调度网关服务。
     * 直接向其注入 applicationContext 与全局封面丢失自愈助手，消除对旧有 BookLibraryRepository 的直接依赖。
     */
    override val scanScheduler: ScanScheduler by lazy {
        ScanService(
            context = context,
            coverRecoveryHelper = coverRecoveryHelper,
            vfsFileInterface = vfsFileInterface
        )
    }

    // 详尽的中文注释：延迟实例化新创建的书库根目录网关。
    // 向其直接注入 DAO 接口与扫描调度器，彻底避免了旧上帝仓库的薄适配委托。
    override val libraryRootGateway: LibraryRootGateway by lazy {
        LibraryRootService(
            context = context,
            libraryRootDao = database.libraryRootDao(),
            bookDao = database.bookDao(),
            scanScheduler = scanScheduler
        )
    }

    /**
     * 详尽的中文注释：延迟实例化新创建的封面网关服务。
     * 直接向其注入 bookDao、chapterDao 接口以及六个精细化的解耦物理处理与自愈单例，彻底摆脱了对旧上帝仓库的薄适配委托。
     */
    override val coverGateway: CoverGateway by lazy {
        CoverService(
            bookDao = database.bookDao(),
            chapterDao = database.chapterDao(),
            coverRecoveryHelper = coverRecoveryHelper,
            coverExtractor = coverExtractor,
            metadataResolver = metadataResolver,
            subtitleResolver = subtitleFileResolver,
            detailAvailabilityChecker = detailAvailabilityChecker,
            availabilityChecker = availabilityChecker
        )
    }

    /**
     * 详尽的中文注释：延迟实例化新创建的搜索历史网关服务。
     * 直接向其注入检索历史存储 DataStore 门面，彻底消除了对旧上帝仓库的直接依赖。
     */
    override val searchHistoryGateway: SearchHistoryGateway by lazy {
        SearchService(
            searchHistoryStore = com.viel.aplayer.data.store.SearchHistoryStore.getInstance(context)
        )
    }

    /**
     * 详尽的中文注释：聚合新高层媒体库业务门面组件。
     * 直接向其组合式注入六大精细化分域 Gateway 网关，无需再传递任何 legacy 废弃仓库依赖，达成终极物理重构。
     */
    override val libraryFacade: LibraryFacade by lazy {
        LibraryFacade(
            bookQueryGateway = bookQueryGateway,
            progressGateway = progressGateway,
            scanScheduler = scanScheduler,
            libraryRootGateway = libraryRootGateway,
            coverGateway = coverGateway,
            searchHistoryGateway = searchHistoryGateway
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