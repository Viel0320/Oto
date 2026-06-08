package com.viel.aplayer

import android.content.Context
import androidx.media3.common.util.UnstableApi
import com.viel.aplayer.abs.playback.AbsPlaybackSessionSyncer
import com.viel.aplayer.abs.playback.AbsProgressConflictCoordinator
import com.viel.aplayer.abs.sync.AbsAuthorizedProgressSynchronizer
import com.viel.aplayer.abs.sync.AbsCatalogSynchronizer
import com.viel.aplayer.abs.sync.AbsSyncTaskCoordinator
import com.viel.aplayer.data.AppSettingsRepository
import com.viel.aplayer.data.LibraryFacade
import com.viel.aplayer.data.gateway.BookAvailabilityGateway
import com.viel.aplayer.data.gateway.BookQueryGateway
import com.viel.aplayer.data.gateway.LibraryRootGateway
import com.viel.aplayer.data.gateway.ProgressGateway
import com.viel.aplayer.data.gateway.ScanScheduler
import com.viel.aplayer.data.gateway.SearchHistoryGateway
import com.viel.aplayer.dependencies.AbsSyncWorkerDependencies
import com.viel.aplayer.dependencies.AppFeedbackDependencies
import com.viel.aplayer.dependencies.AppShellDependencies
import com.viel.aplayer.dependencies.HomeScreenDependencies
import com.viel.aplayer.dependencies.LibrarySyncWorkerDependencies
import com.viel.aplayer.dependencies.PlaybackRuntimeDependencies
import com.viel.aplayer.dependencies.PlayerScreenDependencies
import com.viel.aplayer.dependencies.SettingsScreenDependencies
import com.viel.aplayer.dependencies.VfsPlaybackDependencies
import com.viel.aplayer.domain.usecase.AbsSettingsConnectionUseCase
import com.viel.aplayer.domain.usecase.BuildPlaybackPlanUseCase
import com.viel.aplayer.domain.usecase.DeleteBookUseCase
import com.viel.aplayer.domain.usecase.DeleteLibraryRootUseCase
import com.viel.aplayer.domain.usecase.SettingsLibraryMaintenanceUseCase
import com.viel.aplayer.domain.usecase.SettingsQueryUseCase
import com.viel.aplayer.event.AppEventSink
import com.viel.aplayer.graph.AbsGraph
import com.viel.aplayer.graph.DataGraph
import com.viel.aplayer.graph.LibraryGraph
import com.viel.aplayer.graph.MediaGraph
import com.viel.aplayer.graph.UiEventGraph
import com.viel.aplayer.graph.closeAppGraphsInLifecycleOrder
import com.viel.aplayer.library.availability.MissingBookFileRecoveryChecker
import com.viel.aplayer.library.readmodel.HomeLibraryReadModel
import com.viel.aplayer.library.readmodel.HomeLibraryUseCases
import com.viel.aplayer.library.vfs.VfsFileInterface
import com.viel.aplayer.library.vfs.sourceProvider.webdav.WebDavConnectionTester
import com.viel.aplayer.library.vfs.sourceProvider.webdav.WebDavCredentialStore
import com.viel.aplayer.media.PlaybackDomainEventSink
import com.viel.aplayer.media.PlaybackFileLookup
import com.viel.aplayer.media.PlaybackPlanGateway
import com.viel.aplayer.media.PlaybackSourcePreflight

/**
 * Global Dependency Container (Manages lifecycle and instantiation of core gateway services)
 * Serves as a lightweight DI provider for unified coordination across application domains.
 */
interface AppContainer :
    PlaybackRuntimeDependencies,
    VfsPlaybackDependencies,
    LibrarySyncWorkerDependencies,
    AbsSyncWorkerDependencies,
    HomeScreenDependencies,
    SettingsScreenDependencies,
    PlayerScreenDependencies,
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
    override val playbackDomainEventSink: PlaybackDomainEventSink

    override val settingsRepository: AppSettingsRepository

    /**
     * Settings Query Use Case (Read model and credential lookup seam for SettingsViewModel)
     * Keeps settings UI state construction away from Room DAO and credential-store dependencies.
     */
    override val settingsQueryUseCase: SettingsQueryUseCase

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

    /**
     * WebDAV Connection Tester (Settings-side remote endpoint preflight)
     * Owns PROPFIND and TLS policy checks so SettingsViewModel only receives success or failure.
     */
    override val webDavConnectionTester: WebDavConnectionTester
    
    /**
     * Cross-Domain Use Case (Unregister and purge library root coordinates)
     * Coordinates active playback teardown and background physical data cleanup.
     */
    override val deleteLibraryRootUseCase: DeleteLibraryRootUseCase

    /**
     * Delete Book Use Case (Delete audiobook, chapters, bookmarks, and physical cache files)
     */
    override val deleteBookUseCase: DeleteBookUseCase

    /**
     * Home Library Read Model (Scene-level catalog stream for LibraryViewModel)
     * Keeps the home presentation path off the all-capability LibraryFacade while retaining the facade for transition screens.
     */
    override val homeLibraryReadModel: HomeLibraryReadModel

    /**
     * Home Library Use Cases (Scene-level home catalog commands)
     * Exposes only home-owned commands instead of the complete set of library gateways.
     */
    override val homeLibraryUseCases: HomeLibraryUseCases

    /**
     * UI-Facing Library Facade (Unified entry point for presentation and application use-case callers)
     * Keeps screens and ViewModels on one high-level seam while delegating actual work to the focused gateway adapters below.
     */
    override val libraryFacade: LibraryFacade

    /**
     * Playback/Core Book Query Gateway (Fine-grained router for non-UI consumers that need only book metadata operations)
     * Intended for media-core services and domain use cases that should avoid depending on the wider LibraryFacade interface.
     */
    override val bookQueryGateway: BookQueryGateway

    /**
     * Playback/Core Book Availability Gateway (Fine-grained status-writing reachability seam)
     * Exposed for media-core recovery flows that must refresh file/book availability without depending on LibraryFacade.
     */
    override val bookAvailabilityGateway: BookAvailabilityGateway

    /**
     * Build Playback Plan Use Case (UI-facing playback startup command)
     * Keeps PlayerViewModel on an application use-case seam instead of reaching into generic book queries.
     */
    override val buildPlaybackPlanUseCase: BuildPlaybackPlanUseCase

    /**
     * Playback Plan Gateway (Fine-grained media-core playback startup read model)
     * Lets foreground playback services build playable plans without depending on LibraryFacade or BookQueryGateway.
     */
    override val playbackPlanGateway: PlaybackPlanGateway

    /**
     * Playback/Core Progress Gateway (Fine-grained router for playback position and audio file status)
     * Lets media-core collaborators persist and recover progress without importing unrelated library capabilities.
     */
    override val progressGateway: ProgressGateway

    /**
     * Scanner Gateway Adapter (Fine-grained scheduler interface owned by the library application layer)
     * Exposed for infrastructure coordinators while UI callers route scan commands through LibraryFacade.
     */
    override val scanScheduler: ScanScheduler

    /**
     * Library Root Gateway Adapter (Fine-grained maintenance interface for source root registration and reachability)
     * Exposed for domain coordination and data services while presentation code consumes the LibraryFacade seam.
     */
    val libraryRootGateway: LibraryRootGateway

    /**
     * Search History Gateway Adapter (Fine-grained persistence interface for keyword lookup logs)
     * Remains available for application services while screens reach search history through LibraryFacade.
     */
    val searchHistoryGateway: SearchHistoryGateway

    /**
     * Virtual File System Interface (Single I/O access point for file path abstractions)
     */
    override val vfsFileInterface: VfsFileInterface

    /**
     * Playback File Resolver (Lookup utility to associate media IDs with physical files)
     */
    override val playbackFileLookup: PlaybackFileLookup

    /**
     * Playback Source Preflight (DB-only root lifecycle gate before media source creation)
     * Reads persisted library root rows without opening files or probing remote endpoints.
     */
    override val playbackSourcePreflight: PlaybackSourcePreflight

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
    override val absCatalogSynchronizer: AbsCatalogSynchronizer

    /**
     * ABS Playback Session Syncer (Coordinator for remote server progress handshakes)
     * Restricts operations to play/sync/close events, keeping local database records as the source of truth.
     */
    override val absPlaybackSessionSyncer: AbsPlaybackSessionSyncer

    /**
     * ABS Progress Conflict Coordinator (Remote/local progress arbitration service)
     * Provides playback prompts and upload guards without leaking ABS protocol details into generic progress services.
     */
    override val absProgressConflictCoordinator: AbsProgressConflictCoordinator

    /**
     * ABS Authorized Progress Synchronizer (Reusable remote progress merge service)
     * Pulls authorize.user.mediaProgress for existing ABS books and applies the shared progress conflict rules across startup, manual sync, and worker sync.
     */
    val absAuthorizedProgressSynchronizer: AbsAuthorizedProgressSynchronizer

    /**
     * Application-Level ABS Sync Coordinator (Coordinates background server synchronization)
     * Ensures sync events persist beyond SettingsActivity lifecycle, preventing unexpected interruptions.
     */
    override val absSyncTaskCoordinator: AbsSyncTaskCoordinator
}

@UnstableApi
class DefaultAppContainer(private val context: Context) : AppContainer {

    internal val uiEvents = UiEventGraph()
    internal val data = DataGraph(context)
    internal val media = MediaGraph(context, data)
    internal val library = LibraryGraph(context, data, media, uiEvents)
    internal val abs = AbsGraph(context, data, media, library, uiEvents)

    init {
        // Application Event Bridge Activation (Attach process-level event routing as soon as the container exists)
        // This ensures background services and workers can publish feedback before the first Compose collector starts.
        uiEvents.startEventBridges()
    }

    private val settingsWebDavCredentialStore: WebDavCredentialStore by lazy {
        // Settings WebDAV Credential Store (Shared credential lookup for query use cases)
        // Reuses the same storage adapter as LibraryRootService without exposing it to SettingsViewModel.
        WebDavCredentialStore(context.applicationContext)
    }

    override val settingsRepository: AppSettingsRepository
        get() = data.settingsRepository

    override val appEventSink: AppEventSink
        get() = uiEvents.appEventSink

    override val playbackDomainEventSink: PlaybackDomainEventSink
        get() = uiEvents.playbackDomainEventSink

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
            maintenanceUseCase = settingsLibraryMaintenanceUseCase
        )
    }

    override val webDavConnectionTester: WebDavConnectionTester by lazy {
        // WebDAV Tester Wiring (Shares app settings for insecure TLS selection)
        // The tester hides OkHttp, PROPFIND, and TLS details from the settings presentation layer.
        WebDavConnectionTester(appSettingsRepository = data.settingsRepository)
    }

    override val deleteLibraryRootUseCase: DeleteLibraryRootUseCase
        get() = library.deleteLibraryRootUseCase

    override val deleteBookUseCase: DeleteBookUseCase
        get() = library.deleteBookUseCase

    override val homeLibraryReadModel: HomeLibraryReadModel
        get() = library.homeLibraryReadModel

    override val homeLibraryUseCases: HomeLibraryUseCases
        get() = library.homeLibraryUseCases

    override val libraryFacade: LibraryFacade
        get() = library.libraryFacade

    override val bookQueryGateway: BookQueryGateway
        get() = library.bookQueryGateway

    override val bookAvailabilityGateway: BookAvailabilityGateway
        get() = library.bookAvailabilityGateway

    override val buildPlaybackPlanUseCase: BuildPlaybackPlanUseCase
        get() = library.buildPlaybackPlanUseCase

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

    override val playbackSourcePreflight: PlaybackSourcePreflight
        get() = media.playbackSourcePreflight

    override val playbackManager: com.viel.aplayer.media.PlaybackManager
        get() = data.playbackManager

    override val searchHistoryStore: com.viel.aplayer.data.store.SearchHistoryStore
        get() = data.searchHistoryStore

    override val autoRewindManager: com.viel.aplayer.media.AutoRewindManager
        get() = data.autoRewindManager

    override val absCatalogSynchronizer: AbsCatalogSynchronizer
        get() = abs.absCatalogSynchronizer

    override val absPlaybackSessionSyncer: AbsPlaybackSessionSyncer
        get() = abs.absPlaybackSessionSyncer

    override val absProgressConflictCoordinator: AbsProgressConflictCoordinator
        get() = abs.absProgressConflictCoordinator

    override val absAuthorizedProgressSynchronizer: AbsAuthorizedProgressSynchronizer
        get() = abs.absAuthorizedProgressSynchronizer

    override val absSyncTaskCoordinator: AbsSyncTaskCoordinator
        get() = abs.absSyncTaskCoordinator

    override fun close() {
        // Graph Teardown Delegation (Close graphs in the application lifecycle order)
        // The composition root owns cross-graph order while each graph owns its own initialized resources.
        closeAppGraphsInLifecycleOrder(
            library = library,
            abs = abs,
            uiEvents = uiEvents
        )
    }
}
