package com.viel.aplayer.di.koin

import androidx.media3.common.util.UnstableApi
import com.viel.aplayer.di.dependencies.AbsSyncWorkerDependencies
import com.viel.aplayer.di.dependencies.AppFeedbackDependencies
import com.viel.aplayer.di.dependencies.AppShellDependencies
import com.viel.aplayer.di.dependencies.DetailScreenDependencies
import com.viel.aplayer.di.dependencies.DownloadRuntimeDependencies
import com.viel.aplayer.di.dependencies.EditScreenDependencies
import com.viel.aplayer.di.dependencies.HomeScreenDependencies
import com.viel.aplayer.di.dependencies.LibrarySyncWorkerDependencies
import com.viel.aplayer.di.dependencies.ManualDownloadNotificationActionDependencies
import com.viel.aplayer.di.dependencies.PlaybackRecoveryDependencies
import com.viel.aplayer.di.dependencies.PlaybackRuntimeDependencies
import com.viel.aplayer.di.dependencies.PlayerScreenDependencies
import com.viel.aplayer.di.dependencies.RemoteConnectionDependencies
import com.viel.aplayer.di.dependencies.SearchScreenDependencies
import com.viel.aplayer.di.dependencies.SettingsScreenDependencies
import com.viel.aplayer.di.dependencies.VfsPlaybackDependencies
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * Binds the narrow dependency-view interfaces to Koin-resolved implementations.
 *
 * Each interface delegates its properties to the Koin-managed singletons, preserving the
 * architectural boundary between callers and di-owned implementations.
 */
@UnstableApi
internal object DependencyViewModule {

    val module: Module = module {
        single<AppShellDependencies> {
            object : AppShellDependencies {
                override val settingsReadModel = get<com.viel.aplayer.application.library.settings.AppSettingsReadModel>()
                override val appEventSink = get<com.viel.aplayer.event.AppEventSink>()
            }
        }

        single<AppFeedbackDependencies> {
            object : AppFeedbackDependencies {
                override val appEventSink = get<com.viel.aplayer.event.AppEventSink>()
            }
        }

        single<PlaybackRecoveryDependencies> {
            object : PlaybackRecoveryDependencies {
                override val bookCatalogGateway = get<com.viel.aplayer.data.book.BookCatalogGateway>()
                override val progressGateway = get<com.viel.aplayer.data.progress.ProgressGateway>()
            }
        }

        single<PlaybackRuntimeDependencies> {
            object : PlaybackRuntimeDependencies, PlaybackRecoveryDependencies by get<PlaybackRecoveryDependencies>() {
                override val chapterGateway = get<com.viel.aplayer.data.book.ChapterGateway>()
                override val bookmarkGateway = get<com.viel.aplayer.data.book.BookmarkGateway>()
                override val bookAvailabilityGateway = get<com.viel.aplayer.data.availability.BookAvailabilityGateway>()
                override val playbackPlanGateway = get<com.viel.aplayer.media.PlaybackPlanGateway>()
                override val absPlaybackSessionSyncer = get<com.viel.aplayer.abs.playback.AbsPlaybackSessionSyncer>()
                override val playbackSourcePreflight = get<com.viel.aplayer.media.PlaybackSourcePreflight>()
                override val playbackDomainEventSink = get<com.viel.aplayer.media.PlaybackDomainEventSink>()
            }
        }

        single<VfsPlaybackDependencies> {
            object : VfsPlaybackDependencies {
                override val vfsFileInterface = get<com.viel.aplayer.library.vfs.VfsFileInterface>()
                override val playbackFileLookup = get<com.viel.aplayer.media.PlaybackFileLookup>()
                override val playbackRootLookup = get<com.viel.aplayer.media.PlaybackRootLookup>()
            }
        }

        single<DownloadRuntimeDependencies> {
            object : DownloadRuntimeDependencies {
                override val downloadRuntimeGateway = get<com.viel.aplayer.application.download.DownloadRuntimeGateway>()
                override val downloadCacheAccess = get<com.viel.aplayer.application.download.DownloadCacheAccess>()
            }
        }

        single<ManualDownloadNotificationActionDependencies> {
            object : ManualDownloadNotificationActionDependencies {
                override val downloadController = get<com.viel.aplayer.application.download.DownloadController>()
            }
        }

        single<LibrarySyncWorkerDependencies> {
            object : LibrarySyncWorkerDependencies {
                override val scanScheduler = get<com.viel.aplayer.data.scan.ScanScheduler>()
            }
        }

        single<AbsSyncWorkerDependencies> {
            object : AbsSyncWorkerDependencies {
                override val appEventSink = get<com.viel.aplayer.event.AppEventSink>()
                override val absCatalogSynchronizer = get<com.viel.aplayer.abs.sync.AbsCatalogSynchronizer>()
            }
        }

        single<SearchScreenDependencies> {
            object : SearchScreenDependencies {
                override val searchLibraryReadModel = get<com.viel.aplayer.application.library.search.SearchLibraryReadModel>()
                override val searchLibraryCommands = get<com.viel.aplayer.application.library.search.SearchLibraryCommands>()
            }
        }

        single<DetailScreenDependencies> {
            object : DetailScreenDependencies {
                override val detailBookReadModel = get<com.viel.aplayer.application.library.detail.DetailBookReadModel>()
                override val detailBookCommands = get<com.viel.aplayer.application.library.detail.DetailBookCommands>()
                override val downloadStatusReadModel = get<com.viel.aplayer.application.download.DownloadStatusReadModel>()
                override val downloadController = get<com.viel.aplayer.application.download.DownloadController>()
                override val appEventSink = get<com.viel.aplayer.event.AppEventSink>()
            }
        }

        single<HomeScreenDependencies> {
            object : HomeScreenDependencies {
                override val homeLibraryReadModel = get<com.viel.aplayer.application.library.home.HomeLibraryReadModel>()
                override val homeLibraryUseCases = get<com.viel.aplayer.application.library.home.HomeLibraryUseCases>()
                override val settingsReadModel = get<com.viel.aplayer.application.library.settings.AppSettingsReadModel>()
                override val settingsCommands = get<com.viel.aplayer.application.library.settings.AppSettingsCommands>()
                override val appEventSink = get<com.viel.aplayer.event.AppEventSink>()
                override val bookManagementUseCase = get<com.viel.aplayer.application.usecase.BookManagementUseCase>()
            }
        }

        single<SettingsScreenDependencies> {
            object : SettingsScreenDependencies {
                override val formatSettingsRootUseCase = get<com.viel.aplayer.application.usecase.FormatSettingsRootUseCase>()
                override val settingsRootReadModel = get<com.viel.aplayer.application.library.settings.SettingsRootReadModel>()
                override val settingsRootCommands = get<com.viel.aplayer.application.library.settings.SettingsRootCommands>()
                override val deletedBookRecoveryReadModel = get<com.viel.aplayer.application.library.recovery.DeletedBookRecoveryReadModel>()
                override val deletedBookRecoveryCommands = get<com.viel.aplayer.application.library.recovery.DeletedBookRecoveryCommands>()
                override val settingsReadModel = get<com.viel.aplayer.application.library.settings.AppSettingsReadModel>()
                override val settingsCommands = get<com.viel.aplayer.application.library.settings.AppSettingsCommands>()
                override val downloadManagementReadModel = get<com.viel.aplayer.application.download.DownloadManagementReadModel>()
                override val downloadController = get<com.viel.aplayer.application.download.DownloadController>()
                override val cacheStatisticsProvider = get<com.viel.aplayer.application.download.CacheStatisticsProvider>()
                override val cacheMaintenanceCommands = get<com.viel.aplayer.application.download.CacheMaintenanceCommands>()
                override val settingsQueryUseCase = get<com.viel.aplayer.application.usecase.SettingsQueryUseCase>()
                override val settingsLibraryMaintenanceUseCase = get<com.viel.aplayer.application.usecase.SettingsLibraryMaintenanceUseCase>()
                override val absSettingsConnectionUseCase = get<com.viel.aplayer.application.usecase.AbsSettingsConnectionUseCase>()
                override val testWebDavConnectionUseCase = get<com.viel.aplayer.application.usecase.TestWebDavConnectionUseCase>()
                override val appEventSink = get<com.viel.aplayer.event.AppEventSink>()
                override val libraryRootManagementUseCase = get<com.viel.aplayer.application.usecase.LibraryRootManagementUseCase>()
                override val exportUserDataUseCase = get<com.viel.aplayer.application.usecase.ExportUserDataUseCase>()
                override val importUserDataUseCase = get<com.viel.aplayer.application.usecase.ImportUserDataUseCase>()
            }
        }

        single<PlayerScreenDependencies> {
            object : PlayerScreenDependencies {
                override val playerLibraryReadModel = get<com.viel.aplayer.application.library.player.PlayerLibraryReadModel>()
                override val playerBookmarkCommands = get<com.viel.aplayer.application.library.player.PlayerBookmarkCommands>()
                override val settingsReadModel = get<com.viel.aplayer.application.library.settings.AppSettingsReadModel>()
                override val settingsCommands = get<com.viel.aplayer.application.library.settings.AppSettingsCommands>()
                override val resolveProgressConflictUseCase = get<com.viel.aplayer.application.usecase.ResolveProgressConflictUseCase>()
                override val appEventSink = get<com.viel.aplayer.event.AppEventSink>()
                override val buildPlaybackPlanUseCase = get<com.viel.aplayer.application.usecase.BuildPlaybackPlanUseCase>()
                override val playerPlaybackController = get<com.viel.aplayer.application.playback.PlayerPlaybackController>()
            }
        }

        single<EditScreenDependencies> {
            object : EditScreenDependencies {
                override val editBookReadModel = get<com.viel.aplayer.application.library.edit.EditBookReadModel>()
                override val editBookCommands = get<com.viel.aplayer.application.library.edit.EditBookCommands>()
            }
        }

        single<RemoteConnectionDependencies> {
            object : RemoteConnectionDependencies {
                override val absSettingsConnectionUseCase = get<com.viel.aplayer.application.usecase.AbsSettingsConnectionUseCase>()
                override val testWebDavConnectionUseCase = get<com.viel.aplayer.application.usecase.TestWebDavConnectionUseCase>()
                override val settingsQueryUseCase = get<com.viel.aplayer.application.usecase.SettingsQueryUseCase>()
                override val settingsRootCommands = get<com.viel.aplayer.application.library.settings.SettingsRootCommands>()
                override val formatSettingsRootUseCase = get<com.viel.aplayer.application.usecase.FormatSettingsRootUseCase>()
                override val settingsLibraryMaintenanceUseCase = get<com.viel.aplayer.application.usecase.SettingsLibraryMaintenanceUseCase>()
                override val libraryRootManagementUseCase = get<com.viel.aplayer.application.usecase.LibraryRootManagementUseCase>()
                override val appEventSink = get<com.viel.aplayer.event.AppEventSink>()
            }
        }
    }
}
