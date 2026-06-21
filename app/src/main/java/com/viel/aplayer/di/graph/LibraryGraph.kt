package com.viel.aplayer.di.graph

import android.content.Context
import androidx.media3.common.util.UnstableApi
import com.viel.aplayer.abs.sync.AbsCoverStore
import com.viel.aplayer.application.download.ManualDownloadCleanupGateway
import com.viel.aplayer.application.library.detail.DefaultDetailBookModule
import com.viel.aplayer.application.library.detail.DetailBookCommands
import com.viel.aplayer.application.library.detail.DetailBookReadModel
import com.viel.aplayer.application.library.edit.DefaultEditBookModule
import com.viel.aplayer.application.library.edit.EditBookCommands
import com.viel.aplayer.application.library.edit.EditBookReadModel
import com.viel.aplayer.application.library.home.DefaultHomeLibraryReadModel
import com.viel.aplayer.application.library.home.DefaultHomeLibraryUseCases
import com.viel.aplayer.application.library.home.HomeLibraryReadModel
import com.viel.aplayer.application.library.home.HomeLibraryUseCases
import com.viel.aplayer.application.library.player.DefaultPlayerLibraryModule
import com.viel.aplayer.application.library.player.PlayerBookmarkCommands
import com.viel.aplayer.application.library.player.PlayerLibraryReadModel
import com.viel.aplayer.application.library.recovery.DefaultDeletedBookRecoveryModule
import com.viel.aplayer.application.library.recovery.DeletedBookRecoveryCommands
import com.viel.aplayer.application.library.recovery.DeletedBookRecoveryReadModel
import com.viel.aplayer.application.library.search.DefaultSearchLibraryModule
import com.viel.aplayer.application.library.search.SearchLibraryCommands
import com.viel.aplayer.application.library.search.SearchLibraryReadModel
import com.viel.aplayer.application.library.search.SearchQueryPlanner
import com.viel.aplayer.application.usecase.BookManagementUseCase
import com.viel.aplayer.application.usecase.BuildPlaybackPlanUseCase
import com.viel.aplayer.application.usecase.LibraryRootManagementUseCase
import com.viel.aplayer.data.availability.BookAvailabilityGateway
import com.viel.aplayer.data.availability.BookAvailabilityGatewayImpl
import com.viel.aplayer.data.book.BookCatalogGateway
import com.viel.aplayer.data.book.BookCatalogGatewayImpl
import com.viel.aplayer.data.book.BookDeletionGateway
import com.viel.aplayer.data.book.BookDeletionGatewayImpl
import com.viel.aplayer.data.book.BookMetadataGateway
import com.viel.aplayer.data.book.BookMetadataGatewayImpl
import com.viel.aplayer.data.book.BookRootInventoryGateway
import com.viel.aplayer.data.book.BookRootInventoryGatewayImpl
import com.viel.aplayer.data.book.BookmarkGateway
import com.viel.aplayer.data.book.BookmarkGatewayImpl
import com.viel.aplayer.data.book.ChapterGateway
import com.viel.aplayer.data.book.ChapterGatewayImpl
import com.viel.aplayer.data.cache.CacheEvictionCoordinator
import com.viel.aplayer.data.cleanup.LibraryResourceCleanupGateway
import com.viel.aplayer.data.cleanup.RemotePlaybackCleanupGatewayImpl
import com.viel.aplayer.data.cover.AndroidCoverUriResolver
import com.viel.aplayer.data.cover.CoverAssetGateway
import com.viel.aplayer.data.cover.CoverAssetGatewayImpl
import com.viel.aplayer.data.cover.CoverRecoveryGateway
import com.viel.aplayer.data.cover.CoverRecoveryGatewayImpl
import com.viel.aplayer.data.cover.CoverRecoveryHelper
import com.viel.aplayer.data.cover.CoverUriResolver
import com.viel.aplayer.data.metadata.MetadataRefreshGateway
import com.viel.aplayer.data.metadata.MetadataRefreshGatewayImpl
import com.viel.aplayer.data.progress.ProgressGateway
import com.viel.aplayer.data.progress.ProgressGatewayImpl
import com.viel.aplayer.data.root.LibraryRootGateway
import com.viel.aplayer.data.root.LibraryRootGatewayImpl
import com.viel.aplayer.data.scan.ScanScheduler
import com.viel.aplayer.data.scan.ScanSchedulerImpl
import com.viel.aplayer.data.search.SearchHistoryGateway
import com.viel.aplayer.data.search.SearchHistoryGatewayImpl
import com.viel.aplayer.data.subtitle.SubtitleGateway
import com.viel.aplayer.data.subtitle.SubtitleGatewayImpl
import com.viel.aplayer.library.availability.AvailabilityChecker
import com.viel.aplayer.media.PlaybackPlanGateway
import com.viel.aplayer.media.PlaybackPlanGatewayImpl
import com.viel.aplayer.media.parser.CoverExtractor
import com.viel.aplayer.media.parser.MetadataResolver
import com.viel.aplayer.media.subtitle.SubtitleFileResolver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.Closeable

/**
 * Owns local library queries, scan scheduling, metadata, cover, and deletion use cases.
 * Gives UI and playback callers stable library-facing adapters while keeping implementation construction local.
 */
@UnstableApi
internal class LibraryGraph(
    val context: Context,
    val data: DataGraph,
    val media: MediaGraph,
    val uiEvents: UiEventGraph,
    private val manualDownloadCleanupGatewayProvider: () -> ManualDownloadCleanupGateway,
    private val absCoverStoreProvider: () -> AbsCoverStore?
) : Closeable {
    val coverExtractor: CoverExtractor by lazy {
        CoverExtractor(context.applicationContext)
    }

    val metadataResolver: MetadataResolver by lazy {
        MetadataResolver(media.vfsFileInterface)
    }

    val availabilityChecker: AvailabilityChecker by lazy {
        AvailabilityChecker(context.applicationContext)
    }

    val bookAvailabilityGatewayImpl: BookAvailabilityGatewayImpl by lazy {
        BookAvailabilityGatewayImpl(
            bookDao = data.database.bookDao(),
            libraryRootDao = data.database.libraryRootDao(),
            availabilityChecker = availabilityChecker
        )
    }

    val subtitleFileResolver: SubtitleFileResolver by lazy {
        SubtitleFileResolver(
            context = context.applicationContext,
            bookDao = data.database.bookDao(),
            fileReader = media.vfsFileInterface
        )
    }

    val coverUriResolver: CoverUriResolver by lazy {
        AndroidCoverUriResolver(context.applicationContext)
    }

    var coverRecoveryScope: CoroutineScope? = null
        private set

    val coverRecoveryHelper: CoverRecoveryHelper by lazy {
        val s = CoroutineScope(Dispatchers.IO + SupervisorJob())
        coverRecoveryScope = s
        CoverRecoveryHelper(
            bookDao = data.database.bookDao(),
            libraryRootDao = data.database.libraryRootDao(),
            coverExtractor = coverExtractor,
            scope = s,
            fileReader = media.vfsFileInterface,
            absItemMirrorDao = data.database.absItemMirrorDao(),
            absCoverStoreProvider = absCoverStoreProvider
        )
    }

    /**
     * One implementation per capability seam.
     * BookQueryService was decomposed into capability-scoped services so each gateway accessor returns its
     * own implementation. Only the metadata and chapter services own background scopes and need teardown.
     */
    private val bookCatalogGatewayLazy = lazy {
        BookCatalogGatewayImpl(
            bookDao = data.database.bookDao(),
            coverRecoveryGateway = coverRecoveryGatewayLazy.value
        )
    }

    private val bookMetadataGatewayLazy = lazy {
        BookMetadataGatewayImpl(bookDao = data.database.bookDao())
    }

    /**
     * Batch self-heal seam for the home cold-start sweep.
     * Reuses the shared CoverRecoveryHelper background scope so the deferred sweep only adds a catalog snapshot read.
     */
    private val coverRecoveryGatewayLazy: Lazy<CoverRecoveryGateway> = lazy {
        CoverRecoveryGatewayImpl(
            bookDao = data.database.bookDao(),
            coverSelfHealer = coverRecoveryHelper
        )
    }

    private val bookmarkGatewayLazy = lazy {
        BookmarkGatewayImpl(
            bookDao = data.database.bookDao(),
            bookmarkDao = data.database.bookmarkDao()
        )
    }

    private val chapterGatewayLazy = lazy {
        ChapterGatewayImpl(
            bookDao = data.database.bookDao(),
            chapterDao = data.database.chapterDao()
        )
    }

    private val bookDeletionGatewayLazy = lazy {
        BookDeletionGatewayImpl(bookDao = data.database.bookDao())
    }

    private val bookRootInventoryGatewayLazy = lazy {
        BookRootInventoryGatewayImpl(bookDao = data.database.bookDao())
    }

    val bookCatalogGateway: BookCatalogGateway
        get() = bookCatalogGatewayLazy.value

    val bookMetadataGateway: BookMetadataGateway
        get() = bookMetadataGatewayLazy.value

    val bookmarkGateway: BookmarkGateway
        get() = bookmarkGatewayLazy.value

    val chapterGateway: ChapterGateway
        get() = chapterGatewayLazy.value

    val bookDeletionGateway: BookDeletionGateway
        get() = bookDeletionGatewayLazy.value

    val bookRootInventoryGateway: BookRootInventoryGateway
        get() = bookRootInventoryGatewayLazy.value

    val remotePlaybackCleanupGateway by lazy {
        RemotePlaybackCleanupGatewayImpl(
            absPlaybackSessionDao = data.database.absPlaybackSessionDao(),
            absPendingProgressSyncDao = data.database.absPendingProgressSyncDao()
        )
    }

    val playbackPlanGateway: PlaybackPlanGateway by lazy {
        PlaybackPlanGatewayImpl(
            coverUriResolver = coverUriResolver,
            bookDao = data.database.bookDao(),
            coverRecoveryHelper = coverRecoveryHelper
        )
    }

    val buildPlaybackPlanUseCase: BuildPlaybackPlanUseCase by lazy {
        BuildPlaybackPlanUseCase(playbackPlanGateway)
    }

    private val progressGatewayLazy = lazy {
        ProgressGatewayImpl(
            bookDao = data.database.bookDao()
        )
    }

    /**
     * Keeps public di API unchanged while retaining lazy lifecycle metadata.
     * Teardown can now close the service only after a runtime caller has resolved this gateway.
     */
    val progressGateway: ProgressGateway
        get() = progressGatewayLazy.value

    private val scanSchedulerLazy = lazy {
        ScanSchedulerImpl(
            context = context,
            coverRecoveryGateway = coverRecoveryGatewayLazy.value,
            vfsFileInterface = media.vfsFileInterface,
            directoryListingCache = media.directoryListingCache,
            appEventSink = uiEvents.appEventSink
        )
    }

    /**
     * Keeps scanner dependency resolution lazy and observable by teardown.
     * This prevents shutdown from creating WorkManager and scanner dependencies solely to close them.
     */
    val scanScheduler: ScanScheduler
        get() = scanSchedulerLazy.value

    val cacheEvictionCoordinator by lazy {
        CacheEvictionCoordinator(
            context = context.applicationContext,
            bookDao = data.database.bookDao(),
            directoryCacheDao = data.database.directoryCacheDao(),
            directoryChildCacheDao = data.database.directoryChildCacheDao(),
            vfsRangeCache = media.vfsRangeCache,
            /**
             * Wire VfsFileInterface: Pass the process-wide VFS reader to support invalidating in-memory library root configurations.
             */
            vfsFileInterface = media.vfsFileInterface
        )
    }

    /**
     * Expose derived-cache cleanup through a narrow seam.
     * Management use cases coordinate cleanup timing while CacheEvictionCoordinator keeps ownership of file-path deletion logic.
     */
    val libraryResourceCleanupGateway: LibraryResourceCleanupGateway
        get() = cacheEvictionCoordinator

    private val libraryRootGatewayLazy = lazy {
        LibraryRootGatewayImpl(
            context = context,
            libraryRootDao = data.database.libraryRootDao(),
            bookDao = data.database.bookDao(),
            scanScheduler = scanScheduler,
            cacheEvictionCoordinator = cacheEvictionCoordinator,
            databaseOverride = data.database
        )
    }

    /**
     * Preserves lazy root-service construction with explicit lifecycle ownership.
     * The backing Lazy lets close() skip root-store collectors when root management was never initialized.
     */
    val libraryRootGateway: LibraryRootGateway
        get() = libraryRootGatewayLazy.value

    val coverAssetGateway: CoverAssetGateway by lazy {
        CoverAssetGatewayImpl(
            bookDao = data.database.bookDao(),
            coverExtractor = coverExtractor
        )
    }

    val metadataRefreshGateway: MetadataRefreshGateway by lazy {
        MetadataRefreshGatewayImpl(
            bookDao = data.database.bookDao(),
            chapterDao = data.database.chapterDao(),
            coverRecoveryGateway = coverRecoveryGatewayLazy.value,
            metadataResolver = metadataResolver,
            database = data.database
        )
    }

    val subtitleGateway: SubtitleGateway by lazy {
        SubtitleGatewayImpl(
            subtitleResolver = subtitleFileResolver
        )
    }

    private val searchHistoryGatewayLazy = lazy {
        SearchHistoryGatewayImpl(
            searchHistoryStore = data.searchHistoryStore
        )
    }

    /**
     * Keeps search history storage behind lazy di construction.
     * The explicit Lazy backing keeps future closeable behavior visible to di lifecycle tests.
     */
    val searchHistoryGateway: SearchHistoryGateway
        get() = searchHistoryGatewayLazy.value

    private val searchLibraryModule: DefaultSearchLibraryModule by lazy {
        DefaultSearchLibraryModule(
            searchHistoryGateway = searchHistoryGateway,
            queryPlanner = SearchQueryPlanner.from(bookCatalogGateway)
        )
    }

    val searchLibraryReadModel: SearchLibraryReadModel
        get() = searchLibraryModule

    val searchLibraryCommands: SearchLibraryCommands
        get() = searchLibraryModule

    private val detailBookModule: DefaultDetailBookModule by lazy {
        DefaultDetailBookModule(
            bookCatalogGateway = bookCatalogGateway,
            bookAvailabilityGateway = bookAvailabilityGatewayImpl,
            libraryRootGateway = libraryRootGateway
        )
    }

    val detailBookReadModel: DetailBookReadModel
        get() = detailBookModule

    val detailBookCommands: DetailBookCommands
        get() = detailBookModule

    private val playerLibraryModule: DefaultPlayerLibraryModule by lazy {
        DefaultPlayerLibraryModule(
            bookCatalogGateway = bookCatalogGateway,
            chapterGateway = chapterGateway,
            bookmarkGateway = bookmarkGateway,
            bookAvailabilityGateway = bookAvailabilityGatewayImpl,
            progressGateway = progressGateway,
            subtitleGateway = subtitleGateway
        )
    }

    val playerLibraryReadModel: PlayerLibraryReadModel
        get() = playerLibraryModule

    val playerBookmarkCommands: PlayerBookmarkCommands
        get() = playerLibraryModule

    private val editBookModule: DefaultEditBookModule by lazy {
        DefaultEditBookModule(
            bookCatalogGateway = bookCatalogGateway,
            bookMetadataGateway = bookMetadataGateway,
            coverAssetGateway = coverAssetGateway
        )
    }

    val editBookReadModel: EditBookReadModel
        get() = editBookModule

    val editBookCommands: EditBookCommands
        get() = editBookModule

    val homeLibraryReadModel: HomeLibraryReadModel by lazy {
        DefaultHomeLibraryReadModel(
            bookCatalogGateway = bookCatalogGateway,
            libraryRootGateway = libraryRootGateway
        )
    }

    val homeLibraryUseCases: HomeLibraryUseCases by lazy {
        DefaultHomeLibraryUseCases(
            bookMetadataGateway = bookMetadataGateway,
            scanScheduler = scanScheduler,
            libraryRootGateway = libraryRootGateway,
            metadataRefreshGateway = metadataRefreshGateway,
            searchHistoryGateway = searchHistoryGateway,
            coverRecoveryGateway = coverRecoveryGatewayLazy.value
        )
    }

    private val deletedBookRecoveryModule: DefaultDeletedBookRecoveryModule by lazy {
        DefaultDeletedBookRecoveryModule(
            bookDao = data.database.bookDao(),
            libraryRootDao = data.database.libraryRootDao(),
            absItemMirrorDao = data.database.absItemMirrorDao(),
            availabilityChecker = availabilityChecker
        )
    }

    val deletedBookRecoveryReadModel: DeletedBookRecoveryReadModel
        get() = deletedBookRecoveryModule

    val deletedBookRecoveryCommands: DeletedBookRecoveryCommands
        get() = deletedBookRecoveryModule

    val libraryRootManagementUseCase: LibraryRootManagementUseCase by lazy {
        LibraryRootManagementUseCase(
            playbackStopper = media.playbackStopper,
            bookCatalogGateway = bookCatalogGateway,
            bookRootInventoryGateway = bookRootInventoryGateway,
            libraryRootGateway = libraryRootGateway,
            manualDownloadCleanupGateway = manualDownloadCleanupGatewayProvider(),
            libraryResourceCleanupGateway = libraryResourceCleanupGateway
        )
    }

    val bookManagementUseCase: BookManagementUseCase by lazy {
        BookManagementUseCase(
            playbackStopper = media.playbackStopper,
            bookAvailabilityGateway = bookAvailabilityGatewayImpl,
            bookDeletionGateway = bookDeletionGateway,
            remotePlaybackCleanupGateway = remotePlaybackCleanupGateway,
            manualDownloadCleanupGateway = manualDownloadCleanupGatewayProvider()
        )
    }

    override fun close() {
        closeInitializedLibraryGraphResources(
            closeableResources = listOf(
                bookMetadataGatewayLazy,
                chapterGatewayLazy,
                progressGatewayLazy,
                scanSchedulerLazy,
                libraryRootGatewayLazy,
                searchHistoryGatewayLazy
            ),
            recoveryScope = coverRecoveryScope
        )
    }

    val bookAvailabilityGateway: BookAvailabilityGateway
        get() = bookAvailabilityGatewayImpl
}
