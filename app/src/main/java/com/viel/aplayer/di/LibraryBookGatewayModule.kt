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
import java.io.Closeable

/**
 * Local library book-level gateways and availability checker.
 * Replaces the gateway section of LibraryGraph with Koin-managed single definitions.
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

        single { BookAvailabilityGatewayImpl(get<AppDatabase>().bookDao(), get<AppDatabase>().libraryRootDao(), get()) }

        single<BookAvailabilityGateway> { get<BookAvailabilityGatewayImpl>() }

        single {
            BookMetadataGatewayImpl(get<AppDatabase>().bookDao()).also { gateway ->
                GraphClosePolicy.register(
                    stage = GraphClosePolicy.Stage.Library,
                    closeable = Closeable { gateway.close() }
                )
            }
        }

        single<BookMetadataGateway> { get<BookMetadataGatewayImpl>() }

        single<BookmarkGateway> {
            BookmarkGatewayImpl(get<AppDatabase>().bookDao(), get<AppDatabase>().bookmarkDao())
        }

        single {
            ChapterGatewayImpl(get<AppDatabase>().bookDao(), get<AppDatabase>().chapterDao()).also { gateway ->
                GraphClosePolicy.register(
                    stage = GraphClosePolicy.Stage.Library,
                    closeable = Closeable { gateway.close() }
                )
            }
        }

        single<ChapterGateway> {
            get<ChapterGatewayImpl>()
        }

        single<BookDeletionGateway> { BookDeletionGatewayImpl(get<AppDatabase>().bookDao()) }

        single<BookRootInventoryGateway> { BookRootInventoryGatewayImpl(get<AppDatabase>().bookDao()) }

        single {
            BookCatalogGatewayImpl(
                bookDao = get<AppDatabase>().bookDao(),
                coverRecoveryGateway = get()
            )
        }

        single<BookCatalogGateway> { get<BookCatalogGatewayImpl>() }

        single {
            RemotePlaybackCleanupGatewayImpl(
                absPlaybackSessionDao = get<AppDatabase>().absPlaybackSessionDao(),
                absPendingProgressSyncDao = get<AppDatabase>().absPendingProgressSyncDao()
            )
        }

        single<RemotePlaybackCleanupGateway> { get<RemotePlaybackCleanupGatewayImpl>() as RemotePlaybackCleanupGateway }

    }
}
