package com.viel.aplayer.di.koin

import androidx.media3.common.util.UnstableApi
import com.viel.aplayer.abs.auth.AbsCredentialStore
import com.viel.aplayer.abs.net.RealAbsApiClient
import com.viel.aplayer.abs.playback.AbsPlaybackCredentialResolver
import com.viel.aplayer.abs.playback.AbsPlaybackSessionSyncer
import com.viel.aplayer.abs.playback.AbsProgressConflictCoordinator
import com.viel.aplayer.abs.sync.AbsAuthorizedProgressSynchronizer
import com.viel.aplayer.abs.sync.AbsCatalogSynchronizer
import com.viel.aplayer.abs.sync.AbsSyncTaskCoordinator
import com.viel.aplayer.data.book.BookCatalogGateway
import com.viel.aplayer.data.book.BookMetadataGateway
import com.viel.aplayer.data.db.AppDatabase
import com.viel.aplayer.data.progress.ProgressGateway
import com.viel.aplayer.data.root.LibraryRootGateway
import com.viel.aplayer.event.AppEventSink
import org.koin.core.module.Module
import org.koin.dsl.module
import java.io.Closeable

/**
 * ABS catalog sync, playback session sync, progress conflict coordinator, and sync task coordinator.
 * Replaces the sync section of AbsGraph and registers the coordinator for ordered shutdown.
 */
@UnstableApi
internal object AbsSyncModule {

    val module: Module = module {
        single {
            AbsProgressConflictCoordinator(
                apiClient = get<RealAbsApiClient>(),
                bookCatalogGateway = get<BookCatalogGateway>(),
                bookMetadataGateway = get<BookMetadataGateway>(),
                progressGateway = get<ProgressGateway>(),
                credentialProvider = { book -> get<AbsPlaybackCredentialResolver>().resolve(book) }
            )
        }

        single {
            AbsAuthorizedProgressSynchronizer(
                apiClient = get<RealAbsApiClient>(),
                credentialProvider = { root ->
                    val credential = root.credentialId?.let { credentialId ->
                        get<AbsCredentialStore>().get(credentialId)
                    }
                    credential?.let {
                        AbsAuthorizedProgressSynchronizer.CredentialSnapshot(
                            baseUrl = it.baseUrl,
                            token = it.token
                        )
                    }
                },
                bookCatalogGateway = get<BookCatalogGateway>(),
                bookMetadataGateway = get<BookMetadataGateway>(),
                progressGateway = get<ProgressGateway>()
            )
        }

        single {
            AbsCatalogSynchronizer(
                apiClient = get<RealAbsApiClient>(),
                credentialStore = get(),
                catalogStore = get<AppDatabase>().absCatalogDao(),
                authorizedProgressSynchronizer = get()
            )
        }

        single {
            AbsPlaybackSessionSyncer(
                apiClient = get<RealAbsApiClient>(),
                absPlaybackSessionDao = get<AppDatabase>().absPlaybackSessionDao(),
                absPendingProgressSyncDao = get<AppDatabase>().absPendingProgressSyncDao(),
                catalogStore = get<AppDatabase>().absCatalogDao(),
                credentialProvider = { book -> get<AbsPlaybackCredentialResolver>().resolve(book) },
                progressConflictCoordinator = get()
            )
        }

        single {
            AbsSyncTaskCoordinator(
                libraryRootDao = get<AppDatabase>().libraryRootDao(),
                synchronizer = get(),
                appEventSink = get<AppEventSink>(),
                rootPreflight = get<LibraryRootGateway>()::refreshLibraryRootStatus
            ).also { coordinator ->
                GraphClosePolicy.register(
                    stage = GraphClosePolicy.Stage.Abs,
                    closeable = Closeable { coordinator.close() }
                )
            }
        }
    }
}
