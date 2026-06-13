package com.viel.aplayer.dependencies

import com.viel.aplayer.application.library.detail.DetailBookReadModel
import com.viel.aplayer.application.library.detail.DetailBookCommands
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
import com.viel.aplayer.application.library.settings.SettingsRootCommands
import com.viel.aplayer.application.library.settings.SettingsRootReadModel
import com.viel.aplayer.application.library.settings.AppSettingsReadModel
import com.viel.aplayer.application.library.settings.AppSettingsCommands
import com.viel.aplayer.application.playback.PlayerPlaybackController
import com.viel.aplayer.application.usecase.AbsSettingsConnectionUseCase
import com.viel.aplayer.application.usecase.BuildPlaybackPlanUseCase
import com.viel.aplayer.application.usecase.DeleteBookUseCase
import com.viel.aplayer.application.usecase.DeleteLibraryRootUseCase
import com.viel.aplayer.application.usecase.SettingsLibraryMaintenanceUseCase
import com.viel.aplayer.application.usecase.SettingsQueryUseCase
import com.viel.aplayer.application.usecase.TestWebDavConnectionUseCase
import com.viel.aplayer.application.usecase.ResolveProgressConflictUseCase
import com.viel.aplayer.event.AppEventSink

/**
 * Search Screen Dependencies (Search-scene dependency view)
 * Exposes only the search read model and history commands required by SearchViewModel.
 */
interface SearchScreenDependencies {
    /**
     * Search Library Read Model (Search-scoped query and history stream)
     * Lets SearchViewModel observe search data without depending on the retired broad library seam.
     */
    val searchLibraryReadModel: SearchLibraryReadModel

    /**
     * Search Library Commands (Search-scoped history mutations)
     * Routes search-history writes through a small scene interface instead of the all-capability library facade.
     */
    val searchLibraryCommands: SearchLibraryCommands
}

/**
 * Detail Screen Dependencies (Detail-scene dependency view)
 * Exposes only detail source, live snapshot, and availability interfaces required by DetailViewModel.
 */
interface DetailScreenDependencies {
    /**
     * Detail Book Read Model (Detail-scoped read surface)
     * Resolves source labels and observes live book updates without exposing file or root gateways to the UI layer.
     */
    val detailBookReadModel: DetailBookReadModel

    /**
     * Detail Book Commands (Detail-scoped mutation surface)
     * Routes availability refreshes through a small scene command interface instead of a broad library facade.
     */
    val detailBookCommands: DetailBookCommands
}

/**
 * Home Screen Dependencies (Home-library screen dependency view)
 * Groups the home read model, settings, book deletion use case, and feedback sink consumed by LibraryViewModel.
 */
interface HomeScreenDependencies {
    /**
     * Home Library Read Model (Scene-level audiobook catalog stream)
     * Lets LibraryViewModel build home UI state without receiving the full library method bus.
     */
    val homeLibraryReadModel: HomeLibraryReadModel

    /**
     * Home Library Use Cases (Scene-level home catalog commands)
     * Keeps home root import, scan scheduling, read-status, metadata refresh, and history clearing behind a small home interface.
     */
    val homeLibraryUseCases: HomeLibraryUseCases

    // Title: AppSettings Abstractions for Home (Provide settings queries and commands to LibraryViewModel)
    // Exposing read model and command interfaces prevents the home scene from directly referencing concrete storage.
    val settingsReadModel: AppSettingsReadModel
    val settingsCommands: AppSettingsCommands

    /**
     * Application Event Sink (Home feedback command seam)
     * Routes deletion and metadata rebuild messages through the app shell renderer.
     */
    val appEventSink: AppEventSink

    /**
     * Delete Book Use Case (Home book-removal coordinator)
     * Lets the home screen remove a book without reaching into availability gateways directly.
     */
    val deleteBookUseCase: DeleteBookUseCase
}

/**
 * Settings Screen Dependencies (Settings-page dependency view)
 * Collects settings root scene interfaces, connection operations, maintenance use cases, and feedback sink required by SettingsViewModel.
 */
interface SettingsScreenDependencies {
    /**
     * Settings Root Read Model (Settings-scoped root display stream)
     * Gives SettingsViewModel root display snapshots without reopening the broad library transition entry point.
     */
    val settingsRootReadModel: SettingsRootReadModel

    /**
     * Settings Root Commands (Settings-scoped root management operations)
     * Routes root registration, status refresh, and manual scan triggers through the settings-root module.
     */
    val settingsRootCommands: SettingsRootCommands

    /**
     * Deleted Book Recovery Read Model (Settings-scoped recoverable book stream)
     * Gives the settings recovery page a focused list feed without exposing the full home catalog.
     */
    val deletedBookRecoveryReadModel: DeletedBookRecoveryReadModel

    /**
     * Deleted Book Recovery Commands (Settings-scoped restore operations)
     * Routes soft-delete restore workflows through the recovery scene instead of SettingsRootCommands.
     */
    val deletedBookRecoveryCommands: DeletedBookRecoveryCommands

    // Title: AppSettings Abstractions for Settings (Provide settings queries and commands to SettingsViewModel)
    // Restricting dependencies to read/write abstractions decouples settings components from the concrete storage class.
    val settingsReadModel: AppSettingsReadModel
    val settingsCommands: AppSettingsCommands

    /**
     * Settings Query Use Case (Settings read model seam)
     * Provides roots, book counts, sync state, and credentials as application snapshots.
     */
    val settingsQueryUseCase: SettingsQueryUseCase

    /**
     * Settings Library Maintenance Use Case (Root edit follow-up seam)
     * Centralizes cache eviction, missing-file recovery, and rescan scheduling after settings edits.
     */
    val settingsLibraryMaintenanceUseCase: SettingsLibraryMaintenanceUseCase

    /**
     * Settings ABS Connection Use Case (ABS login and root registration seam)
     * Keeps token reuse, credential storage, and ABS root registration behind one settings operation.
     */
    val absSettingsConnectionUseCase: AbsSettingsConnectionUseCase

    // Title: WebDavConnectionTester Decoupling (Expose TestWebDavConnectionUseCase instead of the concrete tester)
    // Prevents UI settings ViewModel from directly referencing WebDavConnectionTester from library/vfs.
    val testWebDavConnectionUseCase: TestWebDavConnectionUseCase

    /**
     * Application Event Sink (Settings feedback command seam)
     * Routes connection, scan, and deletion messages through the shared app shell renderer.
     */
    val appEventSink: AppEventSink

    /**
     * Library Root Deletion Use Case (Settings root-removal coordinator)
     * Ensures root deletion coordinates playback shutdown and data cleanup through the application use case.
     */
    val deleteLibraryRootUseCase: DeleteLibraryRootUseCase
}

/**
 * Player Screen Dependencies (Player UI dependency view)
 * Gives PlayerViewModel player scene interfaces, settings, startup use case, ABS conflict coordinator, and feedback sink it consumes.
 */
interface PlayerScreenDependencies {
    /**
     * Player Library Read Model (Player-scoped metadata and recovery reads)
     * Keeps PlayerViewModel, MediaPlaybackDelegate, and subtitle loading off the broad library transition facade.
     */
    val playerLibraryReadModel: PlayerLibraryReadModel

    /**
     * Player Bookmark Commands (Player-scoped bookmark mutations)
     * Lets BookmarkManager add, edit, and delete bookmarks through a compact scene command surface.
     */
    val playerBookmarkCommands: PlayerBookmarkCommands

    // Title: AppSettings Abstractions for Player (Provide settings queries and commands to Player UI VM)
    // Limits the player dependencies to settings abstractions to decouple playback preferences from storage implementations.
    val settingsReadModel: AppSettingsReadModel
    val settingsCommands: AppSettingsCommands

    // Title: AbsProgressConflictCoordinator Decoupling (Expose ResolveProgressConflictUseCase instead of the coordinator)
    // Prevents player UI from directly referencing AbsProgressConflictCoordinator from infrastructure.
    val resolveProgressConflictUseCase: ResolveProgressConflictUseCase

    /**
     * Application Event Sink (Player feedback command seam)
     * Routes player-screen tips through the app shell renderer.
     */
    val appEventSink: AppEventSink

    /**
     * Build Playback Plan Use Case (Player playback-start command seam)
     * Keeps PlayerViewModel on an application operation instead of media-core gateway details.
     */
    val buildPlaybackPlanUseCase: BuildPlaybackPlanUseCase

    /**
     * Player Playback Controller (Player-scene playback runtime seam)
     * Lets PlayerViewModel and helper classes control playback without resolving media singletons from Context.
     */
    val playerPlaybackController: PlayerPlaybackController
}

/**
 * Edit Screen Dependencies (Edit-page dependency view)
 * Gives EditBookViewModel only editable-book reads and edit commands for the metadata editing flow.
 */
interface EditScreenDependencies {
    /**
     * Edit Book Read Model (Edit-scoped selected book lookup)
     * Lets EditBookViewModel load the target book without resolving the library presentation facade.
     */
    val editBookReadModel: EditBookReadModel

    /**
     * Edit Book Commands (Edit-scoped metadata and cover persistence)
     * Routes text metadata and custom cover writes through the edit scene module.
     */
    val editBookCommands: EditBookCommands
}
