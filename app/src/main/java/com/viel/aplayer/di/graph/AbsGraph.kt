package com.viel.aplayer.di.graph

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
 * Owns AudiobookShelf credentials, remote clients, catalog sync, and playback session sync.
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
        AbsCredentialStore.getInstance(context.applicationContext)
    }

    val absApiClient by lazy {
        RealAbsApiClient(
            appSettingsRepository = data.settingsRepository,
            credentialStore = absCredentialStore
        )
    }

    val absConnectionTester by lazy {
        AbsConnectionTester(absApiClient)
    }

    val absCoverCache by lazy {
        AbsCoverCache(
            context = context.applicationContext,
            settingsProvider = { data.settingsRepository.cachedSettings }
        )
    }

    val absPlaybackCredentialResolver by lazy {
        AbsPlaybackCredentialResolver(
            libraryRootDao = data.database.libraryRootDao(),
            credentialStore = absCredentialStore
        )
    }

    val absProgressConflictCoordinator: AbsProgressConflictCoordinator by lazy {
        AbsProgressConflictCoordinator(
            apiClient = absApiClient,
            bookCatalogGateway = library.bookCatalogGateway,
            bookMetadataGateway = library.bookMetadataGateway,
            progressGateway = library.progressGateway,
            credentialProvider = { book -> absPlaybackCredentialResolver.resolve(book) }
        )
    }

    val absAuthorizedProgressSynchronizer: AbsAuthorizedProgressSynchronizer by lazy {
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
        AbsCatalogSynchronizer(
            apiClient = absApiClient,
            credentialStore = absCredentialStore,
            catalogStore = data.database.absCatalogDao(),
            authorizedProgressSynchronizer = absAuthorizedProgressSynchronizer
        )
    }

    val absPlaybackSessionSyncer: AbsPlaybackSessionSyncer by lazy {
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
        AbsSyncTaskCoordinator(
            libraryRootDao = data.database.libraryRootDao(),
            synchronizer = absCatalogSynchronizer,
            appEventSink = uiEvents.appEventSink,
            rootPreflight = library.libraryRootGateway::refreshLibraryRootStatus
        )
    }

    /**
     * Preserves lazy background coordinator construction.
     * The backing Lazy lets di teardown close active sync scopes without creating unused remote synchronization machinery.
     */
    val absSyncTaskCoordinator: AbsSyncTaskCoordinator
        get() = absSyncTaskCoordinatorLazy.value

    override fun close() {
        closeInitializedAbsGraphResources(listOf(absSyncTaskCoordinatorLazy))
    }
}
