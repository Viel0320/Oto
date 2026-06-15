package com.viel.aplayer.application.download

import com.viel.aplayer.data.dao.DownloadMetadataDao
import com.viel.aplayer.logger.DownloadSyncLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class ManualDownloadOrphanCleanupResult(
    val scannedKeys: Int,
    val removedKeys: Int,
    val bytesBefore: Long,
    val bytesAfter: Long
)

/**
 * Manual Download Orphan Cleaner (Remove L1 cache keys no longer referenced by durable book metadata)
 *
 * The manual cache uses BookFileEntity.id as its cache key, so cleanup can compare cache keys against
 * the same remote-audio selector used by DownloadController without opening provider streams.
 */
class ManualDownloadOrphanCleaner(
    private val downloadCacheAccess: DownloadCacheAccess,
    private val downloadMetadataDao: DownloadMetadataDao,
    private val downloadableBookFileSelector: DownloadableBookFileSelector
) {
    suspend fun cleanOrphans(): ManualDownloadOrphanCleanupResult = withContext(Dispatchers.IO) {
        val manualCache = downloadCacheAccess.manualCache
        val bytesBefore = manualCache.cacheSpace
        val activeKeys = downloadMetadataDao.getAllMetadata()
            .flatMap { metadata -> downloadableBookFileSelector.remoteAudioFilesForBook(metadata.bookId) }
            .map { file -> file.id }
            .toSet()
        val cachedKeys = manualCache.keys
        val orphanKeys = cachedKeys.filterNot { key -> key in activeKeys }
        orphanKeys.forEach { key -> manualCache.removeResource(key) }
        val result = ManualDownloadOrphanCleanupResult(
            scannedKeys = cachedKeys.size,
            removedKeys = orphanKeys.size,
            bytesBefore = bytesBefore,
            bytesAfter = manualCache.cacheSpace
        )
        DownloadSyncLogger.logOrphanCleanup(result.scannedKeys, result.removedKeys, result.bytesBefore, result.bytesAfter)
        result
    }
}
