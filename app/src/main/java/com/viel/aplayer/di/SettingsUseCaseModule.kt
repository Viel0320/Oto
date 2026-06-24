package com.viel.aplayer.di

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import com.viel.aplayer.abs.auth.AbsCredentialStore
import com.viel.aplayer.abs.net.AbsApiClient
import com.viel.aplayer.abs.playback.AbsProgressConflictCoordinator
import com.viel.aplayer.abs.sync.AbsCatalogSynchronizer
import com.viel.aplayer.abs.sync.AbsConnectionTester
import com.viel.aplayer.abs.sync.AbsSyncTaskCoordinator
import com.viel.aplayer.application.library.settings.DefaultSettingsRootModule
import com.viel.aplayer.application.library.settings.SettingsRootCommands
import com.viel.aplayer.application.library.settings.SettingsRootReadModel
import com.viel.aplayer.application.usecase.AbsSettingsConnectionUseCase
import com.viel.aplayer.application.usecase.ExportUserDataUseCase
import com.viel.aplayer.application.usecase.FormatSettingsRootUseCase
import com.viel.aplayer.application.usecase.ImportUserDataUseCase
import com.viel.aplayer.application.usecase.ResolveProgressConflictUseCase
import com.viel.aplayer.application.usecase.SettingsLibraryMaintenanceUseCase
import com.viel.aplayer.application.usecase.SettingsQueryUseCase
import com.viel.aplayer.application.usecase.TestWebDavConnectionUseCase
import com.viel.aplayer.data.AppSettingsRepository
import com.viel.aplayer.data.cache.CacheEvictionCoordinator
import com.viel.aplayer.data.db.AppDatabase
import com.viel.aplayer.data.root.LibraryRootGateway
import com.viel.aplayer.data.scan.ScanScheduler
import com.viel.aplayer.library.vfs.sourceProvider.webdav.WebDavConnectionTester
import com.viel.aplayer.library.vfs.sourceProvider.webdav.WebDavCredentialStore
import org.koin.core.module.Module
import org.koin.dsl.binds
import org.koin.dsl.module

/**
 * Settings-scoped use cases: query, format, maintenance, ABS connection, WebDAV test, root module,
 * export/import, and progress conflict resolution.
 *
 * Scene read and command contracts are bound from one SettingsRoot module instance so release builds
 * do not create redirect-only Koin providers for the same object.
 */
@OptIn(UnstableApi::class)
internal object SettingsUseCaseModule {

    val module: Module = module {
        single { WebDavCredentialStore(get()) }

        single {
            SettingsQueryUseCase(
                libraryRootGateway = get<LibraryRootGateway>(),
                absSyncStateDao = get<AppDatabase>().absSyncStateDao(),
                bookDao = get<AppDatabase>().bookDao(),
                libraryRootDao = get<AppDatabase>().libraryRootDao(),
                webDavCredentialStore = get(),
                absCredentialStore = get()
            )
        }

        single { FormatSettingsRootUseCase(get()) }

        single {
            SettingsLibraryMaintenanceUseCase(
                libraryRootGateway = get<LibraryRootGateway>(),
                scanScheduler = get<ScanScheduler>(),
                cacheEvictionCoordinator = get<CacheEvictionCoordinator>(),
                missingBookFileRecoveryChecker = get()
            )
        }

        single {
            AbsSettingsConnectionUseCase(
                apiClient = get<AbsApiClient>(),
                connectionTester = get<AbsConnectionTester>(),
                credentialStore = get<AbsCredentialStore>(),
                libraryRootDao = get<AppDatabase>().libraryRootDao(),
                libraryRootGateway = get<LibraryRootGateway>(),
                libraryRootManagementUseCase = get(),
                maintenanceUseCase = get()
            )
        }

        single { WebDavConnectionTester(appSettingsRepository = get<AppSettingsRepository>()) }

        single {
            TestWebDavConnectionUseCase(
                webDavConnectionTester = get(),
                settingsQueryUseCase = get(),
                libraryRootGateway = get<LibraryRootGateway>()
            )
        }

        single {
            DefaultSettingsRootModule(
                observeRootSnapshotsSource = get<SettingsQueryUseCase>()::observeLibraryRootSnapshots,
                libraryRootGateway = get<LibraryRootGateway>(),
                scanScheduler = get<ScanScheduler>(),
                inspectAbsSyncPlan = get<AbsCatalogSynchronizer>()::inspectRootSyncPlan,
                startAbsSyncTask = get<AbsSyncTaskCoordinator>()::start
            )
        } binds arrayOf(SettingsRootReadModel::class, SettingsRootCommands::class)

        single {
            ExportUserDataUseCase(
                context = get(),
                checkpointDatabaseForBackup = {
                    get<AppDatabase>().openHelper.writableDatabase.query("PRAGMA wal_checkpoint(FULL)").use { cursor ->
                        cursor.moveToFirst()
                    }
                }
            )
        }

        single {
            ImportUserDataUseCase(
                context = get(),
                closeDatabaseForRestore = { get<AppDatabase>().close() }
            )
        }

        single { ResolveProgressConflictUseCase(get<AbsProgressConflictCoordinator>()) }
    }
}
