package com.viel.aplayer.application.di.graph

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
        // Media3 Download Database Provider (Backs cache metadata and DownloadIndex persistence)
        // DownloadGraph owns this provider so media and library graphs do not learn Media3's download schema details.
        StandaloneDatabaseProvider(appContext)
    }
    private val manualCacheLazy = lazy { createManualDownloadCache() }
    private val downloadManagerLazy = lazy { createDownloadManager(manualCacheLazy.value) }
    private val downloadIndexSnapshotReaderLazy = lazy {
        // Download Index Reader (Adapt Media3 file-level state behind a testable snapshot boundary)
        // Reading the index resolves DownloadManager only when recovery or sync has already proven there is download work.
        Media3DownloadIndexSnapshotReader(
            downloadIndexProvider = { downloadManagerLazy.value.downloadIndex },
            activeDownloadsProvider = {
                // Live Download Progress Source (Expose in-memory Media3 progress only after the manager exists)
                // This keeps metadata repair from constructing DownloadManager just to check active downloads, while progress polling can still see byte changes.
                if (downloadManagerLazy.isInitialized()) {
                    downloadManagerLazy.value.currentDownloads
                } else {
                    emptyList()
                }
            }
        )
    }
    private val downloadSyncServiceLazy = lazy {
        // Download Sync Service (Reconcile Media3 DownloadIndex into durable book aggregates)
        // This service stays inside DownloadGraph so application code receives state through narrow download modules.
        DownloadSyncService(
            downloadableBookFileSelector = downloadableBookFileSelectorLazy.value,
            downloadMetadataDao = data.database.downloadMetadataDao(),
            downloadIndexSnapshotReader = downloadIndexSnapshotReaderLazy.value,
            manualDownloadNotificationGateway = manualDownloadNotificationGatewayLazy.value
        )
    }
    private val downloadProgressPollerLazy = lazy {
        // Download Progress Poller (Periodically project in-flight byte progress into Room)
        // Media3 state callbacks are sparse, so active manual downloads need a small runtime sampler between start and terminal events.
        DownloadProgressPoller(
            downloadMetadataDao = data.database.downloadMetadataDao(),
            downloadBookReconcilerProvider = { downloadSyncServiceLazy.value },
            scope = downloadScope
        )
    }
    private val downloadableBookFileSelectorLazy = lazy {
        // Downloadable File Selector (Share remote-audio eligibility across commands and maintenance)
        // Centralizing SAF and manifest exclusion prevents controller submissions and orphan cleanup from drifting.
        DownloadableBookFileSelector(
            downloadBookFileReader = RoomDownloadBookFileReader(data.database.bookDao()),
            playbackRootLookup = media.playbackRootLookup
        )
    }
    private val manualDownloadNotificationGatewayLazy = lazy<ManualDownloadNotificationGateway> {
        // Manual Download Notification Gateway (Publish one progress notification per book)
        // Keeping Android notification rendering behind this gateway lets sync and controller code stay on book aggregates.
        AndroidManualDownloadNotificationGateway(
            context = appContext,
            bookDao = data.database.bookDao()
        )
    }

    val downloadCacheAccess: DownloadCacheAccess by lazy {
        // Download Cache Access Boundary (Expose only the user-owned L1 manual cache without resolving DownloadManager)
        // Playback reads completed manual downloads through this handle, while uncached remote playback falls back to VFS memory buffering.
        DefaultDownloadCacheAccess(
            manualCacheProvider = { manualCacheLazy.value }
        )
    }

    val downloadRuntimeGateway: DownloadRuntimeGateway by lazy {
        // Download Runtime Gateway Boundary (Expose queue operations without leaking Media3 DownloadManager)
        // The provider keeps DownloadManager construction behind explicit download work or recovery decisions.
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
                // Per-File Stop Reason Command (Pause or resume one book without affecting other queued books)
                // The controller resolves a book into file IDs before reaching this Android service boundary.
                DownloadService.sendSetStopReason(appContext, APlayerDownloadService::class.java, fileId, reason, false)
            },
            updateRequirementsCommand = { requirements ->
                // Runtime Requirement Update (Apply settings only to an already materialized manager)
                // Settings code owns the lazy gate, so this path avoids starting a foreground service for preference-only writes.
                downloadManagerLazy.value.requirements = requirements
            }
        )
    }

    val downloadRecoveryService: DownloadRecoveryService by lazy {
        // Download Recovery Service (Startup gate before resolving DownloadManager-backed sync)
        // The provider defers DownloadSyncService construction until Room reports recoverable manual download work.
        DownloadRecoveryService(
            downloadMetadataDao = data.database.downloadMetadataDao(),
            downloadBookReconcilerProvider = { downloadSyncServiceLazy.value },
            progressPollerStarter = { downloadProgressPollerLazy.value.start() }
        )
    }

    val downloadRequestRepairer: DownloadRequestRepairer by lazy {
        // Missing Request Repair Helper (Finds DownloadIndex gaps without duplicating known requests)
        // DownloadController uses this during idempotent retries after recovery or partial submission failures.
        DownloadRequestRepairer(downloadIndexSnapshotReaderLazy.value)
    }

    val cacheStatisticsProvider: CacheStatisticsProvider by lazy {
        // Cache Statistics Provider (Summarize L1 manual storage through a narrow application surface)
        // Playback buffering is memory-only now, so settings screens should not receive or imply a playback disk-cache total.
        CacheStatisticsProvider(
            downloadCacheAccess = downloadCacheAccess,
            downloadMetadataDao = data.database.downloadMetadataDao()
        )
    }

    val cacheMaintenanceCommands: CacheMaintenanceCommands by lazy {
        // Cache Maintenance Commands (Own user-requested cache clearing operations)
        // Settings pages receive this small command surface instead of raw Cache handles or DownloadMetadataDao access.
        DefaultCacheMaintenanceCommands(
            downloadMetadataDao = data.database.downloadMetadataDao(),
            downloadController = downloadController
        )
    }

    val downloadStatusReadModel: DownloadStatusReadModel by lazy {
        // Download Status Read Model (Expose Room-derived cache status to presentation)
        // Detail and management UI observe this projection instead of touching the download metadata DAO directly.
        RoomDownloadStatusReadModel(data.database.downloadMetadataDao())
    }

    val downloadManagementReadModel: DownloadManagementReadModel by lazy {
        // Download Management Read Model (Join manual cache aggregates with book display data)
        // Settings-hosted download screens receive display-ready task rows without accessing BookDao or DownloadMetadataDao.
        RoomDownloadManagementReadModel(
            downloadMetadataDao = data.database.downloadMetadataDao(),
            bookDao = data.database.bookDao()
        )
    }

    val manualDownloadOrphanCleaner: ManualDownloadOrphanCleaner by lazy {
        // Manual Download Orphan Cleaner (Background maintenance for stale L1 cache keys)
        // WorkManager can invoke this without touching DownloadManager because cleanup only needs cache access and Room metadata.
        ManualDownloadOrphanCleaner(
            downloadCacheAccess = downloadCacheAccess,
            downloadMetadataDao = data.database.downloadMetadataDao(),
            downloadableBookFileSelector = downloadableBookFileSelectorLazy.value
        )
    }

    val downloadController: DownloadController by lazy {
        // Manual Download Controller (Application command surface for book-level offline cache)
        // The controller composes file inventory, root filtering, idempotent repair, and runtime queue submission without exposing Media3 to UI code.
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

    val manualDownloadCache: Cache
        get() = manualCacheLazy.value

    // Download Service Runtime Owner (Expose the raw Media3 manager only to process-internal service wiring)
    // APlayerDownloadService needs this object for Media3's DownloadService contract, while UI and use cases remain on DownloadRuntimeGateway.
    val media3DownloadManager: DownloadManager
        get() = downloadManagerLazy.value

    // Download Runtime Initialization Flag (Expose lazy-state observation without resolving DownloadManager)
    // Settings commands use this to update Media3 requirements only after real download work has created the runtime.
    val isDownloadRuntimeInitialized: Boolean
        get() = downloadManagerLazy.isInitialized()

    internal fun isDownloadRuntimeInitializedForTests(): Boolean = isDownloadRuntimeInitialized

    private fun createManualDownloadCache(): Cache =
        SimpleCache(
            appContext.filesDir.resolve(MANUAL_CACHE_DIRECTORY),
            NoOpCacheEvictor(),
            databaseProvider
        )

    private fun createDownloadManager(manualCache: Cache): DownloadManager {
        // Download Manager Upstream Factory (Let DownloadManager own the manual-cache write layer)
        // Media3's DownloadManager already receives manualCache below, so the data source factory must be raw VFS upstream instead of another CacheDataSource over the same cache.
        val downloadDataSourceFactory = VfsPlaybackDataSource.Factory(appContext)
        return DownloadManager(
            appContext,
            databaseProvider,
            manualCache,
            downloadDataSourceFactory,
            Executors.newFixedThreadPool(MAX_PARALLEL_DOWNLOADS)
        ).apply {
            // Global Parallelism Limit (Allow three file downloads across all books)
            // Queue ordering remains deterministic by submission order while Media3 can interleave multiple books up to this process-wide cap.
            maxParallelDownloads = MAX_PARALLEL_DOWNLOADS
            // Initial Network Requirements (Apply persisted WiFi policy during the first runtime construction)
            // Later settings writes update this value through DownloadAwareAppSettingsCommands without rebuilding the manager.
            requirements = requirementsForWifiPolicy(data.settingsRepository.cachedSettings.isDownloadWifiOnly)
            // Realtime Download Sync Listener (Attach file-level Media3 callbacks to book-level Room reconciliation)
            // The listener is registered only after DownloadManager exists, preserving the startup lazy boundary.
            addListener(
                DownloadSyncListener(
                    downloadBookIdResolver = RoomDownloadBookIdResolver(data.database.bookDao()),
                    downloadBookReconcilerProvider = { downloadSyncServiceLazy.value },
                    downloadBookRemovalHandler = { bookId ->
                        // Removed Download Projection (Clear the durable task row when Media3 confirms file removal)
                        // Without this terminal path, removed DownloadIndex rows are indistinguishable from missing queued requests and can reappear as QUEUED.
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
        // Download Runtime Teardown (Release Media3 resources only after callers have initialized them)
        // Closing is ordered after playback shutdown by the app container so manual-cache readers no longer hold spans.
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

        // Download Network Requirement Mapping (Translate app settings into Media3 scheduler requirements)
        // WiFi-only mode uses unmetered connectivity while standard mode allows any available network.
        fun requirementsForWifiPolicy(wifiOnly: Boolean): Requirements =
            if (wifiOnly) {
                Requirements(Requirements.NETWORK_UNMETERED)
            } else {
                Requirements(Requirements.NETWORK)
            }
    }
}
