package com.viel.aplayer.dependencies

import com.viel.aplayer.abs.playback.AbsProgressConflictCoordinator
import com.viel.aplayer.abs.sync.AbsCatalogSynchronizer
import com.viel.aplayer.abs.sync.AbsSyncTaskCoordinator
import com.viel.aplayer.data.AppSettingsRepository
import com.viel.aplayer.data.LibraryFacade
import com.viel.aplayer.domain.usecase.AbsSettingsConnectionUseCase
import com.viel.aplayer.domain.usecase.BuildPlaybackPlanUseCase
import com.viel.aplayer.domain.usecase.DeleteBookUseCase
import com.viel.aplayer.domain.usecase.DeleteLibraryRootUseCase
import com.viel.aplayer.domain.usecase.SettingsLibraryMaintenanceUseCase
import com.viel.aplayer.domain.usecase.SettingsQueryUseCase
import com.viel.aplayer.event.AppEventSink
import com.viel.aplayer.library.readmodel.HomeLibraryReadModel
import com.viel.aplayer.library.readmodel.HomeLibraryUseCases
import com.viel.aplayer.library.vfs.sourceProvider.webdav.WebDavConnectionTester

/**
 * Library Presentation Dependencies (Shared read/write facade view for library screens)
 * Gives simple book detail, search, and edit screens only the high-level library facade they already use.
 */
interface LibraryPresentationDependencies {
    /**
     * UI-Facing Library Facade (Unified entry point for presentation callers)
     * Keeps simple library screens away from granular gateways and container lifecycle entries.
     */
    val libraryFacade: LibraryFacade
}

/**
 * Home Screen Dependencies (Home-library screen dependency view)
 * Groups the home read model, settings, deletion use cases, and feedback sink consumed by LibraryViewModel.
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

    /**
     * Settings Repository (Home display preference source)
     * Supplies library layout and filter preferences without exposing settings-only maintenance operations.
     */
    val settingsRepository: AppSettingsRepository

    /**
     * Application Event Sink (Home feedback command seam)
     * Routes deletion and metadata rebuild messages through the app shell renderer.
     */
    val appEventSink: AppEventSink

    /**
     * Library Root Deletion Use Case (Home root-removal coordinator)
     * Lets the home screen remove roots safely without importing playback or root gateway internals.
     */
    val deleteLibraryRootUseCase: DeleteLibraryRootUseCase

    /**
     * Delete Book Use Case (Home book-removal coordinator)
     * Lets the home screen remove a book without reaching into availability gateways directly.
     */
    val deleteBookUseCase: DeleteBookUseCase
}

/**
 * Settings Screen Dependencies (Settings-page dependency view)
 * Collects the settings read models, connection operations, maintenance use cases, and feedback sink required by SettingsViewModel.
 */
interface SettingsScreenDependencies : LibraryPresentationDependencies {
    /**
     * Settings Repository (Settings persistence source)
     * Provides the reactive app settings flow and cached startup settings.
     */
    val settingsRepository: AppSettingsRepository

    /**
     * ABS Catalog Synchronizer (Manual preview and catalog sync dependency)
     * Supports settings-side ABS operations without exposing playback session syncers.
     */
    val absCatalogSynchronizer: AbsCatalogSynchronizer

    /**
     * Application-Level ABS Sync Coordinator (Settings-triggered background sync scheduler)
     * Lets settings enqueue ABS sync work while hiding worker implementation details.
     */
    val absSyncTaskCoordinator: AbsSyncTaskCoordinator

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

    /**
     * WebDAV Connection Tester (Remote endpoint preflight seam)
     * Keeps TLS, PROPFIND, and credential checks outside SettingsViewModel.
     */
    val webDavConnectionTester: WebDavConnectionTester

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
 * Gives PlayerViewModel the facade, settings, startup use case, ABS conflict coordinator, and feedback sink it consumes.
 */
interface PlayerScreenDependencies : LibraryPresentationDependencies {
    /**
     * Settings Repository (Player preference source)
     * Supplies playback UI and behavior preferences without exposing settings maintenance use cases.
     */
    val settingsRepository: AppSettingsRepository

    /**
     * ABS Progress Conflict Coordinator (Player remote-progress arbitration seam)
     * Lets player UI inspect remote/local conflicts without seeing ABS catalog or worker modules.
     */
    val absProgressConflictCoordinator: AbsProgressConflictCoordinator

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
}
