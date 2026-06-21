package com.viel.aplayer.di.graph

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadService
import androidx.media3.exoplayer.scheduler.Requirements
import com.viel.aplayer.application.download.CacheMaintenanceCommands
import com.viel.aplayer.application.download.CacheStatisticsProvider
import com.viel.aplayer.application.download.DefaultCacheMaintenanceCommands
import com.viel.aplayer.application.download.DefaultDownloadCacheAccess
import com.viel.aplayer.application.download.DefaultDownloadController
import com.viel.aplayer.application.download.DefaultDownloadRuntimeGateway
import com.viel.aplayer.application.download.DownloadCacheAccess
import com.viel.aplayer.application.download.DownloadController
import com.viel.aplayer.application.download.DownloadManagementReadModel
import com.viel.aplayer.application.download.DownloadProgressPoller
import com.viel.aplayer.application.download.DownloadRecoveryService
import com.viel.aplayer.application.download.DownloadRequestRepairer
import com.viel.aplayer.application.download.DownloadRuntimeGateway
import com.viel.aplayer.application.download.DownloadStatusReadModel
import com.viel.aplayer.application.download.DownloadSyncListener
import com.viel.aplayer.application.download.DownloadSyncService
import com.viel.aplayer.application.download.DownloadableBookFileSelector
import com.viel.aplayer.application.download.ManualDownloadCleanupGateway
import com.viel.aplayer.application.download.ManualDownloadNotificationGateway
import com.viel.aplayer.application.download.ManualDownloadOrphanCleaner
import com.viel.aplayer.application.download.Media3DownloadIndexSnapshotReader
import com.viel.aplayer.application.download.RoomDownloadBookFileReader
import com.viel.aplayer.application.download.RoomDownloadBookIdResolver
import com.viel.aplayer.application.download.RoomDownloadManagementReadModel
import com.viel.aplayer.application.download.RoomDownloadStatusReadModel
import com.viel.aplayer.media.VfsPlaybackDataSource
import com.viel.aplayer.media.service.APlayerDownloadService
import com.viel.aplayer.media.service.AndroidManualDownloadNotificationGateway
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.io.Closeable
import java.util.concurrent.Executors

@OptIn(UnstableApi::class)
internal class DownloadGraph(
    context: Context,
    private val data: DataGraph,
    private val media: MediaGraph
) : Closeable {
    private val appContext = context.applicationContext
    private val downloadScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val databaseProvider by lazy {
        StandaloneDatabaseProvider(appContext)
    }
    private val manualCacheLazy = lazy { createManualDownloadCache() }
    private val downloadManagerLazy = lazy { createDownloadManager(manualCacheLazy.value) }
    private val downloadIndexSnapshotReaderLazy = lazy {
        Media3DownloadIndexSnapshotReader(
            downloadIndexProvider = { downloadManagerLazy.value.downloadIndex },
            activeDownloadsProvider = {
                if (downloadManagerLazy.isInitialized()) {
                    downloadManagerLazy.value.currentDownloads
                } else {
                    emptyList()
                }
            }
        )
    }
    private val downloadSyncServiceLazy = lazy {
        DownloadSyncService(
            downloadableBookFileSelector = downloadableBookFileSelectorLazy.value,
            downloadMetadataDao = data.database.downloadMetadataDao(),
            downloadIndexSnapshotReader = downloadIndexSnapshotReaderLazy.value,
            manualDownloadNotificationGateway = manualDownloadNotificationGatewayLazy.value
        )
    }
    private val downloadProgressPollerLazy = lazy {
        DownloadProgressPoller(
            downloadMetadataDao = data.database.downloadMetadataDao(),
            downloadBookReconcilerProvider = { downloadSyncServiceLazy.value },
            scope = downloadScope
        )
    }
    private val downloadableBookFileSelectorLazy = lazy {
        DownloadableBookFileSelector(
            downloadBookFileReader = RoomDownloadBookFileReader(data.database.bookDao()),
            playbackRootLookup = media.playbackRootLookup
        )
    }
    private val manualDownloadNotificationGatewayLazy = lazy<ManualDownloadNotificationGateway> {
        AndroidManualDownloadNotificationGateway(
            context = appContext,
            bookDao = data.database.bookDao()
        )
    }

    val downloadCacheAccess: DownloadCacheAccess by lazy {
        DefaultDownloadCacheAccess(
            manualCacheProvider = { manualCacheLazy.value }
        )
    }

    val downloadRuntimeGateway: DownloadRuntimeGateway by lazy {
        DefaultDownloadRuntimeGateway(
            addDownloadCommand = { request ->
                DownloadService.sendAddDownload(appContext, APlayerDownloadService::class.java, request, true)
            },
            removeDownloadCommand = { fileId ->
                DownloadService.sendRemoveDownload(appContext, APlayerDownloadService::class.java, fileId, false)
            },
            pauseDownloadsCommand = {
                DownloadService.sendPauseDownloads(appContext, APlayerDownloadService::class.java, false)
            },
            resumeDownloadsCommand = {
                DownloadService.sendResumeDownloads(appContext, APlayerDownloadService::class.java, true)
            },
            setStopReasonCommand = { fileId, reason ->
                DownloadService.sendSetStopReason(appContext, APlayerDownloadService::class.java, fileId, reason, false)
            },
            updateRequirementsCommand = { requirements ->
                downloadManagerLazy.value.requirements = requirements
            }
        )
    }

    val downloadRecoveryService: DownloadRecoveryService by lazy {
        DownloadRecoveryService(
            downloadMetadataDao = data.database.downloadMetadataDao(),
            downloadBookReconcilerProvider = { downloadSyncServiceLazy.value },
            progressPollerStarter = { downloadProgressPollerLazy.value.start() }
        )
    }

    val downloadRequestRepairer: DownloadRequestRepairer by lazy {
        DownloadRequestRepairer(downloadIndexSnapshotReaderLazy.value)
    }

    val cacheStatisticsProvider: CacheStatisticsProvider by lazy {
        CacheStatisticsProvider(
            downloadCacheAccess = downloadCacheAccess,
            downloadMetadataDao = data.database.downloadMetadataDao()
        )
    }

    val cacheMaintenanceCommands: CacheMaintenanceCommands by lazy {
        DefaultCacheMaintenanceCommands(
            downloadMetadataDao = data.database.downloadMetadataDao(),
            downloadController = downloadController
        )
    }

    val downloadStatusReadModel: DownloadStatusReadModel by lazy {
        RoomDownloadStatusReadModel(
            downloadMetadataDao = data.database.downloadMetadataDao(),
            bookDao = data.database.bookDao()
        )
    }

    val downloadManagementReadModel: DownloadManagementReadModel by lazy {
        RoomDownloadManagementReadModel(
            downloadMetadataDao = data.database.downloadMetadataDao(),
            bookDao = data.database.bookDao()
        )
    }

    val manualDownloadOrphanCleaner: ManualDownloadOrphanCleaner by lazy {
        ManualDownloadOrphanCleaner(
            downloadCacheAccess = downloadCacheAccess,
            downloadMetadataDao = data.database.downloadMetadataDao(),
            downloadableBookFileSelector = downloadableBookFileSelectorLazy.value
        )
    }

    val downloadController: DownloadController by lazy {
        DefaultDownloadController(
            downloadableBookFileSelector = downloadableBookFileSelectorLazy.value,
            downloadMetadataDao = data.database.downloadMetadataDao(),
            downloadRuntimeGateway = downloadRuntimeGateway,
            downloadRequestRepairer = downloadRequestRepairer,
            manualDownloadNotificationGateway = manualDownloadNotificationGatewayLazy.value
        )
    }

    val manualDownloadCleanupGateway: ManualDownloadCleanupGateway
        get() = downloadController as ManualDownloadCleanupGateway

    val media3DownloadManager: DownloadManager
        get() = downloadManagerLazy.value

    val isDownloadRuntimeInitialized: Boolean
        get() = downloadManagerLazy.isInitialized()

    private fun createManualDownloadCache(): Cache =
        SimpleCache(
            appContext.filesDir.resolve(MANUAL_CACHE_DIRECTORY),
            NoOpCacheEvictor(),
            databaseProvider
        )

    private fun createDownloadManager(manualCache: Cache): DownloadManager {
        val downloadDataSourceFactory = VfsPlaybackDataSource.Factory(appContext)
        return DownloadManager(
            appContext,
            databaseProvider,
            manualCache,
            downloadDataSourceFactory,
            Executors.newFixedThreadPool(MAX_PARALLEL_DOWNLOADS)
        ).apply {
            maxParallelDownloads = MAX_PARALLEL_DOWNLOADS
            requirements = requirementsForWifiPolicy(data.settingsRepository.cachedSettings.isDownloadWifiOnly)
            addListener(
                DownloadSyncListener(
                    downloadBookIdResolver = RoomDownloadBookIdResolver(data.database.bookDao()),
                    downloadBookReconcilerProvider = { downloadSyncServiceLazy.value },
                    downloadBookRemovalHandler = { bookId ->
                        data.database.downloadMetadataDao().deleteByBookId(bookId)
                        manualDownloadNotificationGatewayLazy.value.cancel(bookId)
                    },
                    progressPollerStarter = { downloadProgressPollerLazy.value.start() },
                    scope = downloadScope
                )
            )
        }
    }

    override fun close() {
        runCatching {
            if (downloadProgressPollerLazy.isInitialized()) {
                downloadProgressPollerLazy.value.stop()
            }
        }
        runCatching {
            if (downloadManagerLazy.isInitialized()) {
                downloadManagerLazy.value.release()
            }
        }
        runCatching {
            if (manualCacheLazy.isInitialized()) {
                manualCacheLazy.value.release()
            }
        }
        runCatching {
            downloadScope.cancel()
        }
    }

    private companion object {
        private const val MANUAL_CACHE_DIRECTORY = "manual_cache"
        private const val MAX_PARALLEL_DOWNLOADS = 3

        fun requirementsForWifiPolicy(wifiOnly: Boolean): Requirements =
            if (wifiOnly) {
                Requirements(Requirements.NETWORK_UNMETERED)
            } else {
                Requirements(Requirements.NETWORK)
            }
    }
}
