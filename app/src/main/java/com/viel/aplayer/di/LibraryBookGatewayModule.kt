package com.viel.aplayer.di

import androidx.media3.common.util.UnstableApi
import com.viel.aplayer.data.availability.BookAvailabilityGateway
import com.viel.aplayer.data.availability.BookAvailabilityGatewayImpl
import com.viel.aplayer.data.book.BookCatalogGateway
import com.viel.aplayer.data.book.BookCatalogGatewayImpl
import com.viel.aplayer.data.book.BookDeletionGateway
import com.viel.aplayer.data.book.BookDeletionGatewayImpl
import com.viel.aplayer.data.book.BookMetadataGateway
import com.viel.aplayer.data.book.BookMetadataGatewayImpl
import com.viel.aplayer.data.book.BookRootInventoryGateway
import com.viel.aplayer.data.book.BookRootInventoryGatewayImpl
import com.viel.aplayer.data.book.BookmarkGateway
import com.viel.aplayer.data.book.BookmarkGatewayImpl
import com.viel.aplayer.data.book.ChapterGateway
import com.viel.aplayer.data.book.ChapterGatewayImpl
import com.viel.aplayer.data.cleanup.RemotePlaybackCleanupGateway
import com.viel.aplayer.data.cleanup.RemotePlaybackCleanupGatewayImpl
import com.viel.aplayer.data.db.AppDatabase
import com.viel.aplayer.library.availability.AvailabilityChecker
import com.viel.aplayer.library.availability.MissingBookFileRecoveryChecker
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * Local library book-level gateways and availability checker.
 * Replaces the gateway section of LibraryGraph with Koin-managed single definitions.
 * Gateway interfaces are registered directly so release dependency resolution keeps one factory
 * path per contract.
 */
@UnstableApi
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
            BookMetadataGatewayImpl(get<AppDatabase>().bookDao()).also { gateway ->
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
            ChapterGatewayImpl(get<AppDatabase>().bookDao(), get<AppDatabase>().chapterDao()).also { gateway ->
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
