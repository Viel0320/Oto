package com.viel.aplayer.di.dependencies

import com.viel.aplayer.application.download.CacheMaintenanceCommands
import com.viel.aplayer.application.download.CacheStatisticsProvider
import com.viel.aplayer.application.download.DownloadController
import com.viel.aplayer.application.download.DownloadManagementReadModel
import com.viel.aplayer.application.download.DownloadStatusReadModel
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
import com.viel.aplayer.application.library.settings.AppSettingsCommands
import com.viel.aplayer.application.library.settings.AppSettingsReadModel
import com.viel.aplayer.application.library.settings.SettingsRootCommands
import com.viel.aplayer.application.library.settings.SettingsRootReadModel
import com.viel.aplayer.application.playback.PlayerPlaybackController
import com.viel.aplayer.application.usecase.AbsSettingsConnectionUseCase
import com.viel.aplayer.application.usecase.BookManagementUseCase
import com.viel.aplayer.application.usecase.BuildPlaybackPlanUseCase
import com.viel.aplayer.application.usecase.ExportUserDataUseCase
import com.viel.aplayer.application.usecase.FormatSettingsRootUseCase
import com.viel.aplayer.application.usecase.ImportUserDataUseCase
import com.viel.aplayer.application.usecase.LibraryRootManagementUseCase
import com.viel.aplayer.application.usecase.ResolveProgressConflictUseCase
import com.viel.aplayer.application.usecase.SettingsLibraryMaintenanceUseCase
import com.viel.aplayer.application.usecase.SettingsQueryUseCase
import com.viel.aplayer.application.usecase.TestWebDavConnectionUseCase
import com.viel.aplayer.event.AppEventSink

/**
 * Search-scene dependency view.
 * Exposes only the search read model and history commands required by SearchViewModel.
 */
interface SearchScreenDependencies {
    /**
     * Search-scoped query and history stream.
     * Lets SearchViewModel observe search data without depending on the retired broad library seam.
     */
    val searchLibraryReadModel: SearchLibraryReadModel

    /**
     * Search-scoped history mutations.
     * Routes search-history writes through a small scene interface instead of the all-capability library facade.
     */
    val searchLibraryCommands: SearchLibraryCommands
}

/**
 * Detail-scene dependency view.
 * Exposes only detail source, live snapshot, and availability interfaces required by DetailViewModel.
 */
interface DetailScreenDependencies {
    /**
     * Detail-scoped read surface.
     * Resolves source labels and observes live book updates without exposing file or root gateways to the UI layer.
     */
    val detailBookReadModel: DetailBookReadModel

    /**
     * Detail-scoped mutation surface.
     * Routes availability refreshes through a small scene command interface instead of a broad library facade.
     */
    val detailBookCommands: DetailBookCommands

    /**
     * Detail-scoped manual cache state.
     * The detail page observes BookCacheStatus instead of reading download metadata rows or Media3 state.
     */
    val downloadStatusReadModel: DownloadStatusReadModel

    /**
     * Detail-scoped manual cache commands.
     * The route performs notification permission preflight before the ViewModel invokes this book-level command surface.
     */
    val downloadController: DownloadController

    /**
     * Detail feedback command seam.
     * Download command outcomes are emitted as resource-backed feedback facts from the ViewModel.
     */
    val appEventSink: AppEventSink
}

/**
 * Home-library screen dependency view.
 * Groups the home read model, settings, book deletion use case, and feedback sink consumed by LibraryViewModel.
 */
interface HomeScreenDependencies {
    /**
     * Scene-level audiobook catalog stream.
     * Lets LibraryViewModel build home UI state without receiving the full library method bus.
     */
    val homeLibraryReadModel: HomeLibraryReadModel

    /**
     * Scene-level home catalog commands.
     * Keeps home root import, scan scheduling, read-status, metadata refresh, and history clearing behind a small home interface.
     */
    val homeLibraryUseCases: HomeLibraryUseCases

    val settingsReadModel: AppSettingsReadModel
    val settingsCommands: AppSettingsCommands

    /**
     * Home feedback command seam.
     * Routes deletion and metadata rebuild messages through the app shell renderer.
     */
    val appEventSink: AppEventSink

    /**
     * Home book-removal coordinator.
     * Lets the home screen remove a book while the application layer clears cover and manual-download resources first.
     */
    val bookManagementUseCase: BookManagementUseCase
}

/**
 * Settings-page dependency view.
 * Collects settings root scene interfaces, connection operations, maintenance use cases, and feedback sink required by SettingsViewModel.
 */
interface SettingsScreenDependencies {
    val formatSettingsRootUseCase: FormatSettingsRootUseCase

    /**
     * Settings-scoped root display stream.
     * Gives SettingsViewModel root display snapshots without reopening the broad library transition entry point.
     */
    val settingsRootReadModel: SettingsRootReadModel

    /**
     * Settings-scoped root management operations.
     * Routes root registration, status refresh, and manual scan triggers through the settings-root module.
     */
    val settingsRootCommands: SettingsRootCommands

    /**
     * Settings-scoped recoverable book stream.
     * Gives the settings recovery page a focused list feed without exposing the full home catalog.
     */
    val deletedBookRecoveryReadModel: DeletedBookRecoveryReadModel

    /**
     * Settings-scoped restore operations.
     * Routes soft-delete restore workflows through the recovery scene instead of SettingsRootCommands.
     */
    val deletedBookRecoveryCommands: DeletedBookRecoveryCommands

    val settingsReadModel: AppSettingsReadModel
    val settingsCommands: AppSettingsCommands

    /**
     * Settings-hosted manual download task stream.
     * The settings overlay lists manual cache tasks without reaching into Room or Media3 state.
     */
    val downloadManagementReadModel: DownloadManagementReadModel

    /**
     * Settings-hosted manual download commands.
     * The management page reuses the book-level command surface for pause, resume, and cache deletion.
     */
    val downloadController: DownloadController

    /**
     * Settings-hosted cache size summary.
     * Cache settings can show manual-cache totals without receiving cache handles or eviction internals.
     */
    val cacheStatisticsProvider: CacheStatisticsProvider

    /**
     * Settings-hosted cache cleanup operations.
     * Destructive cache actions stay behind a small application command surface instead of exposing Media3 caches to UI code.
     */
    val cacheMaintenanceCommands: CacheMaintenanceCommands

    /**
     * Settings read model seam.
     * Provides roots, book counts, sync state, and credentials as application snapshots.
     */
    val settingsQueryUseCase: SettingsQueryUseCase

    /**
     * Root edit follow-up seam.
     * Centralizes cache eviction, missing-file recovery, and rescan scheduling after settings edits.
     */
    val settingsLibraryMaintenanceUseCase: SettingsLibraryMaintenanceUseCase

    /**
     * ABS login and root registration seam.
     * Keeps token reuse, credential storage, and ABS root registration behind one settings operation.
     */
    val absSettingsConnectionUseCase: AbsSettingsConnectionUseCase

    val testWebDavConnectionUseCase: TestWebDavConnectionUseCase

    /**
     * Settings feedback command seam.
     * Routes connection, scan, and deletion messages through the shared app shell renderer.
     */
    val appEventSink: AppEventSink

    /**
     * Settings root-removal and ABS-switch coordinator.
     * Ensures root deletion and ABS library switching clear root caches and manual downloads before cascade deletion.
     */
    val libraryRootManagementUseCase: LibraryRootManagementUseCase

    val exportUserDataUseCase: ExportUserDataUseCase
    val importUserDataUseCase: ImportUserDataUseCase
}

/**
 * Player UI dependency view.
 * Gives PlayerViewModel player scene interfaces, settings, startup use case, ABS conflict coordinator, and feedback sink it consumes.
 */
interface PlayerScreenDependencies {
    /**
     * Player-scoped metadata and recovery reads.
     * Keeps PlayerViewModel, MediaPlaybackDelegate, and subtitle loading off the broad library transition facade.
     */
    val playerLibraryReadModel: PlayerLibraryReadModel

    /**
     * Player-scoped bookmark mutations.
     * Lets BookmarkManager add, edit, and delete bookmarks through a compact scene command surface.
     */
    val playerBookmarkCommands: PlayerBookmarkCommands

    val settingsReadModel: AppSettingsReadModel
    val settingsCommands: AppSettingsCommands

    val resolveProgressConflictUseCase: ResolveProgressConflictUseCase

    /**
     * Player feedback command seam.
     * Routes player-screen tips through the app shell renderer.
     */
    val appEventSink: AppEventSink

    /**
     * Player playback-start command seam.
     * Keeps PlayerViewModel on an application operation instead of media-core gateway details.
     */
    val buildPlaybackPlanUseCase: BuildPlaybackPlanUseCase

    /**
     * Player-scene playback runtime seam.
     * Lets PlayerViewModel and helper classes control playback without resolving media singletons from Context.
     */
    val playerPlaybackController: PlayerPlaybackController
}

/**
 * Edit-page dependency view.
 * Gives EditBookViewModel only editable-book reads and edit commands for the metadata editing flow.
 */
interface EditScreenDependencies {
    /**
     * Edit-scoped selected book lookup.
     * Lets EditBookViewModel load the target book without resolving the library presentation facade.
     */
    val editBookReadModel: EditBookReadModel

    /**
     * Edit-scoped metadata and cover persistence.
     * Routes text metadata and custom cover writes through the edit scene module.
     */
    val editBookCommands: EditBookCommands
}

/**
 * Remote-connection scene dependency view.
 * Exposes only the connection-test, ABS login, root registration, and maintenance seams that the
 * app-level RemoteConnectionViewModel needs to add or edit WebDAV/ABS/SAF library roots, kept
 * separate from the broader settings surface so the connection flow no longer depends on settings.
 */
interface RemoteConnectionDependencies {
    val absSettingsConnectionUseCase: AbsSettingsConnectionUseCase
    val testWebDavConnectionUseCase: TestWebDavConnectionUseCase
    val settingsQueryUseCase: SettingsQueryUseCase
    val settingsRootCommands: SettingsRootCommands
    val formatSettingsRootUseCase: FormatSettingsRootUseCase
    val settingsLibraryMaintenanceUseCase: SettingsLibraryMaintenanceUseCase
    val appEventSink: AppEventSink
}
