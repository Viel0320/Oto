package com.viel.aplayer

import android.content.Context
import androidx.media3.common.util.UnstableApi
import com.viel.aplayer.abs.auth.AbsCredentialStore
import com.viel.aplayer.abs.net.RealAbsApiClient
import com.viel.aplayer.abs.playback.AbsPlaybackCredentialResolver
import com.viel.aplayer.abs.playback.AbsPlaybackSessionSyncer
import com.viel.aplayer.abs.playback.AbsProgressConflictCoordinator
import com.viel.aplayer.abs.sync.AbsCatalogSynchronizer
import com.viel.aplayer.abs.sync.AbsCoverCache
import com.viel.aplayer.abs.sync.AbsSyncTaskCoordinator
import com.viel.aplayer.data.AppSettingsRepository
import com.viel.aplayer.data.LibraryFacade
import com.viel.aplayer.data.cache.CacheEvictionCoordinator
import com.viel.aplayer.data.db.AppDatabase
import com.viel.aplayer.data.gateway.BookQueryGateway
import com.viel.aplayer.data.gateway.CoverGateway
import com.viel.aplayer.data.gateway.LibraryRootGateway
import com.viel.aplayer.data.gateway.ProgressGateway
import com.viel.aplayer.data.gateway.ScanScheduler
import com.viel.aplayer.data.gateway.SearchHistoryGateway
import com.viel.aplayer.data.service.BookQueryService
import com.viel.aplayer.data.service.CoverService
import com.viel.aplayer.data.service.LibraryRootService
import com.viel.aplayer.data.service.ProgressService
import com.viel.aplayer.data.service.ScanService
import com.viel.aplayer.data.service.SearchService
import com.viel.aplayer.data.usecase.DeleteLibraryRootUseCase
import com.viel.aplayer.library.availability.AvailabilityChecker
import com.viel.aplayer.library.availability.DetailAvailabilityChecker
import com.viel.aplayer.library.availability.PlaybackReachabilityManager
import com.viel.aplayer.library.vfs.VfsFileInterface
import com.viel.aplayer.library.vfs.cache.RoomDirectoryListingCache
import com.viel.aplayer.library.vfs.cache.VfsRangeCache
import com.viel.aplayer.media.PlaybackFileLookup
import com.viel.aplayer.media.parser.CoverExtractor
import com.viel.aplayer.media.parser.CoverRecoveryHelper
import com.viel.aplayer.media.parser.MetadataResolver
import com.viel.aplayer.media.subtitle.SubtitleFileResolver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Global Dependency Container (Manages lifecycle and instantiation of core gateway services)
 * Serves as a lightweight DI provider for unified coordination across application domains.
 */
interface AppContainer : java.io.Closeable {
    val settingsRepository: AppSettingsRepository
    
    /**
     * Cross-Domain Use Case (Unregister and purge library root coordinates)
     * Coordinates active playback teardown and background physical data cleanup.
     */
    val deleteLibraryRootUseCase: DeleteLibraryRootUseCase

    /**
     * High-Level Library Facade (Unified entry point aggregating core domain services)
     * Simplifies routing to book query, progress database syncing, and scanning sub-systems.
     */
    val libraryFacade: LibraryFacade

    /**
     * Book Query Gateway (Read-write router for audiobook queries and bookmarks)
     */
    val bookQueryGateway: BookQueryGateway

    /**
     * Progress Sync Gateway (State router for playback position and audio file status)
     */
    val progressGateway: ProgressGateway

    /**
     * Media Scanner Gateway (Scheduler interface for triggering library updates)
     */
    val scanScheduler: ScanScheduler

    /**
     * Library Root Gateway (Maintenance interface for directory registrations)
     */
    val libraryRootGateway: LibraryRootGateway

    /**
     * Cover Metadata Gateway (Domain router for image extraction and backdrop color caching)
     */
    val coverGateway: CoverGateway

    /**
     * Search History Gateway (Persistence management for keyword lookup logs)
     */
    val searchHistoryGateway: SearchHistoryGateway

    /**
     * Virtual File System Interface (Single I/O access point for file path abstractions)
     */
    val vfsFileInterface: VfsFileInterface

    /**
     * Playback File Resolver (Lookup utility to associate media IDs with physical files)
     */
    val playbackFileLookup: PlaybackFileLookup

    /**
     * Playback Manager Instance (Singleton registry for media controller coordination)
     * Promotes decoupled architecture and simplifies component isolation during unit testing.
     */
    val playbackManager: com.viel.aplayer.media.PlaybackManager

    /**
     * Search History Storage (Singleton reference to search term DataStore backend)
     */
    val searchHistoryStore: com.viel.aplayer.data.store.SearchHistoryStore

    /**
     * Auto Rewind Controller (Singleton manager tracking playback progress self-healing)
     */
    val autoRewindManager: com.viel.aplayer.media.AutoRewindManager

    /**
     * ABS Catalog Synchronizer (Dedicated mirror processor for Audiobookshelf servers)
     * Kept separate from LibraryFacade to prevent remote REST details leaking into the local domain.
     */
    val absCatalogSynchronizer: AbsCatalogSynchronizer

    /**
     * ABS Playback Session Syncer (Coordinator for remote server progress handshakes)
     * Restricts operations to play/sync/close events, keeping local database records as the source of truth.
     */
    val absPlaybackSessionSyncer: AbsPlaybackSessionSyncer

    /**
     * ABS Progress Conflict Coordinator (Remote/local progress arbitration service)
     * Provides playback prompts and upload guards without leaking ABS protocol details into generic progress services.
     */
    val absProgressConflictCoordinator: AbsProgressConflictCoordinator

    /**
     * Application-Level ABS Sync Coordinator (Coordinates background server synchronization)
     * Ensures sync events persist beyond SettingsActivity lifecycle, preventing unexpected interruptions.
     */
    val absSyncTaskCoordinator: AbsSyncTaskCoordinator
}

@UnstableApi
class DefaultAppContainer(private val context: Context) : AppContainer {

    private val database: AppDatabase by lazy {
        AppDatabase.getInstance(context)
    }

    override val playbackManager: com.viel.aplayer.media.PlaybackManager by lazy {
        com.viel.aplayer.media.PlaybackManager.getInstance(context)
    }

    override val searchHistoryStore: com.viel.aplayer.data.store.SearchHistoryStore by lazy {
        com.viel.aplayer.data.store.SearchHistoryStore.getInstance(context)
    }

    override val autoRewindManager: com.viel.aplayer.media.AutoRewindManager by lazy {
        com.viel.aplayer.media.AutoRewindManager.getInstance(context)
    }

    private val absCredentialStore by lazy {
        AbsCredentialStore.getInstance(context.applicationContext)
    }

    private val absApiClient by lazy {
        RealAbsApiClient()
    }

    private val absCoverCache by lazy {
        AbsCoverCache(context.applicationContext)
    }

    // Scanner Directory Listing Cache (Lazily creates the Room-backed WebDAV child snapshot cache)
    // Injected only into ScanService so playback, metadata reading, and availability checks keep direct provider behavior.
    private val directoryListingCache by lazy {
        RoomDirectoryListingCache(
            directoryChildCacheDao = database.directoryChildCacheDao()
        )
    }

    // Metadata Range Cache (Stores only bounded readRange blocks for metadata and cover extraction)
    // This cache is injected into VfsFileInterface and never into playback stream data sources, preserving provider-owned seek behavior.
    private val vfsRangeCache by lazy {
        VfsRangeCache(context.applicationContext)
    }

    // Root Cache Eviction Coordinator (Lazily creates data-domain cleanup for root deletion)
    // Clears cover files and directory cache rows before Room cascades remove source records, without owning playback or scan behavior.
    private val cacheEvictionCoordinator by lazy {
        CacheEvictionCoordinator(
            context = context.applicationContext,
            bookDao = database.bookDao(),
            directoryCacheDao = database.directoryCacheDao(),
            directoryChildCacheDao = database.directoryChildCacheDao(),
            vfsRangeCache = vfsRangeCache
        )
    }

    private val absPlaybackCredentialResolver by lazy {
        AbsPlaybackCredentialResolver(
            libraryRootDao = database.libraryRootDao(),
            credentialStore = absCredentialStore
        )
    }

    // Playback Reachability Monitor (Lazily instantiates the reachability controller to verify tracks and handle skips)
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
     * Root Library Deletion Service (Lazily instantiates root deletion orchestrator)
     * Injects PlaybackManager, BookQueryGateway, and LibraryRootGateway, removing legacy repository dependencies.
     */
    override val deleteLibraryRootUseCase: DeleteLibraryRootUseCase by lazy {
        DeleteLibraryRootUseCase(
            playbackManager = playbackManager,
            bookQueryGateway = bookQueryGateway,
            libraryRootGateway = libraryRootGateway
        )
    }

    // Sandboxed Cover Extractor (Lazily instantiates helper to parse cover art and apply graphics crops)
    private val coverExtractor: CoverExtractor by lazy {
        CoverExtractor(context.applicationContext)
    }

    // Audio Metadata Resolver (Lazily instantiates tag parser, injecting the VFS instance to avoid direct DB accesses)
    private val metadataResolver: MetadataResolver by lazy {
        MetadataResolver(vfsFileInterface)
    }

    // Detail Availability Verifier (Lazily instantiates component verifying audiobook accessibility)
    private val detailAvailabilityChecker: DetailAvailabilityChecker by lazy {
        DetailAvailabilityChecker(context.applicationContext)
    }

    // Single Track Checker (Lazily instantiates reachability checker for individual media files)
    private val availabilityChecker: AvailabilityChecker by lazy {
        AvailabilityChecker(context.applicationContext)
    }

    // Subtitle Resolver Facade (Lazily instantiates subtitle locator and file reader utility)
    private val subtitleFileResolver: SubtitleFileResolver by lazy {
        SubtitleFileResolver(
            context = context.applicationContext,
            bookDao = database.bookDao(),
            fileReader = vfsFileInterface
        )
    }

    // Cover Self-Healing Agent (Lazily instantiates the self-healing utility restoring missing covers)
    // Creates a dedicated IO CoroutineScope internally to prevent blocking UI main threads.
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
     * Book Query Service (Lazily instantiates the read-write gateway service for audiobooks)
     * Injects specific DAO interfaces and the cover self-healing helper, decoupling legacy repository logic.
     */
    override val bookQueryGateway: BookQueryGateway by lazy {
        // Safe Content URI Resolution (Inject the global ApplicationContext into BookQueryService constructor)
        // Enables FileProvider to map absolute cached image paths to content:// schemes when constructing PlaybackPlans.
        BookQueryService(
            context = context.applicationContext,
            bookDao = database.bookDao(),
            chapterDao = database.chapterDao(),
            bookmarkDao = database.bookmarkDao(),
            scanSessionDao = database.scanSessionDao(),
            coverRecoveryHelper = coverRecoveryHelper
        )
    }

    /**
     * Playback Progress Service (Lazily instantiates progress storage gateway)
     * Injects database.bookDao() and the reachability manager to bypass legacy history repository dependencies.
     */
    override val progressGateway: ProgressGateway by lazy {
        ProgressService(
            bookDao = database.bookDao(),
            reachabilityManager = playbackReachabilityManager
        )
    }

    /**
     * Background Scanner Service (Lazily instantiates background media scanner service)
     * Injects context, cover healing agent, and PlaybackManager bus, replacing legacy library repositories.
     */
    override val scanScheduler: ScanScheduler by lazy {
        ScanService(
            context = context,
            coverRecoveryHelper = coverRecoveryHelper,
            vfsFileInterface = vfsFileInterface,
            directoryListingCache = directoryListingCache,
            playbackManager = playbackManager
        )
    }

    // Library Root Service (Lazily instantiates root directory maintenance gateway)
    // Injects database DAOs and ScanScheduler, avoiding thin delegator patterns in legacy repositories.
    override val libraryRootGateway: LibraryRootGateway by lazy {
        LibraryRootService(
            context = context,
            libraryRootDao = database.libraryRootDao(),
            bookDao = database.bookDao(),
            scanScheduler = scanScheduler,
            cacheEvictionCoordinator = cacheEvictionCoordinator
        )
    }

    /**
     * Cover Service Orchestrator (Lazily instantiates covers and subtitles resolving gateway)
     * Aggregates bookDao, chapterDao, and the six decoupled helper singletons to clean up legacy dependencies.
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
            availabilityChecker = availabilityChecker,
            database = database
        )
    }

    /**
     * Search History Service (Lazily instantiates search queries storage gateway)
     * Injects SearchHistoryStore directly, decoupling search logic from the legacy repository.
     */
    override val searchHistoryGateway: SearchHistoryGateway by lazy {
        SearchService(
            searchHistoryStore = searchHistoryStore
        )
    }

    /**
     * Unified Media Library Facade (Composes the six domain-specific gateways under one unified facade)
     * Avoids passing any legacy repositories, completing the decoupling of core storage operations.
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
     * Virtual File System Channel (Lazily instantiates the VfsFileInterface singleton)
     * Serves as the single channel for virtual audio path mapping across playback and scan workflows.
     */
    override val vfsFileInterface: VfsFileInterface by lazy {
        VfsFileInterface(
            context.applicationContext,
            libraryRootDao = database.libraryRootDao(),
            rangeCache = vfsRangeCache
        )
    }

    /**
     * Playback File Lookup Service (Lazily instantiates default database lookup service)
     */
    override val playbackFileLookup: PlaybackFileLookup by lazy {
        com.viel.aplayer.media.DefaultPlaybackFileLookup(
            database.bookDao()
        )
    }

    override val absCatalogSynchronizer: AbsCatalogSynchronizer by lazy {
        AbsCatalogSynchronizer(
            apiClient = absApiClient,
            credentialStore = absCredentialStore,
            catalogStore = database.absCatalogDao(),
            coverCache = absCoverCache
        )
    }

    override val absProgressConflictCoordinator: AbsProgressConflictCoordinator by lazy {
        AbsProgressConflictCoordinator(
            apiClient = absApiClient,
            bookQueryGateway = bookQueryGateway,
            progressGateway = progressGateway,
            credentialProvider = { book -> absPlaybackCredentialResolver.resolve(book) }
        )
    }

    override val absPlaybackSessionSyncer: AbsPlaybackSessionSyncer by lazy {
        AbsPlaybackSessionSyncer(
            apiClient = absApiClient,
            absPlaybackSessionDao = database.absPlaybackSessionDao(),
            absPendingProgressSyncDao = database.absPendingProgressSyncDao(),
            catalogStore = database.absCatalogDao(),
            credentialProvider = { book -> absPlaybackCredentialResolver.resolve(book) },
            progressConflictCoordinator = absProgressConflictCoordinator
        )
    }

    override val absSyncTaskCoordinator: AbsSyncTaskCoordinator by lazy {
        // Decoupled Background Toast Dispatcher (Inject PlaybackManager into AbsSyncTaskCoordinator to handle toast events)
        // Dispatches UiEvent.ShowToast via the global event bus, eliminating direct dependencies on UI Activity contexts.
        AbsSyncTaskCoordinator(
            libraryRootDao = database.libraryRootDao(),
            synchronizer = absCatalogSynchronizer,
            playbackManager = playbackManager
        )
    }

    override fun close() {
        // Resource Cleanup Coordinator (Coordinate teardown across domain services during container release)
        // Cancels active coroutine jobs and tears down listeners on Room, VFS, and file observers to prevent memory leaks.
        (bookQueryGateway as? java.io.Closeable)?.close()
        (progressGateway as? java.io.Closeable)?.close()
        (scanScheduler as? java.io.Closeable)?.close()
        (libraryRootGateway as? java.io.Closeable)?.close()
        (coverGateway as? java.io.Closeable)?.close()
    }
}
