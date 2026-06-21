package com.viel.aplayer

import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.offline.DownloadManager
import com.viel.aplayer.abs.playback.AbsPlaybackSessionSyncer
import com.viel.aplayer.abs.sync.AbsAuthorizedProgressSynchronizer
import com.viel.aplayer.abs.sync.AbsCatalogSynchronizer
import com.viel.aplayer.abs.sync.AbsSyncTaskCoordinator
import com.viel.aplayer.application.download.CacheMaintenanceCommands
import com.viel.aplayer.application.download.CacheStatisticsProvider
import com.viel.aplayer.application.download.DownloadCacheAccess
import com.viel.aplayer.application.download.DownloadController
import com.viel.aplayer.application.download.DownloadManagementReadModel
import com.viel.aplayer.application.download.DownloadRecoveryService
import com.viel.aplayer.application.download.DownloadRuntimeGateway
import com.viel.aplayer.application.download.DownloadStatusReadModel
import com.viel.aplayer.application.download.ManualDownloadCleanupGateway
import com.viel.aplayer.application.download.ManualDownloadOrphanCleaner
import com.viel.aplayer.application.library.detail.DetailBookCommands
import com.viel.aplayer.application.library.detail.DetailBookReadModel
import com.viel.aplayer.application.library.edit.EditBookCommands
import com.viel.aplayer.application.library.edit.EditBookReadModel
import com.viel.aplayer.application.library.home.HomeLibraryReadModel
import com.viel.aplayer.application.library.home.HomeLibraryUseCases
import com.viel.aplayer.application.library.player.PlayerBookmarkCommands
import com.viel.aplayer.application.library.player.PlayerLibraryReadModel
import com.viel.aplayer.application.library.recovery.DeletedBookRecoveryCommands
import com.viel.aplayer.application.library.recovery.DeletedBookRecoveryReadModel
import com.viel.aplayer.application.library.search.SearchLibraryCommands
import com.viel.aplayer.application.library.search.SearchLibraryReadModel
import com.viel.aplayer.application.library.settings.DefaultSettingsRootModule
import com.viel.aplayer.application.library.settings.DownloadAwareAppSettingsCommands
import com.viel.aplayer.application.library.settings.SettingsRootCommands
import com.viel.aplayer.application.library.settings.SettingsRootReadModel
import com.viel.aplayer.application.playback.PlayerPlaybackController
import com.viel.aplayer.application.usecase.AbsSettingsConnectionUseCase
import com.viel.aplayer.application.usecase.BookManagementUseCase
import com.viel.aplayer.application.usecase.BuildPlaybackPlanUseCase
import com.viel.aplayer.application.usecase.ExportUserDataUseCase
import com.viel.aplayer.application.usecase.ImportUserDataUseCase
import com.viel.aplayer.application.usecase.LibraryRootManagementUseCase
import com.viel.aplayer.application.usecase.ResolveProgressConflictUseCase
import com.viel.aplayer.application.usecase.SettingsLibraryMaintenanceUseCase
import com.viel.aplayer.application.usecase.SettingsQueryUseCase
import com.viel.aplayer.application.usecase.TestWebDavConnectionUseCase
import com.viel.aplayer.data.availability.BookAvailabilityGateway
import com.viel.aplayer.data.book.BookCatalogGateway
import com.viel.aplayer.data.book.BookmarkGateway
import com.viel.aplayer.data.book.ChapterGateway
import com.viel.aplayer.data.progress.ProgressGateway
import com.viel.aplayer.data.root.LibraryRootGateway
import com.viel.aplayer.data.scan.ScanScheduler
import com.viel.aplayer.data.search.SearchHistoryGateway
import com.viel.aplayer.di.dependencies.AbsSyncWorkerDependencies
import com.viel.aplayer.di.dependencies.AppFeedbackDependencies
import com.viel.aplayer.di.dependencies.AppShellDependencies
import com.viel.aplayer.di.dependencies.DetailScreenDependencies
import com.viel.aplayer.di.dependencies.DownloadRuntimeDependencies
import com.viel.aplayer.di.dependencies.EditScreenDependencies
import com.viel.aplayer.di.dependencies.HomeScreenDependencies
import com.viel.aplayer.di.dependencies.LibrarySyncWorkerDependencies
import com.viel.aplayer.di.dependencies.ManualDownloadNotificationActionDependencies
import com.viel.aplayer.di.dependencies.PlaybackRuntimeDependencies
import com.viel.aplayer.di.dependencies.PlayerScreenDependencies
import com.viel.aplayer.di.dependencies.SearchScreenDependencies
import com.viel.aplayer.di.dependencies.SettingsScreenDependencies
import com.viel.aplayer.di.dependencies.VfsPlaybackDependencies
import com.viel.aplayer.di.graph.AbsGraph
import com.viel.aplayer.di.graph.DataGraph
import com.viel.aplayer.di.graph.DownloadGraph
import com.viel.aplayer.di.graph.LibraryGraph
import com.viel.aplayer.di.graph.MediaGraph
import com.viel.aplayer.di.graph.UiEventGraph
import com.viel.aplayer.di.graph.closeAppGraphsInLifecycleOrder
import com.viel.aplayer.event.AppEventSink
import com.viel.aplayer.library.availability.MissingBookFileRecoveryChecker
import com.viel.aplayer.library.vfs.VfsFileInterface
import com.viel.aplayer.library.vfs.sourceProvider.webdav.WebDavConnectionTester
import com.viel.aplayer.library.vfs.sourceProvider.webdav.WebDavCredentialStore
import com.viel.aplayer.media.PlaybackDomainEventSink
import com.viel.aplayer.media.PlaybackFileLookup
import com.viel.aplayer.media.PlaybackPlanGateway
import com.viel.aplayer.media.PlaybackRootLookup
import com.viel.aplayer.media.PlaybackSourcePreflight

/**
 * Expose only narrow caller-facing contracts.
 * Keeps di-owned implementations out of the public application container so callers must use typed dependency views.
 */
interface AppContainer :
    PlaybackRuntimeDependencies,
    VfsPlaybackDependencies,
    DownloadRuntimeDependencies,
    ManualDownloadNotificationActionDependencies,
    LibrarySyncWorkerDependencies,
    AbsSyncWorkerDependencies,
    HomeScreenDependencies,
    SearchScreenDependencies,
    DetailScreenDependencies,
    SettingsScreenDependencies,
    PlayerScreenDependencies,
    EditScreenDependencies,
    AppShellDependencies,
    AppFeedbackDependencies,
    java.io.Closeable {
    /**
     * Process-wide entry point for transient UI feedback.
     * Keeps Toast and dialog commands out of media-core classes and scattered ViewModel-local event streams.
     */
    override val appEventSink: AppEventSink

    /**
     * Media-core event stream for playback facts.
     * Lets playback services publish domain outcomes while the application bridge decides how to render them.
     */
    override val settingsReadModel: com.viel.aplayer.application.library.settings.AppSettingsReadModel
    override val settingsCommands: com.viel.aplayer.application.library.settings.AppSettingsCommands

    /**
     * Scene-level root display stream.
     * Keeps SettingsViewModel root rows on the settings-root module rather than the broad library transition entry point.
     */
    override val settingsRootReadModel: SettingsRootReadModel

    /**
     * Scene-level root management operations.
     * Lets SettingsViewModel register roots, refresh reachability, and schedule scans through a compact settings interface.
     */
    override val settingsRootCommands: SettingsRootCommands

    /**
     * Scene-level deleted catalog stream.
     * Lets the recovery page list soft-deleted books without widening settings root dependencies.
     */
    override val deletedBookRecoveryReadModel: DeletedBookRecoveryReadModel

    /**
     * Scene-level restore command surface.
     * Keeps restore preflight and partial confirmation outside root management workflows.
     */
    override val deletedBookRecoveryCommands: DeletedBookRecoveryCommands

    /**
     * Read model and credential lookup seam for SettingsViewModel.
     * Keeps settings UI state construction away from Room DAO and credential-store dependencies.
     */
    override val settingsQueryUseCase: SettingsQueryUseCase

    override val formatSettingsRootUseCase: com.viel.aplayer.application.usecase.FormatSettingsRootUseCase


    /**
     * Root edit follow-up operations.
     * Encapsulates cache eviction, missing file recovery, and rescan scheduling after settings edits.
     */
    override val settingsLibraryMaintenanceUseCase: SettingsLibraryMaintenanceUseCase

    /**
     * Login, token reuse, credential save, and ABS root registration.
     * Prevents SettingsViewModel from constructing ABS clients or touching ABS credential stores directly.
     */
    override val absSettingsConnectionUseCase: AbsSettingsConnectionUseCase

    override val testWebDavConnectionUseCase: TestWebDavConnectionUseCase

    /**
     * Unregister roots and switch ABS libraries.
     * Coordinates playback teardown, root cache eviction, manual-download cleanup, and data cascade deletion.
     */
    override val libraryRootManagementUseCase: LibraryRootManagementUseCase

    /**
     * Delete audiobooks through the cleanup-first application boundary.
     * Clears cover and manual-download resources before the database row is soft-deleted.
     */
    override val bookManagementUseCase: BookManagementUseCase

    /**
     * Scene-level catalog stream for LibraryViewModel.
     * Keeps the home presentation path on a catalog-specific interface after the broad facade has been retired.
     */
    override val homeLibraryReadModel: HomeLibraryReadModel

    /**
     * Scene-level home catalog commands.
     * Exposes only home-owned commands instead of the complete set of library gateways.
     */
    override val homeLibraryUseCases: HomeLibraryUseCases

    /**
     * Scene-level search query stream.
     * Keeps SearchViewModel on the search scene read model while preserving the existing search-result data shape.
     */
    override val searchLibraryReadModel: SearchLibraryReadModel

    /**
     * Scene-level search history mutations.
     * Lets SearchViewModel save and prune history through a small command surface.
     */
    override val searchLibraryCommands: SearchLibraryCommands

    /**
     * Scene-level source and live metadata read surface.
     * Keeps DetailViewModel off broad library, root lookup, and file inventory gateways.
     */
    override val detailBookReadModel: DetailBookReadModel

    /**
     * Scene-level availability refresh command.
     * Lets DetailViewModel request status refresh through the detail scene boundary only.
     */
    override val detailBookCommands: DetailBookCommands

    /**
     * Scene-level player metadata and recovery read surface.
     * Keeps PlayerViewModel and player helpers on the player scene seam while preserving playback-page behavior.
     */
    override val playerLibraryReadModel: PlayerLibraryReadModel

    /**
     * Scene-level bookmark mutation surface.
     * Lets BookmarkManager write bookmark changes through a compact player command boundary.
     */
    override val playerBookmarkCommands: PlayerBookmarkCommands

    /**
     * Scene-level editable metadata read surface.
     * Keeps EditBookViewModel from resolving the old library presentation dependency view.
     */
    override val editBookReadModel: EditBookReadModel

    /**
     * Scene-level edit mutation surface.
     * Routes metadata and custom cover saves through the edit scene module.
     */
    override val editBookCommands: EditBookCommands

    /**
     * Read-only book metadata and file inventory seam.
     * Intended for media-core services that need stored book rows and track lists without bookmark or metadata mutation commands.
     */
    override val bookCatalogGateway: BookCatalogGateway

    /**
     * Playback timeline chapter seam.
     * Lets media services read chapter timelines without receiving catalog filters or bookmark commands.
     */
    override val chapterGateway: ChapterGateway

    /**
     * Notification bookmark command seam.
     * Lets media services create notification bookmarks without depending on catalog or chapter operations.
     */
    override val bookmarkGateway: BookmarkGateway

    /**
     * Fine-grained status-writing reachability seam.
     * Exposed for media-core recovery flows that must refresh file/book availability through a narrow adapter.
     */
    override val bookAvailabilityGateway: BookAvailabilityGateway

    /**
     * UI-facing playback startup command.
     * Keeps PlayerViewModel on an application use-case seam instead of reaching into generic book queries.
     */
    override val buildPlaybackPlanUseCase: BuildPlaybackPlanUseCase

    /**
     * Player-scene playback runtime seam.
     * Keeps PlayerViewModel and player helpers on a compact playback controller instead of media singleton lookups.
     */
    override val playerPlaybackController: PlayerPlaybackController

    /**
     * Fine-grained media-core playback startup read model.
     * Lets foreground playback services build playable plans without depending on broad library or book-query surfaces.
     */
    override val playbackPlanGateway: PlaybackPlanGateway

    /**
     * Fine-grained router for playback position and audio file status.
     * Lets media-core collaborators persist and recover progress without importing unrelated library capabilities.
     */
    override val progressGateway: ProgressGateway

    /**
     * Fine-grained scheduler interface owned by the library application layer.
     * Exposed for infrastructure coordinators while UI callers route scan commands through scene-specific use cases.
     */
    override val scanScheduler: ScanScheduler

    /**
     * Single I/O access point for file path abstractions.
     */
    override val vfsFileInterface: VfsFileInterface

    /**
     * Lookup utility to associate media IDs with physical files.
     */
    override val playbackFileLookup: PlaybackFileLookup

    /**
     * Read-only root lookup for manual-cache routing.
     * Manual-cache playback can classify SAF versus remote roots without receiving settings or root mutation APIs.
     */
    override val playbackRootLookup: PlaybackRootLookup

    /**
     * L1 manual cache handle for playback routing.
     * Exposes manual-cache access separately from DownloadRuntimeGateway so playback does not start manual download observers.
     */
    override val downloadCacheAccess: DownloadCacheAccess

    /**
     * Manual download queue seam.
     * Download commands receive queue operations without direct access to Media3 DownloadManager.
     */
    override val downloadRuntimeGateway: DownloadRuntimeGateway

    /**
     * Book-level manual offline cache command surface.
     * UI callers use this application operation after notification-permission preflight.
     */
    override val downloadController: DownloadController

    /**
     * Presentation-facing manual cache status stream.
     * Detail and management UI observe book-level cache state through this projection instead of touching Room or Media3 directly.
     */
    override val downloadStatusReadModel: DownloadStatusReadModel

    /**
     * Manual download task list projection.
     * Settings-hosted management UI consumes display-ready task rows without querying DAOs.
     */
    override val downloadManagementReadModel: DownloadManagementReadModel

    /**
     * Manual cache totals.
     * Cache settings screens read durable storage summaries through this narrow provider instead of touching SimpleCache objects.
     */
    override val cacheStatisticsProvider: CacheStatisticsProvider

    /**
     * Manual cache cleanup commands.
     * Settings screens can trigger confirmed manual-cache cleanup without receiving Media3 cache handles or DAO dependencies.
     */
    override val cacheMaintenanceCommands: CacheMaintenanceCommands

    /**
     * DB-only root lifecycle gate before media source creation.
     * Reads persisted library root rows without opening files or probing remote endpoints.
     */
    override val playbackSourcePreflight: PlaybackSourcePreflight

    /**
     * Dedicated mirror processor for Audiobookshelf servers.
     * Kept separate from local library seams to prevent remote REST details leaking into the local domain.
     */
    override val absCatalogSynchronizer: AbsCatalogSynchronizer

    /**
     * Coordinator for remote server progress handshakes.
     * Restricts operations to play/sync/close events, keeping local database records as the source of truth.
     */
    override val absPlaybackSessionSyncer: AbsPlaybackSessionSyncer

    override val resolveProgressConflictUseCase: ResolveProgressConflictUseCase

}

/**
 * Expose di-owned implementations only to composition-root wiring.
 * Keeps startup and process-level orchestration able to reach concrete di adapters without widening the public AppContainer contract.
 */
internal interface ProcessContainer : AppContainer {
    /**
     * Internal source-root maintenance seam.
     * Allows process wiring to coordinate root registration and reachability without making the gateway available through the public container type.
     */
    val libraryRootGateway: LibraryRootGateway

    /**
     * Internal keyword-history persistence seam.
     * Preserves composition-root access to search-history storage while screen callers stay on search scene dependencies.
     */
    val searchHistoryGateway: SearchHistoryGateway

    /**
     * Internal media di runtime owner.
     * Keeps direct playback runtime access inside process wiring so foreground callers keep using playback dependency views.
     */
    val playbackManager: com.viel.aplayer.media.PlaybackManager

    /**
     * Internal DataStore backend reference.
     * Prevents raw persistence storage from leaking through the public container while leaving di wiring able to share the singleton.
     */
    val searchHistoryStore: com.viel.aplayer.data.store.SearchHistoryStore

    /**
     * Internal playback recovery coordinator.
     * Lets startup invoke cold-start self-healing without exposing MediaGraph-owned recovery machinery to general callers.
     */
    val autoRewindManager: com.viel.aplayer.media.AutoRewindManager

    /**
     * Process-start manual download reconciliation gate.
     * Application startup can invoke this without exposing download recovery to screen-level dependency surfaces.
     */
    val downloadRecoveryService: DownloadRecoveryService

    /**
     * Book and root cleanup seam.
     * Management use cases receive only the book-level cleanup operation.
     */
    val manualDownloadCleanupGateway: ManualDownloadCleanupGateway

    /**
     * Process-internal Android service runtime.
     * APlayerDownloadService must return the raw manager to Media3, but public callers stay on DownloadRuntimeGateway.
     */
    @get:UnstableApi
    val media3DownloadManager: DownloadManager

    /**
     * Process-internal cache maintenance command.
     * WorkManager can remove stale L1 cache keys without exposing cache mutation APIs to UI dependencies.
     */
    val manualDownloadOrphanCleaner: ManualDownloadOrphanCleaner

    /**
     * Internal remote progress merge adapter.
     * Keeps authorized progress refresh mechanics available to di wiring without adding them to the public dependency view union.
     */
    val absAuthorizedProgressSynchronizer: AbsAuthorizedProgressSynchronizer

    /**
     * Internal application-level synchronization coordinator.
     * Allows process-owned settings wiring to start root sync work while external callers use worker or scene dependency views.
     */
    val absSyncTaskCoordinator: AbsSyncTaskCoordinator
}

@UnstableApi
internal class DefaultAppContainer(private val context: Context) : ProcessContainer {

    internal val uiEvents = UiEventGraph()
    internal val data = DataGraph(context)
    internal val media = MediaGraph(context, data)
    internal val download = DownloadGraph(context, data, media)
    internal val library: LibraryGraph = LibraryGraph(
        context = context,
        data = data,
        media = media,
        uiEvents = uiEvents,
        manualDownloadCleanupGatewayProvider = { download.manualDownloadCleanupGateway },
        absCoverStoreProvider = { abs.absCoverCache }
    )
    internal val abs: AbsGraph = AbsGraph(context, data, media, library, uiEvents)

    init {
        uiEvents.startEventBridges()
    }

    private val settingsWebDavCredentialStore: WebDavCredentialStore by lazy {
        WebDavCredentialStore(context.applicationContext)
    }

    override val settingsReadModel: com.viel.aplayer.application.library.settings.AppSettingsReadModel
        get() = data.settingsReadModel

    override val settingsCommands: com.viel.aplayer.application.library.settings.AppSettingsCommands by lazy {
        DownloadAwareAppSettingsCommands(
            delegate = data.settingsCommands,
            downloadRuntimeGatewayProvider = { download.downloadRuntimeGateway },
            isDownloadRuntimeInitialized = { download.isDownloadRuntimeInitialized }
        )
    }

    override val appEventSink: AppEventSink
        get() = uiEvents.appEventSink

    override val playbackDomainEventSink: PlaybackDomainEventSink
        get() = uiEvents.playbackDomainEventSink

    override val formatSettingsRootUseCase: com.viel.aplayer.application.usecase.FormatSettingsRootUseCase by lazy {
        com.viel.aplayer.application.usecase.FormatSettingsRootUseCase(context)
    }

    override val settingsQueryUseCase: SettingsQueryUseCase by lazy {
        SettingsQueryUseCase(
            libraryRootGateway = library.libraryRootGateway,
            absSyncStateDao = data.database.absSyncStateDao(),
            bookDao = data.database.bookDao(),
            libraryRootDao = data.database.libraryRootDao(),
            webDavCredentialStore = settingsWebDavCredentialStore,
            absCredentialStore = abs.absCredentialStore
        )
    }

    private val settingsRootModule: DefaultSettingsRootModule by lazy {
        DefaultSettingsRootModule(
            observeRootSnapshotsSource = settingsQueryUseCase::observeLibraryRootSnapshots,
            libraryRootGateway = library.libraryRootGateway,
            scanScheduler = library.scanScheduler,
            inspectAbsSyncPlan = abs.absCatalogSynchronizer::inspectRootSyncPlan,
            startAbsSyncTask = abs.absSyncTaskCoordinator::start
        )
    }

    override val settingsRootReadModel: SettingsRootReadModel
        get() = settingsRootModule

    override val settingsRootCommands: SettingsRootCommands
        get() = settingsRootModule

    override val deletedBookRecoveryReadModel: DeletedBookRecoveryReadModel
        get() = library.deletedBookRecoveryReadModel

    override val deletedBookRecoveryCommands: DeletedBookRecoveryCommands
        get() = library.deletedBookRecoveryCommands

    override val settingsLibraryMaintenanceUseCase: SettingsLibraryMaintenanceUseCase by lazy {
        SettingsLibraryMaintenanceUseCase(
            libraryRootGateway = library.libraryRootGateway,
            scanScheduler = library.scanScheduler,
            cacheEvictionCoordinator = library.cacheEvictionCoordinator,
            missingBookFileRecoveryChecker = MissingBookFileRecoveryChecker(context.applicationContext)
        )
    }

    override val absSettingsConnectionUseCase: AbsSettingsConnectionUseCase by lazy {
        AbsSettingsConnectionUseCase(
            apiClient = abs.absApiClient,
            connectionTester = abs.absConnectionTester,
            credentialStore = abs.absCredentialStore,
            libraryRootDao = data.database.libraryRootDao(),
            libraryRootGateway = library.libraryRootGateway,
            libraryRootManagementUseCase = library.libraryRootManagementUseCase,
            maintenanceUseCase = settingsLibraryMaintenanceUseCase
        )
    }

    private val webDavConnectionTester: WebDavConnectionTester by lazy {
        WebDavConnectionTester(appSettingsRepository = data.settingsRepository)
    }

    override val testWebDavConnectionUseCase: TestWebDavConnectionUseCase by lazy {
        TestWebDavConnectionUseCase(
            webDavConnectionTester = webDavConnectionTester,
            settingsQueryUseCase = settingsQueryUseCase
        )
    }

    override val libraryRootManagementUseCase: LibraryRootManagementUseCase
        get() = library.libraryRootManagementUseCase

    override val exportUserDataUseCase: ExportUserDataUseCase by lazy {
        ExportUserDataUseCase(context)
    }

    override val importUserDataUseCase: ImportUserDataUseCase by lazy {
        ImportUserDataUseCase(context)
    }

    override val bookManagementUseCase: BookManagementUseCase
        get() = library.bookManagementUseCase

    override val homeLibraryReadModel: HomeLibraryReadModel
        get() = library.homeLibraryReadModel

    override val homeLibraryUseCases: HomeLibraryUseCases
        get() = library.homeLibraryUseCases

    override val searchLibraryReadModel: SearchLibraryReadModel
        get() = library.searchLibraryReadModel

    override val searchLibraryCommands: SearchLibraryCommands
        get() = library.searchLibraryCommands

    override val detailBookReadModel: DetailBookReadModel
        get() = library.detailBookReadModel

    override val detailBookCommands: DetailBookCommands
        get() = library.detailBookCommands

    override val playerLibraryReadModel: PlayerLibraryReadModel
        get() = library.playerLibraryReadModel

    override val playerBookmarkCommands: PlayerBookmarkCommands
        get() = library.playerBookmarkCommands

    override val editBookReadModel: EditBookReadModel
        get() = library.editBookReadModel

    override val editBookCommands: EditBookCommands
        get() = library.editBookCommands

    override val bookCatalogGateway: BookCatalogGateway
        get() = library.bookCatalogGateway

    override val chapterGateway: ChapterGateway
        get() = library.chapterGateway

    override val bookmarkGateway: BookmarkGateway
        get() = library.bookmarkGateway

    override val bookAvailabilityGateway: BookAvailabilityGateway
        get() = library.bookAvailabilityGateway

    override val buildPlaybackPlanUseCase: BuildPlaybackPlanUseCase
        get() = library.buildPlaybackPlanUseCase

    override val playerPlaybackController: PlayerPlaybackController
        get() = media.playerPlaybackController

    override val playbackPlanGateway: PlaybackPlanGateway
        get() = library.playbackPlanGateway

    override val progressGateway: ProgressGateway
        get() = library.progressGateway

    override val scanScheduler: ScanScheduler
        get() = library.scanScheduler

    override val libraryRootGateway: LibraryRootGateway
        get() = library.libraryRootGateway

    override val searchHistoryGateway: SearchHistoryGateway
        get() = library.searchHistoryGateway

    override val vfsFileInterface: VfsFileInterface
        get() = media.vfsFileInterface

    override val playbackFileLookup: PlaybackFileLookup
        get() = media.playbackFileLookup

    override val playbackRootLookup: PlaybackRootLookup
        get() = media.playbackRootLookup

    override val downloadCacheAccess: DownloadCacheAccess
        get() = download.downloadCacheAccess

    override val downloadRuntimeGateway: DownloadRuntimeGateway
        get() = download.downloadRuntimeGateway

    override val downloadController: DownloadController
        get() = download.downloadController

    override val downloadStatusReadModel: DownloadStatusReadModel
        get() = download.downloadStatusReadModel

    override val downloadManagementReadModel: DownloadManagementReadModel
        get() = download.downloadManagementReadModel

    override val cacheStatisticsProvider: CacheStatisticsProvider
        get() = download.cacheStatisticsProvider

    override val cacheMaintenanceCommands: CacheMaintenanceCommands
        get() = download.cacheMaintenanceCommands

    override val playbackSourcePreflight: PlaybackSourcePreflight
        get() = media.playbackSourcePreflight

    override val playbackManager: com.viel.aplayer.media.PlaybackManager
        get() = media.playbackManager

    override val searchHistoryStore: com.viel.aplayer.data.store.SearchHistoryStore
        get() = data.searchHistoryStore

    override val autoRewindManager: com.viel.aplayer.media.AutoRewindManager
        get() = media.autoRewindManager

    override val downloadRecoveryService: DownloadRecoveryService
        get() = download.downloadRecoveryService

    override val manualDownloadCleanupGateway: ManualDownloadCleanupGateway
        get() = download.manualDownloadCleanupGateway

    override val media3DownloadManager: DownloadManager
        get() = download.media3DownloadManager

    override val manualDownloadOrphanCleaner: ManualDownloadOrphanCleaner
        get() = download.manualDownloadOrphanCleaner

    override val absCatalogSynchronizer: AbsCatalogSynchronizer
        get() = abs.absCatalogSynchronizer

    override val absPlaybackSessionSyncer: AbsPlaybackSessionSyncer
        get() = abs.absPlaybackSessionSyncer

    override val resolveProgressConflictUseCase: ResolveProgressConflictUseCase by lazy {
        ResolveProgressConflictUseCase(abs.absProgressConflictCoordinator)
    }

    override val absAuthorizedProgressSynchronizer: AbsAuthorizedProgressSynchronizer
        get() = abs.absAuthorizedProgressSynchronizer

    override val absSyncTaskCoordinator: AbsSyncTaskCoordinator
        get() = abs.absSyncTaskCoordinator

    override fun close() {
        closeAppGraphsInLifecycleOrder(
            media = media,
            download = download,
            library = library,
            abs = abs,
            uiEvents = uiEvents
        )
    }
}
