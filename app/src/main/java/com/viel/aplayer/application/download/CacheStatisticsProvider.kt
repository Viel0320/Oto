package com.viel.aplayer.application.download

import com.viel.aplayer.data.dao.DownloadMetadataDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class CacheStatistics(
    val manualCacheBytes: Long,
    val completedManualBooks: Int,
    val manualCacheFileCount: Int = 0
)

class CacheStatisticsProvider(
    private val downloadCacheAccess: DownloadCacheAccess,
    private val downloadMetadataDao: DownloadMetadataDao
) {
    // Cache Statistics Snapshot (Read L1 manual cache size and durable completed task count)
    // Playback buffering is now memory-only, so persistent cache statistics deliberately exclude removed playback disk storage.
    suspend fun snapshot(): CacheStatistics = withContext(Dispatchers.IO) {
        val manualCache = downloadCacheAccess.manualCache
        CacheStatistics(
            manualCacheBytes = manualCache.cacheSpace,
            completedManualBooks = downloadMetadataDao.getCompletedTaskCount(),
            manualCacheFileCount = manualCache.keys.size
        )
    }
}
