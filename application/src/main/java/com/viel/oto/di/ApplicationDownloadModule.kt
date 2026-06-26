package com.viel.oto.di

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.cache.Cache
import androidx.media3.exoplayer.offline.DownloadManager
import com.viel.oto.application.download.DefaultDownloadCacheAccess
import com.viel.oto.application.download.DefaultDownloadController
import com.viel.oto.application.download.DownloadCacheAccess
import com.viel.oto.application.download.DownloadController
import com.viel.oto.application.download.DownloadIndexSnapshotReader
import com.viel.oto.application.download.DownloadProgressPoller
import com.viel.oto.application.download.DownloadRecoveryService
import com.viel.oto.application.download.DownloadRequestRepairer
import com.viel.oto.application.download.DownloadRuntimeInitializedFlag
import com.viel.oto.application.download.DownloadRuntimeGateway
import com.viel.oto.application.download.DownloadSyncService
import com.viel.oto.application.download.DownloadableBookFileSelector
import com.viel.oto.application.download.ManualDownloadCleanupGateway
import com.viel.oto.application.download.ManualDownloadNotificationGateway
import com.viel.oto.application.download.ManualDownloadOrphanCleaner
import com.viel.oto.application.download.Media3DownloadIndexSnapshotReader
import com.viel.oto.application.download.RoomDownloadBookFileReader
import com.viel.oto.data.db.AppDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.koin.core.module.Module
import org.koin.core.qualifier.named
import org.koin.dsl.binds
import org.koin.dsl.module
import java.io.Closeable

val DownloadScopeQualifier = named("downloadScope")

/**
 * Application-owned manual download orchestration bindings.
 *
 * The media service module owns Media3 DownloadService commands, the app module owns resource
 * selection, and this module owns selection, sync, reconciliation, polling, cleanup, and controller
 * contracts.
 */
@OptIn(UnstableApi::class)
object ApplicationDownloadModule {

    val module: Module = module {
        single(DownloadScopeQualifier) {
            CoroutineScope(Dispatchers.IO + SupervisorJob()).also { scope ->
                GraphClosePolicy.register(
                    stage = GraphClosePolicy.Stage.Download,
                    priority = 40,
                    closeable = Closeable { scope.cancel() }
                )
            }
        }

        single { DownloadRuntimeInitializedFlag() }

        single<DownloadableBookFileSelector> {
            DownloadableBookFileSelector(
                downloadBookFileReader = RoomDownloadBookFileReader(get<AppDatabase>().bookDao()),
                playbackRootLookup = get()
            )
        }

        single<DownloadIndexSnapshotReader> {
            Media3DownloadIndexSnapshotReader(
                downloadIndexProvider = { get<DownloadManager>().downloadIndex },
                activeDownloadsProvider = {
                    get<DownloadRuntimeInitializedFlag>().let { flag ->
                        if (flag.value) get<DownloadManager>().currentDownloads
                        else emptyList()
                    }
                }
            )
        }

        single<DownloadSyncService> {
            DownloadSyncService(
                downloadableBookFileSelector = get(),
                downloadMetadataDao = get<AppDatabase>().downloadMetadataDao(),
                downloadIndexSnapshotReader = get(),
                manualDownloadNotificationGateway = get()
            )
        }

        single<DownloadProgressPoller> {
            DownloadProgressPoller(
                downloadMetadataDao = get<AppDatabase>().downloadMetadataDao(),
                downloadBookReconcilerProvider = { get<DownloadSyncService>() },
                scope = get(DownloadScopeQualifier)
            ).also { poller ->
                GraphClosePolicy.register(
                    stage = GraphClosePolicy.Stage.Download,
                    priority = 10,
                    closeable = Closeable { poller.stop() }
                )
            }
        }

        single<DownloadCacheAccess> {
            DefaultDownloadCacheAccess(manualCacheProvider = { get<Cache>() })
        }

        single<DownloadRecoveryService> {
            DownloadRecoveryService(
                downloadMetadataDao = get<AppDatabase>().downloadMetadataDao(),
                downloadBookReconcilerProvider = { get<DownloadSyncService>() },
                progressPollerStarter = { get<DownloadProgressPoller>().start() }
            )
        }

        single { DownloadRequestRepairer(get()) }

        single {
            DefaultDownloadController(
                downloadableBookFileSelector = get(),
                downloadMetadataDao = get<AppDatabase>().downloadMetadataDao(),
                downloadRuntimeGateway = get<DownloadRuntimeGateway>(),
                downloadRequestRepairer = get(),
                manualDownloadNotificationGateway = get()
            )
        } binds arrayOf(DownloadController::class, ManualDownloadCleanupGateway::class)

        single {
            ManualDownloadOrphanCleaner(
                downloadCacheAccess = get(),
                downloadMetadataDao = get<AppDatabase>().downloadMetadataDao(),
                downloadableBookFileSelector = get()
            )
        }
    }
}
