package com.viel.aplayer.application.download

import com.viel.aplayer.data.dao.DownloadMetadataDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

interface CacheMaintenanceCommands {
    /**
     * Clear every user-requested L1 offline cache task.
     * The command delegates each book to DownloadController so Media3 records, cached bytes, and Room metadata stay in one cleanup path.
     */
    suspend fun deleteAllManualDownloads()

}

class DefaultCacheMaintenanceCommands(
    private val downloadMetadataDao: DownloadMetadataDao,
    private val downloadController: DownloadController
) : CacheMaintenanceCommands {
    override suspend fun deleteAllManualDownloads() = withContext(Dispatchers.IO) {
        downloadMetadataDao.getAllMetadata()
            .map { metadata -> metadata.bookId }
            .distinct()
            .forEach { bookId -> downloadController.deleteDownload(bookId) }
    }
}
