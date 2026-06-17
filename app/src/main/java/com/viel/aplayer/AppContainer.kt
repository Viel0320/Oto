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
import com.viel.aplayer.data.root.LibraryRootGateway
import com.viel.aplayer.data.progress.ProgressGateway
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
 * Public Dependency View Surface (Expose only narrow caller-facing contracts)
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
     * Application Event Sink (Process-wide entry point for transient UI feedback)
     * Keeps Toast and dialog commands out of media-core classes and scattered ViewModel-local event streams.
     */
    override val appEventSink: AppEventSink

    /**
     * Playback Domain Event Sink (Media-core event stream for playback facts)
     * Lets playback services publish domain outcomes while the application bridge decides how to render them.
     */
    override val settingsReadModel: com.viel.aplayer.application.library.settings.AppSettingsReadModel
    override val settingsCommands: com.viel.aplayer.application.library.settings.AppSettingsCommands

    /**
     * Settings Root Read Model (Scene-level root display stream)
     * Keeps SettingsViewModel root rows on the settings-root module rather than the broad library transition entry point.
     */
    override val settingsRootReadModel: SettingsRootReadModel

    /**
     * Settings Root Commands (Scene-level root management operations)
     * Lets SettingsViewModel register roots, refresh reachability, and schedule scans through a compact settings interface.
     */
    override val settingsRootCommands: SettingsRootCommands

    /**
     * Deleted Book Recovery Read Model (Scene-level deleted catalog stream)
     * Lets the recovery page list soft-deleted books without widening settings root dependencies.
     */
    override val deletedBookRecoveryReadModel: DeletedBookRecoveryReadModel

    /**
     * Deleted Book Recovery Commands (Scene-level restore command surface)
     * Keeps restore preflight and partial confirmation outside root management workflows.
     */
    override val deletedBookRecoveryCommands: DeletedBookRecoveryCommands

    /**
     * Settings Query Use Case (Read model and credential lookup seam for SettingsViewModel)
     * Keeps settings UI state construction away from Room DAO and credential-store dependencies.
     */
    override val settingsQueryUseCase: SettingsQueryUseCase

    override val formatSettingsRootUseCase: com.viel.aplayer.application.usecase.FormatSettingsRootUseCase


    /**
     * Settings Library Maintenance Use Case (Root edit follow-up operations)
     * Encapsulates cache eviction, missing file recovery, and rescan scheduling after settings edits.
     */
    override val settingsLibraryMaintenanceUseCase: SettingsLibraryMaintenanceUseCase

    /**
     * Settings ABS Connection Use Case (Login, token reuse, credential save, and ABS root registration)
     * Prevents SettingsViewModel from constructing ABS clients or touching ABS credential stores directly.
     */
    override val absSettingsConnectionUseCase: AbsSettingsConnectionUseCase

    // Title: WebDavConnectionTester Decoupling (Expose TestWebDavConnectionUseCase in AppContainer)
    override val testWebDavConnectionUseCase: TestWebDavConnectionUseCase
    
    /**
     * Library Root Management Use Case (Unregister roots and switch ABS libraries)
     * Coordinates playback teardown, root cache eviction, manual-download cleanup, and data cascade deletion.
     */
    override val libraryRootManagementUseCase: LibraryRootManagementUseCase

    /**
     * Book Management Use Case (Delete audiobooks through the cleanup-first application boundary)
     * Clears cover and manual-download resources before the database row is soft-deleted.
     */
    override val bookManagementUseCase: BookManagementUseCase

    /**
     * Home Library Read Model (Scene-level catalog stream for LibraryViewModel)
     * Keeps the home presentation path on a catalog-specific interface after the broad facade has been retired.
     */
    override val homeLibraryReadModel: HomeLibraryReadModel

    /**
     * Home Library Use Cases (Scene-level home catalog commands)
     * Exposes only home-owned commands instead of the complete set of library gateways.
     */
    override val homeLibraryUseCases: HomeLibraryUseCases

    /**
     * Search Library Read Model (Scene-level search query stream)
     * Keeps SearchViewModel on the search scene read model while preserving the existing search-result data shape.
     */
    override val searchLibraryReadModel: SearchLibraryReadModel

    /**
     * Search Library Commands (Scene-level search history mutations)
     * Lets SearchViewModel save and prune history through a small command surface.
     */
    override val searchLibraryCommands: SearchLibraryCommands

    /**
     * Detail Book Read Model (Scene-level source and live metadata read surface)
     * Keeps DetailViewModel off broad library, root lookup, and file inventory gateways.
     */
    override val detailBookReadModel: DetailBookReadModel

    /**
     * Detail Book Commands (Scene-level availability refresh command)
     * Lets DetailViewModel request status refresh through the detail scene boundary only.
     */
    override val detailBookCommands: DetailBookCommands

    /**
     * Player Library Read Model (Scene-level player metadata and recovery read surface)
     * Keeps PlayerViewModel and player helpers on the player scene seam while preserving playback-page behavior.
     */
    override val playerLibraryReadModel: PlayerLibraryReadModel

    /**
     * Player Bookmark Commands (Scene-level bookmark mutation surface)
     * Lets BookmarkManager write bookmark changes through a compact player command boundary.
     */
    override val playerBookmarkCommands: PlayerBookmarkCommands

    /**
     * Edit Book Read Model (Scene-level editable metadata read surface)
     * Keeps EditBookViewModel from resolving the old library presentation dependency view.
     */
    override val editBookReadModel: EditBookReadModel

    /**
     * Edit Book Commands (Scene-level edit mutation surface)
     * Routes metadata and custom cover saves through the edit scene module.
     */
    override val editBookCommands: EditBookCommands

    /**
     * Playback/Core Book Catalog Gateway (Read-only book metadata and file inventory seam)
     * Intended for media-core services that need stored book rows and track lists without bookmark or metadata mutation commands.
     */
    override val bookCatalogGateway: BookCatalogGateway

    /**
     * Playback/Core Chapter Gateway (Playback timeline chapter seam)
     * Lets media services read chapter timelines without receiving catalog filters or bookmark commands.
     */
    override val chapterGateway: ChapterGateway

    /**
     * Playback/Core Bookmark Gateway (Notification bookmark command seam)
     * Lets media services create notification bookmarks without depending on catalog or chapter operations.
     */
    override val bookmarkGateway: BookmarkGateway

    /**
     * Playback/Core Book Availability Gateway (Fine-grained status-writing reachability seam)
     * Exposed for media-core recovery flows that must refresh file/book availability through a narrow adapter.
     */
    override val bookAvailabilityGateway: BookAvailabilityGateway

    /**
     * Build Playback Plan Use Case (UI-facing playback startup command)
     * Keeps PlayerViewModel on an application use-case seam instead of reaching into generic book queries.
     */
    override val buildPlaybackPlanUseCase: BuildPlaybackPlanUseCase

    /**
     * Player Playback Controller (Player-scene playback runtime seam)
     * Keeps PlayerViewModel and player helpers on a compact playback controller instead of media singleton lookups.
     */
    override val playerPlaybackController: PlayerPlaybackController

    /**
     * Playback Plan Gateway (Fine-grained media-core playback startup read model)
     * Lets foreground playback services build playable plans without depending on broad library or book-query surfaces.
     */
    override val playbackPlanGateway: PlaybackPlanGateway

    /**
     * Playback/Core Progress Gateway (Fine-grained router for playback position and audio file status)
     * Lets media-core collaborators persist and recover progress without importing unrelated library capabilities.
     */
    override val progressGateway: ProgressGateway

    /**
     * Scanner Gateway Adapter (Fine-grained scheduler interface owned by the library application layer)
     * Exposed for infrastructure coordinators while UI callers route scan commands through scene-specific use cases.
     */
    override val scanScheduler: ScanScheduler

    /**
     * Virtual File System Interface (Single I/O access point for file path abstractions)
     */
    override val vfsFileInterface: VfsFileInterface

    /**
     * Playback File Resolver (Lookup utility to associate media IDs with physical files)
     */
    override val playbackFileLookup: PlaybackFileLookup

    /**
     * Playback Root Resolver (Read-only root lookup for manual-cache routing)
     * Manual-cache playback can classify SAF versus remote roots without receiving settings or root mutation APIs.
     */
    override val playbackRootLookup: PlaybackRootLookup

    /**
     * Download Cache Access (L1 manual cache handle for playback routing)
     * Exposes manual-cache access separately from DownloadRuntimeGateway so playback does not start manual download observers.
     */
    override val downloadCacheAccess: DownloadCacheAccess

    /**
     * Download Runtime Gateway (Manual download queue seam)
     * Download commands receive queue operations without direct access to Media3 DownloadManager.
     */
    override val downloadRuntimeGateway: DownloadRuntimeGateway

    /**
     * Download Controller (Book-level manual offline cache command surface)
     * UI callers use this application operation after notification-permission preflight.
     */
    override val downloadController: DownloadController

    /**
     * Download Status Read Model (Presentation-facing manual cache status stream)
     * Detail and management UI observe book-level cache state through this projection instead of touching Room or Media3 directly.
     */
    override val downloadStatusReadModel: DownloadStatusReadModel

    /**
     * Download Management Read Model (Manual download task list projection)
     * Settings-hosted management UI consumes display-ready task rows without querying DAOs.
     */
    override val downloadManagementReadModel: DownloadManagementReadModel

    /**
     * Cache Statistics Provider (Manual cache totals)
     * Cache settings screens read durable storage summaries through this narrow provider instead of touching SimpleCache objects.
     */
    override val cacheStatisticsProvider: CacheStatisticsProvider

    /**
     * Cache Maintenance Commands (Manual cache cleanup commands)
     * Settings screens can trigger confirmed manual-cache cleanup without receiving Media3 cache handles or DAO dependencies.
     */
    override val cacheMaintenanceCommands: CacheMaintenanceCommands

    /**
     * Playback Source Preflight (DB-only root lifecycle gate before media source creation)
     * Reads persisted library root rows without opening files or probing remote endpoints.
     */
    override val playbackSourcePreflight: PlaybackSourcePreflight

    /**
     * ABS Catalog Synchronizer (Dedicated mirror processor for Audiobookshelf servers)
     * Kept separate from local library seams to prevent remote REST details leaking into the local domain.
     */
    override val absCatalogSynchronizer: AbsCatalogSynchronizer

    /**
     * ABS Playback Session Syncer (Coordinator for remote server progress handshakes)
     * Restricts operations to play/sync/close events, keeping local database records as the source of truth.
     */
    override val absPlaybackSessionSyncer: AbsPlaybackSessionSyncer

    // Title: AbsProgressConflictCoordinator Decoupling (Expose ResolveProgressConflictUseCase in AppContainer)
    override val resolveProgressConflictUseCase: ResolveProgressConflictUseCase

}

/**
 * Process Container Surface (Expose di-owned implementations only to composition-root wiring)
 * Keeps startup and process-level orchestration able to reach concrete di adapters without widening the public AppContainer contract.
 */
internal interface ProcessContainer : AppContainer {
    /**
     * Library Root Gateway Adapter (Internal source-root maintenance seam)
     * Allows process wiring to coordinate root registration and reachability without making the gateway available through the public container type.
     */
    val libraryRootGateway: LibraryRootGateway

    /**
     * Search History Gateway Adapter (Internal keyword-history persistence seam)
     * Preserves composition-root access to search-history storage while screen callers stay on search scene dependencies.
     */
    val searchHistoryGateway: SearchHistoryGateway

    /**
     * Playback Manager Instance (Internal media di runtime owner)
     * Keeps direct playback runtime access inside process wiring so foreground callers keep using playback dependency views.
     */
    val playbackManager: com.viel.aplayer.media.PlaybackManager

    /**
     * Search History Store (Internal DataStore backend reference)
     * Prevents raw persistence storage from leaking through the public container while leaving di wiring able to share the singleton.
     */
    val searchHistoryStore: com.viel.aplayer.data.store.SearchHistoryStore

    /**
     * Auto Rewind Controller (Internal playback recovery coordinator)
     * Lets startup invoke cold-start self-healing without exposing MediaGraph-owned recovery machinery to general callers.
     */
    val autoRewindManager: com.viel.aplayer.media.AutoRewindManager

    /**
     * Download Recovery Service (Process-start manual download reconciliation gate)
     * Application startup can invoke this without exposing download recovery to screen-level dependency surfaces.
     */
    val downloadRecoveryService: DownloadRecoveryService

    /**
     * Manual Download Cleanup Gateway (Book and root cleanup seam)
     * Management use cases receive only the book-level cleanup operation.
     */
    val manualDownloadCleanupGateway: ManualDownloadCleanupGateway

    /**
     * Media3 Download Manager (Process-internal Android service runtime)
     * APlayerDownloadService must return the raw manager to Media3, but public callers stay on DownloadRuntimeGateway.
     */
    @get:UnstableApi
    val media3DownloadManager: DownloadManager

    /**
     * Manual Download Orphan Cleaner (Process-internal cache maintenance command)
     * WorkManager can remove stale L1 cache keys without exposing cache mutation APIs to UI dependencies.
     */
    val manualDownloadOrphanCleaner: ManualDownloadOrphanCleaner

    /**
     * ABS Authorized Progress Synchronizer (Internal remote progress merge adapter)
     * Keeps authorized progress refresh mechanics available to di wiring without adding them to the public dependency view union.
     */
    val absAuthorizedProgressSynchronizer: AbsAuthorizedProgressSynchronizer

    /**
     * ABS Sync Task Coordinator (Internal application-level synchronization coordinator)
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
        // Management Download Cleanup Wiring (Give LibraryGraph only the book-scoped manual cache cleanup seam)
        // BookManagementUseCase and LibraryRootManagementUseCase can remove Media3 records without seeing queue or cache statistics APIs.
        manualDownloadCleanupGateway = download.manualDownloadCleanupGateway,
        // ABS Cover Store provider lambda (Provide a lazy accessor to AbsCoverCache to bypass circular initialization ordering)
        // Since AbsGraph is created after LibraryGraph, passing a lambda allows lazy resolution when CoverRecoveryHelper needs to retrieve ABS covers.
        absCoverStoreProvider = { abs.absCoverCache }
    )
    internal val abs: AbsGraph = AbsGraph(context, data, media, library, uiEvents)

    init {
        // Application Event Bridge Activation (Attach process-level event routing as soon as the container exists)
        // This ensures background services and workers can publish feedback before the first Compose collector starts.
        uiEvents.startEventBridges()
    }

    private val settingsWebDavCredentialStore: WebDavCredentialStore by lazy {
        // Settings WebDAV Credential Store (Shared credential lookup for query use cases)
        // Reuses the same storage adapter as LibraryRootGatewayImpl without exposing it to SettingsViewModel.
        WebDavCredentialStore(context.applicationContext)
    }

    // Title: Settings Abstractions Delegate (Delegate dependency lookups to DataGraph abstractions)
    override val settingsReadModel: com.viel.aplayer.application.library.settings.AppSettingsReadModel
        get() = data.settingsReadModel

    override val settingsCommands: com.viel.aplayer.application.library.settings.AppSettingsCommands by lazy {
        // Download-Aware Settings Command Wiring (Keep DataStore writes pure while updating live download requirements)
        // The decorator checks DownloadGraph lazy state before touching DownloadRuntimeGateway, so settings screens do not start DownloadManager.
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

    // Title: Instantiate formatSettingsRootUseCase (Wire context to presentation format UseCase)
    // Instantiates the settings formatter use case with the application context.
    override val formatSettingsRootUseCase: com.viel.aplayer.application.usecase.FormatSettingsRootUseCase by lazy {
        com.viel.aplayer.application.usecase.FormatSettingsRootUseCase(context)
    }

    override val settingsQueryUseCase: SettingsQueryUseCase by lazy {
        // Settings Query Use Case Wiring (Combines library root, ABS sync, book count, and credential readers)
        // ViewModels consume this single read model seam instead of combining DAOs directly.
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
        // Settings Root Module Wiring (Compose settings root reads and commands from granular adapters)
        // This keeps root registration, status refresh, and manual scan triggers inside the settings-root scene seam.
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
        // Settings Maintenance Use Case Wiring (Centralizes edit follow-up work)
        // Root relocation, WebDAV edits, and ABS edit recovery no longer perform cache or recovery operations inside the UI layer.
        SettingsLibraryMaintenanceUseCase(
            libraryRootGateway = library.libraryRootGateway,
            scanScheduler = library.scanScheduler,
            cacheEvictionCoordinator = library.cacheEvictionCoordinator,
            missingBookFileRecoveryChecker = MissingBookFileRecoveryChecker(context.applicationContext)
        )
    }

    override val absSettingsConnectionUseCase: AbsSettingsConnectionUseCase by lazy {
        // ABS Settings Use Case Wiring (Keeps ABS infrastructure behind application operations)
        // SettingsViewModel only passes form fields and receives reusable outcomes for state rendering.
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
        // WebDAV Tester Wiring (Shares app settings for insecure TLS selection)
        // The tester hides OkHttp, PROPFIND, and TLS details from the settings presentation layer.
        WebDavConnectionTester(appSettingsRepository = data.settingsRepository)
    }

    // Title: WebDavConnectionTester Decoupling (Wire TestWebDavConnectionUseCase in DefaultAppContainer)
    override val testWebDavConnectionUseCase: TestWebDavConnectionUseCase by lazy {
        TestWebDavConnectionUseCase(
            webDavConnectionTester = webDavConnectionTester,
            settingsQueryUseCase = settingsQueryUseCase
        )
    }

    override val libraryRootManagementUseCase: LibraryRootManagementUseCase
        get() = library.libraryRootManagementUseCase

    // Title: Initialize Backup and Restore Use Cases (Create lazy instances of the data backup/restore use cases)
    // Instantiates export and import use cases with the application context.
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

    // Title: AbsProgressConflictCoordinator Decoupling (Wire ResolveProgressConflictUseCase in DefaultAppContainer)
    override val resolveProgressConflictUseCase: ResolveProgressConflictUseCase by lazy {
        ResolveProgressConflictUseCase(abs.absProgressConflictCoordinator)
    }

    override val absAuthorizedProgressSynchronizer: AbsAuthorizedProgressSynchronizer
        get() = abs.absAuthorizedProgressSynchronizer

    override val absSyncTaskCoordinator: AbsSyncTaskCoordinator
        get() = abs.absSyncTaskCoordinator

    override fun close() {
        // Graph Teardown Delegation (Close graphs in the application lifecycle order)
        // The composition root owns cross-di order while each di owns its own initialized resources.
        closeAppGraphsInLifecycleOrder(
            media = media,
            download = download,
            library = library,
            abs = abs,
            uiEvents = uiEvents
        )
    }
}
