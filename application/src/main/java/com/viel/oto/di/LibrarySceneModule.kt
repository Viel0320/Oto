package com.viel.oto.di

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import com.viel.oto.application.library.detail.DefaultDetailBookModule
import com.viel.oto.application.library.detail.DetailBookCommands
import com.viel.oto.application.library.detail.DetailBookReadModel
import com.viel.oto.application.library.edit.DefaultEditBookModule
import com.viel.oto.application.library.edit.EditBookCommands
import com.viel.oto.application.library.edit.EditBookReadModel
import com.viel.oto.application.library.home.DefaultHomeLibraryReadModel
import com.viel.oto.application.library.home.DefaultHomeLibraryUseCases
import com.viel.oto.application.library.home.HomeLibraryReadModel
import com.viel.oto.application.library.home.HomeLibraryUseCases
import com.viel.oto.application.library.player.DefaultPlayerLibraryModule
import com.viel.oto.application.library.player.PlayerBookmarkCommands
import com.viel.oto.application.library.player.PlayerLibraryReadModel
import com.viel.oto.application.library.recovery.DefaultDeletedBookRecoveryModule
import com.viel.oto.application.library.recovery.DeletedBookRecoveryCommands
import com.viel.oto.application.library.recovery.DeletedBookRecoveryReadModel
import com.viel.oto.application.library.search.DefaultSearchLibraryModule
import com.viel.oto.application.library.search.SearchLibraryCommands
import com.viel.oto.application.library.search.SearchLibraryReadModel
import com.viel.oto.application.library.search.SearchQueryPlanner
import com.viel.oto.data.availability.BookAvailabilityGateway
import com.viel.oto.data.book.BookCatalogGateway
import com.viel.oto.data.book.BookMetadataGateway
import com.viel.oto.data.book.BookmarkGateway
import com.viel.oto.data.book.ChapterGateway
import com.viel.oto.data.cover.CoverAssetGateway
import com.viel.oto.data.db.AppDatabase
import com.viel.oto.data.metadata.MetadataRefreshGateway
import com.viel.oto.data.progress.ProgressGateway
import com.viel.oto.library.root.LibraryRootGateway
import com.viel.oto.data.search.SearchHistoryGateway
import com.viel.oto.library.availability.AvailabilityChecker
import com.viel.oto.library.scan.ScanScheduler
import com.viel.oto.media.subtitle.SubtitleGateway
import org.koin.core.module.Module
import org.koin.dsl.binds
import org.koin.dsl.module

/**
 * Scene-level read models and commands for home, detail, player, edit, search, and recovery.
 * Replaces the scene-module section of LibraryGraph.
 *
 * Scene adapters that implement both read-model and command contracts are registered once and
 * bound to both contracts so scene state stays single-instance across read and command entrypoints.
 */
@OptIn(UnstableApi::class)
object LibrarySceneModule {

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

        single {
            DefaultSearchLibraryModule(
                searchHistoryGateway = get<SearchHistoryGateway>(),
                queryPlanner = SearchQueryPlanner.from(get<BookCatalogGateway>())
            )
        } binds arrayOf(SearchLibraryReadModel::class, SearchLibraryCommands::class)

        single {
            DefaultDetailBookModule(
                bookCatalogGateway = get<BookCatalogGateway>(),
                bookAvailabilityGateway = get<BookAvailabilityGateway>(),
                libraryRootGateway = get<LibraryRootGateway>()
            )
        } binds arrayOf(DetailBookReadModel::class, DetailBookCommands::class)

        single {
            DefaultPlayerLibraryModule(
                bookCatalogGateway = get<BookCatalogGateway>(),
                chapterGateway = get<ChapterGateway>(),
                bookmarkGateway = get<BookmarkGateway>(),
                bookAvailabilityGateway = get<BookAvailabilityGateway>(),
                progressGateway = get<ProgressGateway>(),
                subtitleGateway = get<SubtitleGateway>()
            )
        } binds arrayOf(PlayerLibraryReadModel::class, PlayerBookmarkCommands::class)

        single {
            DefaultEditBookModule(
                bookCatalogGateway = get<BookCatalogGateway>(),
                bookMetadataGateway = get<BookMetadataGateway>(),
                coverAssetGateway = get<CoverAssetGateway>()
            )
        } binds arrayOf(EditBookReadModel::class, EditBookCommands::class)

        single {
            DefaultDeletedBookRecoveryModule(
                bookDao = get<AppDatabase>().bookDao(),
                libraryRootDao = get<AppDatabase>().libraryRootDao(),
                absItemMirrorDao = get<AppDatabase>().absItemMirrorDao(),
                availabilityChecker = get<AvailabilityChecker>()
            )
        } binds arrayOf(DeletedBookRecoveryReadModel::class, DeletedBookRecoveryCommands::class)
    }
}
