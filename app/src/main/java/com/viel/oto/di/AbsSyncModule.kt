package com.viel.oto.di

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import com.viel.oto.abs.auth.AbsCredentialStore
import com.viel.oto.abs.net.AbsApiClient
import com.viel.oto.abs.playback.AbsPlaybackCredentialResolver
import com.viel.oto.abs.playback.AbsPlaybackSessionSyncer
import com.viel.oto.abs.playback.AbsProgressConflictCoordinator
import com.viel.oto.abs.sync.AbsAuthorizedProgressSynchronizer
import com.viel.oto.abs.sync.AbsCatalogSynchronizer
import com.viel.oto.abs.sync.AbsSyncTaskCoordinator
import com.viel.oto.data.book.BookCatalogGateway
import com.viel.oto.data.book.BookMetadataGateway
import com.viel.oto.data.db.AppDatabase
import com.viel.oto.data.progress.ProgressGateway
import com.viel.oto.library.root.LibraryRootGateway
import com.viel.oto.event.AppEventSink
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * ABS catalog sync, playback session sync, progress conflict coordinator, and sync task coordinator.
 * Replaces the sync section of AbsGraph and depends on the AbsApiClient contract rather than
 * the concrete network implementation outside the ABS networking module.
 */
@OptIn(UnstableApi::class)
internal object AbsSyncModule {

    val module: Module = module {
        single {
            AbsProgressConflictCoordinator(
                apiClient = get<AbsApiClient>(),
                bookCatalogGateway = get<BookCatalogGateway>(),
                bookMetadataGateway = get<BookMetadataGateway>(),
                progressGateway = get<ProgressGateway>(),
                credentialProvider = { book -> get<AbsPlaybackCredentialResolver>().resolve(book) }
            )
        }

        single {
            AbsAuthorizedProgressSynchronizer(
                apiClient = get<AbsApiClient>(),
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
                apiClient = get<AbsApiClient>(),
                credentialStore = get(),
                catalogStore = get<AppDatabase>().absCatalogDao(),
                authorizedProgressSynchronizer = get()
            )
        }

        single {
            AbsPlaybackSessionSyncer(
                apiClient = get<AbsApiClient>(),
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
                    closeable = { coordinator.close() }
                )
            }
        }
    }
}
