package com.viel.oto.di

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import com.viel.oto.data.availability.BookAvailabilityGateway
import com.viel.oto.data.availability.BookAvailabilityGatewayImpl
import com.viel.oto.data.book.BookCatalogGateway
import com.viel.oto.data.book.BookCatalogGatewayImpl
import com.viel.oto.data.book.BookDeletionGateway
import com.viel.oto.data.book.BookDeletionGatewayImpl
import com.viel.oto.data.book.BookMetadataGateway
import com.viel.oto.data.book.BookMetadataGatewayImpl
import com.viel.oto.data.book.BookRootInventoryGateway
import com.viel.oto.data.book.BookRootInventoryGatewayImpl
import com.viel.oto.data.book.BookmarkGateway
import com.viel.oto.data.book.BookmarkGatewayImpl
import com.viel.oto.data.book.ChapterGateway
import com.viel.oto.data.book.ChapterGatewayImpl
import com.viel.oto.data.cleanup.RemotePlaybackCleanupGateway
import com.viel.oto.data.cleanup.RemotePlaybackCleanupGatewayImpl
import com.viel.oto.data.db.AppDatabase
import com.viel.oto.library.availability.AvailabilityChecker
import com.viel.oto.library.availability.MissingBookFileRecoveryChecker
import com.viel.oto.logger.SecureDiagnosticLogSink
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * Local library book-level gateways and availability checker.
 * Replaces the gateway section of LibraryGraph with Koin-managed single definitions.
 * Gateway interfaces are registered directly so release dependency resolution keeps one factory
 * path per contract.
 */
@OptIn(UnstableApi::class)
internal object LibraryBookGatewayModule {

    val module: Module = module {
        single {
            AvailabilityChecker(
                context = get(),
                absCredentialStore = get(),
                appSettingsRepository = get(),
                database = get<AppDatabase>()
            )
        }

        single { MissingBookFileRecoveryChecker(get<AppDatabase>(), get()) }

        single<BookAvailabilityGateway> {
            BookAvailabilityGatewayImpl(get<AppDatabase>().bookDao(), get<AppDatabase>().libraryRootDao(), get())
        }

        single<BookMetadataGateway> {
            BookMetadataGatewayImpl(
                bookDao = get<AppDatabase>().bookDao(),
                diagnosticLogSink = SecureDiagnosticLogSink
            ).also { gateway ->
                GraphClosePolicy.register(
                    stage = GraphClosePolicy.Stage.Library,
                    closeable = { gateway.close() }
                )
            }
        }

        single<BookmarkGateway> {
            BookmarkGatewayImpl(get<AppDatabase>().bookDao(), get<AppDatabase>().bookmarkDao())
        }

        single<ChapterGateway> {
            ChapterGatewayImpl(
                bookDao = get<AppDatabase>().bookDao(),
                chapterDao = get<AppDatabase>().chapterDao(),
                diagnosticLogSink = SecureDiagnosticLogSink
            ).also { gateway ->
                GraphClosePolicy.register(
                    stage = GraphClosePolicy.Stage.Library,
                    closeable = { gateway.close() }
                )
            }
        }

        single<BookDeletionGateway> { BookDeletionGatewayImpl(get<AppDatabase>().bookDao()) }

        single<BookRootInventoryGateway> { BookRootInventoryGatewayImpl(get<AppDatabase>().bookDao()) }

        single<BookCatalogGateway> {
            BookCatalogGatewayImpl(
                bookDao = get<AppDatabase>().bookDao(),
                coverRecoveryGateway = get()
            )
        }

        single<RemotePlaybackCleanupGateway> {
            RemotePlaybackCleanupGatewayImpl(
                absPlaybackSessionDao = get<AppDatabase>().absPlaybackSessionDao(),
                absPendingProgressSyncDao = get<AppDatabase>().absPendingProgressSyncDao()
            )
        }

    }
}
