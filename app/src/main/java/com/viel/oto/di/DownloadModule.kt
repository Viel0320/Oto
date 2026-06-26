package com.viel.oto.di

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.DatabaseProvider
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadService
import androidx.media3.exoplayer.scheduler.Requirements
import com.viel.oto.application.download.DefaultDownloadCacheAccess
import com.viel.oto.application.download.DefaultDownloadController
import com.viel.oto.application.download.DefaultDownloadRuntimeGateway
import com.viel.oto.application.download.DownloadCacheAccess
import com.viel.oto.application.download.DownloadController
import com.viel.oto.application.download.DownloadIndexSnapshotReader
import com.viel.oto.app.download.AppDownloadNotificationResources
import com.viel.oto.app.download.AppManualDownloadActionGateway
import com.viel.oto.app.download.AppManualDownloadNotificationGateway
import com.viel.oto.application.download.DownloadProgressPoller
import com.viel.oto.application.download.DownloadRecoveryService
import com.viel.oto.application.download.DownloadRequestRepairer
import com.viel.oto.application.download.DownloadRuntimeInitializedFlag
import com.viel.oto.application.download.DownloadRuntimeGateway
import com.viel.oto.application.download.DownloadSyncListener
import com.viel.oto.application.download.DownloadSyncService
import com.viel.oto.application.download.DownloadableBookFileSelector
import com.viel.oto.application.download.ManualDownloadCleanupGateway
import com.viel.oto.application.download.ManualDownloadNotificationGateway
import com.viel.oto.application.download.ManualDownloadOrphanCleaner
import com.viel.oto.application.download.Media3DownloadIndexSnapshotReader
import com.viel.oto.application.download.RoomDownloadBookFileReader
import com.viel.oto.application.download.RoomDownloadBookIdResolver
import com.viel.oto.data.AppSettingsRepository
import com.viel.oto.data.db.AppDatabase
import com.viel.oto.media.VfsPlaybackDataSource
import com.viel.oto.media.service.AndroidManualDownloadNotificationGateway
import com.viel.oto.media.service.DownloadNotificationResources
import com.viel.oto.media.service.ManualDownloadActionGateway
import com.viel.oto.media.service.MediaServiceLaunchIntentFactory
import com.viel.oto.media.service.OtoDownloadService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.koin.core.module.Module
import org.koin.dsl.binds
import org.koin.dsl.module
import java.io.Closeable
import java.util.concurrent.Executors

/**
 * Manual download cache, Media3 DownloadManager, and download runtime gateways.
 * Replaces DownloadGraph with Koin-managed single definitions and registers cache/manager/scope
 * for ordered shutdown.
 *
 * DownloadController is exposed as ManualDownloadCleanupGateway from the same singleton definition
 * so R8-optimized builds never resolve a redirect-only cleanup provider back into itself.
 */
@OptIn(UnstableApi::class)
internal object DownloadModule {

    private const val MANUAL_CACHE_DIRECTORY = "manual_cache"
    private const val MAX_PARALLEL_DOWNLOADS = 3

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

        single<DatabaseProvider> { StandaloneDatabaseProvider(get()) }

        single<Cache> {
            SimpleCache(
                get<Context>().filesDir.resolve(MANUAL_CACHE_DIRECTORY),
                NoOpCacheEvictor(),
                get()
            ).also { cache ->
                GraphClosePolicy.register(
                    stage = GraphClosePolicy.Stage.Download,
                    priority = 30,
                    closeable = Closeable { cache.release() }
                )
            }
        }

        single { DownloadRuntimeInitializedFlag() }

        single<DownloadNotificationResources> { AppDownloadNotificationResources() }

        single<ManualDownloadActionGateway> {
            AppManualDownloadActionGateway(downloadControllerProvider = { get<DownloadController>() })
        }

        single<DownloadableBookFileSelector> {
            DownloadableBookFileSelector(
                downloadBookFileReader = RoomDownloadBookFileReader(get<AppDatabase>().bookDao()),
                playbackRootLookup = get()
            )
        }

        single<ManualDownloadNotificationGateway> {
            AppManualDownloadNotificationGateway(
                delegate = AndroidManualDownloadNotificationGateway(
                    context = get(),
                    bookDao = get<AppDatabase>().bookDao(),
                    launchIntentFactory = get<MediaServiceLaunchIntentFactory>(),
                    notificationResources = get<DownloadNotificationResources>()
                )
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

        single<DownloadManager> {
            val appContext = get<Context>()
            val data = get<AppDatabase>()
            val settings = get<AppSettingsRepository>().cachedSettings
            val scope = get<CoroutineScope>(DownloadScopeQualifier)
            val downloadDataSourceFactory = VfsPlaybackDataSource.Factory(appContext, get())
            DownloadManager(
                appContext,
                get(),
                get<Cache>(),
                downloadDataSourceFactory,
                Executors.newFixedThreadPool(MAX_PARALLEL_DOWNLOADS)
            ).apply {
                maxParallelDownloads = MAX_PARALLEL_DOWNLOADS
                requirements = requirementsForWifiPolicy(settings.isDownloadWifiOnly)
                addListener(
                    DownloadSyncListener(
                        downloadBookIdResolver = RoomDownloadBookIdResolver(data.bookDao()),
                        downloadBookReconcilerProvider = { get<DownloadSyncService>() },
                        downloadBookRemovalHandler = { bookId ->
                            data.downloadMetadataDao().deleteByBookId(bookId)
                            get<ManualDownloadNotificationGateway>().cancel(bookId)
                        },
                        progressPollerStarter = { get<DownloadProgressPoller>().start() },
                        scope = scope
                    )
                )
            }.also {
                get<DownloadRuntimeInitializedFlag>().value = true
                GraphClosePolicy.register(
                    stage = GraphClosePolicy.Stage.Download,
                    priority = 20,
                    closeable = Closeable {
                        runCatching { it.release() }
                        get<DownloadRuntimeInitializedFlag>().value = false
                    }
                )
            }
        }

        single<DownloadCacheAccess> {
            DefaultDownloadCacheAccess(manualCacheProvider = { get<Cache>() })
        }

        single<DownloadRuntimeGateway> {
            val appContext = get<Context>()
            DefaultDownloadRuntimeGateway(
                addDownloadCommand = { request ->
                    DownloadService.sendAddDownload(appContext, OtoDownloadService::class.java, request, true)
                },
                removeDownloadCommand = { fileId ->
                    DownloadService.sendRemoveDownload(appContext, OtoDownloadService::class.java, fileId, false)
                },
                pauseDownloadsCommand = {
                    DownloadService.sendPauseDownloads(appContext, OtoDownloadService::class.java, false)
                },
                resumeDownloadsCommand = {
                    DownloadService.sendResumeDownloads(appContext, OtoDownloadService::class.java, true)
                },
                setStopReasonCommand = { fileId, reason ->
                    DownloadService.sendSetStopReason(appContext, OtoDownloadService::class.java, fileId, reason, false)
                },
                updateRequirementsCommand = { requirements ->
                    get<DownloadManager>().requirements = requirements
                }
            )
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
                downloadRuntimeGateway = get(),
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

    private fun requirementsForWifiPolicy(wifiOnly: Boolean): Requirements =
        if (wifiOnly) {
            Requirements(Requirements.NETWORK_UNMETERED)
        } else {
            Requirements(Requirements.NETWORK)
        }
}
