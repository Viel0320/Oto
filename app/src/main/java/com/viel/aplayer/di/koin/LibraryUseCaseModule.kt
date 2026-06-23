package com.viel.aplayer.di.koin

import androidx.media3.common.util.UnstableApi
import com.viel.aplayer.application.download.ManualDownloadCleanupGateway
import com.viel.aplayer.application.playback.PlaybackStopper
import com.viel.aplayer.application.usecase.BookManagementUseCase
import com.viel.aplayer.application.usecase.BuildPlaybackPlanUseCase
import com.viel.aplayer.application.usecase.LibraryRootManagementUseCase
import com.viel.aplayer.data.availability.BookAvailabilityGateway
import com.viel.aplayer.data.book.BookCatalogGateway
import com.viel.aplayer.data.book.BookDeletionGateway
import com.viel.aplayer.data.book.BookRootInventoryGateway
import com.viel.aplayer.data.cleanup.LibraryResourceCleanupGateway
import com.viel.aplayer.data.cleanup.RemotePlaybackCleanupGatewayImpl
import com.viel.aplayer.data.cover.CoverRecoveryHelper
import com.viel.aplayer.data.cover.CoverUriResolver
import com.viel.aplayer.data.db.AppDatabase
import com.viel.aplayer.data.progress.ProgressGateway
import com.viel.aplayer.data.progress.ProgressGatewayImpl
import com.viel.aplayer.data.root.LibraryRootGateway
import com.viel.aplayer.data.search.SearchHistoryGateway
import com.viel.aplayer.data.search.SearchHistoryGatewayImpl
import com.viel.aplayer.data.store.SearchHistoryStore
import com.viel.aplayer.media.PlaybackPlanGateway
import com.viel.aplayer.media.PlaybackPlanGatewayImpl
import org.koin.core.module.Module
import org.koin.dsl.module
import java.io.Closeable

/**
 * Playback plan, progress, metadata, subtitle, search history gateways, and management use cases.
 * Replaces the use-case section of LibraryGraph.
 */
@UnstableApi
internal object LibraryUseCaseModule {

    val module: Module = module {
        single<PlaybackPlanGateway> {
            PlaybackPlanGatewayImpl(
                coverUriResolver = get<CoverUriResolver>(),
                bookDao = get<AppDatabase>().bookDao(),
                coverRecoveryHelper = get<CoverRecoveryHelper>()
            )
        }

        single { BuildPlaybackPlanUseCase(get<PlaybackPlanGateway>()) }

        single {
            ProgressGatewayImpl(get<AppDatabase>().bookDao()).also { gateway ->
                GraphClosePolicy.register(
                    stage = GraphClosePolicy.Stage.Library,
                    closeable = Closeable { gateway.close() }
                )
            }
        }

        single<ProgressGateway> { get<ProgressGatewayImpl>() }

        single<SearchHistoryGateway> {
            SearchHistoryGatewayImpl(searchHistoryStore = get<SearchHistoryStore>())
        }

        single<LibraryRootManagementUseCase> {
            LibraryRootManagementUseCase(
                playbackStopper = get<PlaybackStopper>(),
                bookCatalogGateway = get<BookCatalogGateway>(),
                bookRootInventoryGateway = get<BookRootInventoryGateway>(),
                libraryRootGateway = get<LibraryRootGateway>(),
                manualDownloadCleanupGateway = get<ManualDownloadCleanupGateway>(),
                libraryResourceCleanupGateway = get<LibraryResourceCleanupGateway>()
            )
        }

        single<BookManagementUseCase> {
            BookManagementUseCase(
                playbackStopper = get<PlaybackStopper>(),
                bookAvailabilityGateway = get<BookAvailabilityGateway>(),
                bookDeletionGateway = get<BookDeletionGateway>(),
                remotePlaybackCleanupGateway = get<RemotePlaybackCleanupGatewayImpl>(),
                manualDownloadCleanupGateway = get<ManualDownloadCleanupGateway>()
            )
        }
    }
}
