package com.viel.oto.application.download

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import com.viel.oto.data.dao.DownloadMetadataDao
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
    @OptIn(UnstableApi::class)
    suspend fun snapshot(): CacheStatistics = withContext(Dispatchers.IO) {
        val manualCache = downloadCacheAccess.manualCache
        CacheStatistics(
            manualCacheBytes = manualCache.cacheSpace,
            completedManualBooks = downloadMetadataDao.getCompletedTaskCount(),
            manualCacheFileCount = manualCache.keys.size
        )
    }
}
