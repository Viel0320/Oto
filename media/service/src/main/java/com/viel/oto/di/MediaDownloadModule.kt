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
import com.viel.oto.application.download.DefaultDownloadRuntimeGateway
import com.viel.oto.application.download.DownloadProgressPoller
import com.viel.oto.application.download.DownloadRuntimeInitializedFlag
import com.viel.oto.application.download.DownloadRuntimeGateway
import com.viel.oto.application.download.DownloadSyncListener
import com.viel.oto.application.download.DownloadSyncService
import com.viel.oto.application.download.ManualDownloadNotificationGateway
import com.viel.oto.application.download.RoomDownloadBookIdResolver
import com.viel.oto.data.AppSettingsRepository
import com.viel.oto.data.db.AppDatabase
import com.viel.oto.library.vfs.VfsPlaybackStreamReader
import com.viel.oto.media.PlaybackFileLookup
import com.viel.oto.media.VfsPlaybackDataSource
import com.viel.oto.media.service.DownloadControllerActionGateway
import com.viel.oto.media.service.ManualDownloadActionGateway
import com.viel.oto.media.service.OtoDownloadService
import com.viel.oto.media.service.ServiceManualDownloadNotificationGateway
import kotlinx.coroutines.CoroutineScope
import org.koin.core.module.Module
import org.koin.dsl.module
import java.io.Closeable
import java.util.concurrent.Executors

/**
 * Media service owned Media3 download runtime and service command bindings.
 *
 * ApplicationDownloadModule owns manual-download orchestration, while this module owns the process
 * DownloadManager, DownloadService command gateway, and service notification-action adapters.
 */
@OptIn(UnstableApi::class)
object MediaDownloadModule {

    private const val MANUAL_CACHE_DIRECTORY = "manual_cache"
    private const val MAX_PARALLEL_DOWNLOADS = 3

    val module: Module = module {
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

        single<ManualDownloadActionGateway> {
            DownloadControllerActionGateway(downloadControllerProvider = { get() })
        }

        single<ManualDownloadNotificationGateway> {
            ServiceManualDownloadNotificationGateway(delegate = get())
        }

        single<DownloadManager> {
            val appContext = get<Context>()
            val data = get<AppDatabase>()
            val settings = get<AppSettingsRepository>().cachedSettings
            val scope = get<CoroutineScope>(DownloadScopeQualifier)
            val downloadDataSourceFactory = VfsPlaybackDataSource.Factory(
                fileLookup = get<PlaybackFileLookup>(),
                fileReader = get<VfsPlaybackStreamReader>()
            )
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
    }

    private fun requirementsForWifiPolicy(wifiOnly: Boolean): Requirements =
        if (wifiOnly) {
            Requirements(Requirements.NETWORK_UNMETERED)
        } else {
            Requirements(Requirements.NETWORK)
        }
}
