package com.viel.aplayer.application.di.graph

import android.content.Context
import androidx.media3.common.util.UnstableApi
import com.viel.aplayer.abs.auth.AbsCredentialStore
import com.viel.aplayer.abs.net.RealAbsApiClient
import com.viel.aplayer.abs.playback.AbsPlaybackCredentialResolver
import com.viel.aplayer.abs.playback.AbsPlaybackSessionSyncer
import com.viel.aplayer.abs.playback.AbsProgressConflictCoordinator
import com.viel.aplayer.abs.sync.AbsAuthorizedProgressSynchronizer
import com.viel.aplayer.abs.sync.AbsCatalogSynchronizer
import com.viel.aplayer.abs.sync.AbsConnectionTester
import com.viel.aplayer.abs.sync.AbsCoverCache
import com.viel.aplayer.abs.sync.AbsSyncTaskCoordinator
import java.io.Closeable

/**
 * ABS Graph (Owns Audiobookshelf credentials, remote clients, catalog sync, and playback session sync)
 * Keeps remote protocol construction separate from local library and playback di wiring.
 */
@UnstableApi
internal class AbsGraph(
    val context: Context,
    val data: DataGraph,
    val media: MediaGraph,
    val library: LibraryGraph,
    val uiEvents: UiEventGraph
) : Closeable {
    val absCredentialStore by lazy {
        // ABS Credential Store: Securely manages credentials for Audiobookshelf.
        AbsCredentialStore.getInstance(context.applicationContext)
    }

    val absApiClient by lazy {
        // ABS Client Client: Sends REST calls to Audiobookshelf endpoints.
        RealAbsApiClient(
            appSettingsRepository = data.settingsRepository,
            credentialStore = absCredentialStore
        )
    }

    val absConnectionTester by lazy {
        // ABS Connection Tester: Validates tokens and lists selectable book libraries for settings flows.
        AbsConnectionTester(absApiClient)
    }

    val absCoverCache by lazy {
        // ABS Cover Cache Policy Wiring (Shares the app settings repository with standalone cover downloads)
        // Cover images are fetched outside RealAbsApiClient, so the di injects cached settings to enforce the same cleartext transport policy as catalog and media requests.
        AbsCoverCache(
            context = context.applicationContext,
            settingsProvider = { data.settingsRepository.cachedSettings }
        )
    }

    val absPlaybackCredentialResolver by lazy {
        // ABS Credential Resolver: Resolves credential details for remote playback.
        AbsPlaybackCredentialResolver(
            libraryRootDao = data.database.libraryRootDao(),
            credentialStore = absCredentialStore
        )
    }

    val absProgressConflictCoordinator: AbsProgressConflictCoordinator by lazy {
        // Progress Conflict Coordinator: Arbitrates conflicting play timestamps.
        AbsProgressConflictCoordinator(
            apiClient = absApiClient,
            bookCatalogGateway = library.bookCatalogGateway,
            bookMetadataGateway = library.bookMetadataGateway,
            progressGateway = library.progressGateway,
            credentialProvider = { book -> absPlaybackCredentialResolver.resolve(book) }
        )
    }

    val absAuthorizedProgressSynchronizer: AbsAuthorizedProgressSynchronizer by lazy {
        // Authorized Progress Synchronizer: Merges user progress snapshots for startup and catalog-triggered refreshes.
        AbsAuthorizedProgressSynchronizer(
            apiClient = absApiClient,
            credentialProvider = { root ->
                val credential = root.credentialId?.let { credentialId -> absCredentialStore.get(credentialId) }
                credential?.let { AbsAuthorizedProgressSynchronizer.CredentialSnapshot(baseUrl = it.baseUrl, token = it.token) }
            },
            bookCatalogGateway = library.bookCatalogGateway,
            bookMetadataGateway = library.bookMetadataGateway,
            progressGateway = library.progressGateway
        )
    }

    val absCatalogSynchronizer: AbsCatalogSynchronizer by lazy {
        // ABS Catalog Synchronizer: Mirrors server library catalogs and delegates user progress merging to the shared progress synchronizer.
        AbsCatalogSynchronizer(
            apiClient = absApiClient,
            credentialStore = absCredentialStore,
            catalogStore = data.database.absCatalogDao(),
            authorizedProgressSynchronizer = absAuthorizedProgressSynchronizer
        )
    }

    val absPlaybackSessionSyncer: AbsPlaybackSessionSyncer by lazy {
        // Playback Session Syncer: Submits media play progress intervals to servers.
        AbsPlaybackSessionSyncer(
            apiClient = absApiClient,
            absPlaybackSessionDao = data.database.absPlaybackSessionDao(),
            absPendingProgressSyncDao = data.database.absPendingProgressSyncDao(),
            catalogStore = data.database.absCatalogDao(),
            credentialProvider = { book -> absPlaybackCredentialResolver.resolve(book) },
            progressConflictCoordinator = absProgressConflictCoordinator
        )
    }

    private val absSyncTaskCoordinatorLazy = lazy {
        // Sync Task Coordinator: Coordinates background Audiobookshelf catalog runs.
        AbsSyncTaskCoordinator(
            libraryRootDao = data.database.libraryRootDao(),
            synchronizer = absCatalogSynchronizer,
            appEventSink = uiEvents.appEventSink,
            rootPreflight = library.libraryRootGateway::refreshLibraryRootStatus
        )
    }

    /**
     * ABS Sync Task Coordinator Accessor (Preserves lazy background coordinator construction)
     * The backing Lazy lets di teardown close active sync scopes without creating unused remote synchronization machinery.
     */
    val absSyncTaskCoordinator: AbsSyncTaskCoordinator
        get() = absSyncTaskCoordinatorLazy.value

    override fun close() {
        // Initialized ABS Disposal (Close only background coordination resources that were actually started)
        // This prevents shutdown from allocating ABS sync infrastructure when no ABS work ran in the process.
        closeInitializedAbsGraphResources(listOf(absSyncTaskCoordinatorLazy))
    }
}
