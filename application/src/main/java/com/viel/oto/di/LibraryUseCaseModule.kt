package com.viel.oto.di

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import com.viel.oto.application.download.DownloadStatusReadModel
import com.viel.oto.application.download.ManualDownloadCleanupGateway
import com.viel.oto.application.playback.PlaybackStopper
import com.viel.oto.application.usecase.BookManagementUseCase
import com.viel.oto.application.usecase.BuildPlaybackPlanUseCase
import com.viel.oto.application.usecase.LibraryRootManagementUseCase
import com.viel.oto.data.availability.BookAvailabilityGateway
import com.viel.oto.data.book.BookCatalogGateway
import com.viel.oto.data.book.BookDeletionGateway
import com.viel.oto.data.book.BookRootInventoryGateway
import com.viel.oto.data.cleanup.LibraryResourceCleanupGateway
import com.viel.oto.data.cleanup.RemotePlaybackCleanupGateway
import com.viel.oto.data.cover.CoverRecoveryHelper
import com.viel.oto.data.cover.CoverUriResolver
import com.viel.oto.data.db.AppDatabase
import com.viel.oto.data.progress.ProgressGateway
import com.viel.oto.data.progress.ProgressGatewayImpl
import com.viel.oto.library.root.LibraryRootGateway
import com.viel.oto.data.search.SearchHistoryGateway
import com.viel.oto.data.search.SearchHistoryGatewayImpl
import com.viel.oto.data.store.SearchHistoryStore
import com.viel.oto.media.PlaybackPlanGateway
import com.viel.oto.media.PlaybackPlanGatewayImpl
import com.viel.oto.logger.PlaybackWorkflowLogSink
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * Playback plan, progress, metadata, subtitle, search history gateways, and management use cases.
 * Replaces the use-case section of LibraryGraph. Gateway contracts are registered directly so
 * release builds keep a single factory path for each contract.
 */
@OptIn(UnstableApi::class)
object LibraryUseCaseModule {

    val module: Module = module {
        single<PlaybackPlanGateway> {
            PlaybackPlanGatewayImpl(
                coverUriResolver = get<CoverUriResolver>(),
                bookDao = get<AppDatabase>().bookDao(),
                coverRecoveryHelper = get<CoverRecoveryHelper>()
            )
        }

        single {
            BuildPlaybackPlanUseCase(
                playbackPlanGateway = get<PlaybackPlanGateway>(),
                downloadStatusReadModel = get<DownloadStatusReadModel>()
            )
        }

        single<ProgressGateway> {
            ProgressGatewayImpl(
                bookDao = get<AppDatabase>().bookDao(),
                workflowLogSink = PlaybackWorkflowLogSink
            ).also { gateway ->
                GraphClosePolicy.register(
                    stage = GraphClosePolicy.Stage.Library,
                    closeable = { gateway.close() }
                )
            }
        }

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
                remotePlaybackCleanupGateway = get<RemotePlaybackCleanupGateway>(),
                manualDownloadCleanupGateway = get<ManualDownloadCleanupGateway>()
            )
        }
    }
}
