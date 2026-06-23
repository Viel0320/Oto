package com.viel.aplayer.di

import androidx.media3.common.util.UnstableApi
import com.viel.aplayer.application.library.detail.DefaultDetailBookModule
import com.viel.aplayer.application.library.detail.DetailBookCommands
import com.viel.aplayer.application.library.detail.DetailBookReadModel
import com.viel.aplayer.application.library.edit.DefaultEditBookModule
import com.viel.aplayer.application.library.edit.EditBookCommands
import com.viel.aplayer.application.library.edit.EditBookReadModel
import com.viel.aplayer.application.library.home.DefaultHomeLibraryReadModel
import com.viel.aplayer.application.library.home.DefaultHomeLibraryUseCases
import com.viel.aplayer.application.library.home.HomeLibraryReadModel
import com.viel.aplayer.application.library.home.HomeLibraryUseCases
import com.viel.aplayer.application.library.player.DefaultPlayerLibraryModule
import com.viel.aplayer.application.library.player.PlayerBookmarkCommands
import com.viel.aplayer.application.library.player.PlayerLibraryReadModel
import com.viel.aplayer.application.library.recovery.DefaultDeletedBookRecoveryModule
import com.viel.aplayer.application.library.recovery.DeletedBookRecoveryCommands
import com.viel.aplayer.application.library.recovery.DeletedBookRecoveryReadModel
import com.viel.aplayer.application.library.search.DefaultSearchLibraryModule
import com.viel.aplayer.application.library.search.SearchLibraryCommands
import com.viel.aplayer.application.library.search.SearchLibraryReadModel
import com.viel.aplayer.application.library.search.SearchQueryPlanner
import com.viel.aplayer.data.availability.BookAvailabilityGateway
import com.viel.aplayer.data.book.BookCatalogGateway
import com.viel.aplayer.data.book.BookMetadataGateway
import com.viel.aplayer.data.book.BookmarkGateway
import com.viel.aplayer.data.book.ChapterGateway
import com.viel.aplayer.data.cover.CoverAssetGateway
import com.viel.aplayer.data.db.AppDatabase
import com.viel.aplayer.data.metadata.MetadataRefreshGateway
import com.viel.aplayer.data.progress.ProgressGateway
import com.viel.aplayer.data.root.LibraryRootGateway
import com.viel.aplayer.data.scan.ScanScheduler
import com.viel.aplayer.data.search.SearchHistoryGateway
import com.viel.aplayer.data.subtitle.SubtitleGateway
import com.viel.aplayer.library.availability.AvailabilityChecker
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * Scene-level read models and commands for home, detail, player, edit, search, and recovery.
 * Replaces the scene-module section of LibraryGraph.
 */
@UnstableApi
internal object LibrarySceneModule {

    val module: Module = module {
        single<HomeLibraryReadModel> {
            DefaultHomeLibraryReadModel(
                bookCatalogGateway = get<BookCatalogGateway>(),
                libraryRootGateway = get<LibraryRootGateway>()
            )
        }

        single<HomeLibraryUseCases> {
            DefaultHomeLibraryUseCases(
                bookMetadataGateway = get<BookMetadataGateway>(),
                scanScheduler = get<ScanScheduler>(),
                libraryRootGateway = get<LibraryRootGateway>(),
                metadataRefreshGateway = get<MetadataRefreshGateway>(),
                searchHistoryGateway = get<SearchHistoryGateway>(),
                coverRecoveryGateway = get()
            )
        }

        single<SearchLibraryReadModel> {
            DefaultSearchLibraryModule(
                searchHistoryGateway = get<SearchHistoryGateway>(),
                queryPlanner = SearchQueryPlanner.from(get<BookCatalogGateway>())
            )
        }

        single<DefaultSearchLibraryModule> { get<SearchLibraryReadModel>() as DefaultSearchLibraryModule }

        single<SearchLibraryCommands> { get<DefaultSearchLibraryModule>() }

        single<DetailBookReadModel> {
            DefaultDetailBookModule(
                bookCatalogGateway = get<BookCatalogGateway>(),
                bookAvailabilityGateway = get<BookAvailabilityGateway>(),
                libraryRootGateway = get<LibraryRootGateway>()
            )
        }

        single<DefaultDetailBookModule> { get<DetailBookReadModel>() as DefaultDetailBookModule }

        single<DetailBookCommands> { get<DefaultDetailBookModule>() }

        single<PlayerLibraryReadModel> {
            DefaultPlayerLibraryModule(
                bookCatalogGateway = get<BookCatalogGateway>(),
                chapterGateway = get<ChapterGateway>(),
                bookmarkGateway = get<BookmarkGateway>(),
                bookAvailabilityGateway = get<BookAvailabilityGateway>(),
                progressGateway = get<ProgressGateway>(),
                subtitleGateway = get<SubtitleGateway>()
            )
        }

        single<DefaultPlayerLibraryModule> { get<PlayerLibraryReadModel>() as DefaultPlayerLibraryModule }

        single<PlayerBookmarkCommands> { get<DefaultPlayerLibraryModule>() }

        single<EditBookReadModel> {
            DefaultEditBookModule(
                bookCatalogGateway = get<BookCatalogGateway>(),
                bookMetadataGateway = get<BookMetadataGateway>(),
                coverAssetGateway = get<CoverAssetGateway>()
            )
        }

        single<DefaultEditBookModule> { get<EditBookReadModel>() as DefaultEditBookModule }

        single<EditBookCommands> { get<DefaultEditBookModule>() }

        single<DeletedBookRecoveryReadModel> {
            DefaultDeletedBookRecoveryModule(
                bookDao = get<AppDatabase>().bookDao(),
                libraryRootDao = get<AppDatabase>().libraryRootDao(),
                absItemMirrorDao = get<AppDatabase>().absItemMirrorDao(),
                availabilityChecker = get<AvailabilityChecker>()
            )
        }

        single<DefaultDeletedBookRecoveryModule> { get<DeletedBookRecoveryReadModel>() as DefaultDeletedBookRecoveryModule }

        single<DeletedBookRecoveryCommands> { get<DefaultDeletedBookRecoveryModule>() }
    }
}
