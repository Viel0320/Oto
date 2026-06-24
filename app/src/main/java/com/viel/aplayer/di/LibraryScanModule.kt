package com.viel.aplayer.di

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import com.viel.aplayer.abs.auth.AbsCredentialStore
import com.viel.aplayer.data.AppSettingsRepository
import com.viel.aplayer.data.cache.CacheEvictionCoordinator
import com.viel.aplayer.data.cleanup.LibraryResourceCleanupGateway
import com.viel.aplayer.data.cover.CoverRecoveryGateway
import com.viel.aplayer.data.db.AppDatabase
import com.viel.aplayer.data.root.LibraryRootGateway
import com.viel.aplayer.data.root.LibraryRootGatewayImpl
import com.viel.aplayer.data.scan.ScanScheduler
import com.viel.aplayer.data.scan.ScanSchedulerImpl
import com.viel.aplayer.event.AppEventSink
import com.viel.aplayer.library.LibraryRootStore
import com.viel.aplayer.library.availability.AvailabilityChecker
import com.viel.aplayer.library.availability.MissingBookFileRecoveryChecker
import com.viel.aplayer.library.vfs.VfsFileInterface
import com.viel.aplayer.library.vfs.cache.DirectoryListingCache
import com.viel.aplayer.library.vfs.cache.VfsRangeCache
import com.viel.aplayer.library.vfs.sourceProvider.webdav.WebDavCredentialStore
import org.koin.core.module.Module
import org.koin.dsl.bind
import org.koin.dsl.module
import java.io.Closeable

/**
 * Library scan scheduling, cache eviction, root gateway, and cleanup seams.
 * Replaces the scan/root section of LibraryGraph.
 *
 * Cleanup, scheduler, and root gateway contracts are exposed from their owning definitions
 * instead of through secondary Koin providers that only redirect to implementation classes.
 */
@OptIn(UnstableApi::class)
internal object LibraryScanModule {

    val module: Module = module {
        single {
            CacheEvictionCoordinator(
                context = get(),
                bookDao = get<AppDatabase>().bookDao(),
                directoryCacheDao = get<AppDatabase>().directoryCacheDao(),
                directoryChildCacheDao = get<AppDatabase>().directoryChildCacheDao(),
                vfsRangeCache = get<VfsRangeCache>(),
                vfsFileInterface = get<VfsFileInterface>()
            )
        } bind LibraryResourceCleanupGateway::class

        single<ScanScheduler> {
            ScanSchedulerImpl(
                context = get(),
                coverRecoveryGateway = get<CoverRecoveryGateway>(),
                vfsFileInterface = get<VfsFileInterface>(),
                directoryListingCache = get<DirectoryListingCache>(),
                appEventSink = get<AppEventSink>(),
                database = get<AppDatabase>(),
                rootStore = get<LibraryRootStore>(),
                missingRecoveryChecker = get<MissingBookFileRecoveryChecker>()
            ).also { scheduler ->
                GraphClosePolicy.register(
                    stage = GraphClosePolicy.Stage.Library,
                    priority = 0,
                    closeable = Closeable { scheduler.close() }
                )
            }
        }

        single {
            LibraryRootStore(
                context = get(),
                rootDao = get<AppDatabase>().libraryRootDao(),
                availabilityChecker = get<AvailabilityChecker>(),
                webDavCredentialStore = get<WebDavCredentialStore>(),
                absCredentialStore = get<AbsCredentialStore>(),
                appSettingsRepository = get<AppSettingsRepository>()
            )
        }

        single<LibraryRootGateway> {
            LibraryRootGatewayImpl(
                context = get(),
                libraryRootDao = get<AppDatabase>().libraryRootDao(),
                bookDao = get<AppDatabase>().bookDao(),
                scanScheduler = get<ScanScheduler>(),
                cacheEvictionCoordinator = get<CacheEvictionCoordinator>(),
                rootStore = get<LibraryRootStore>(),
                webDavCredentialStore = get<WebDavCredentialStore>(),
                absCredentialStore = get<AbsCredentialStore>(),
                database = get<AppDatabase>()
            ).also { gateway ->
                GraphClosePolicy.register(
                    stage = GraphClosePolicy.Stage.Library,
                    priority = 10,
                    closeable = Closeable { gateway.close() }
                )
            }
        }
    }
}
