package com.viel.oto.application.download

import com.viel.oto.data.dao.DownloadMetadataDao
import com.viel.oto.logger.DownloadSyncLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

interface DownloadBookReconciler {
    /**
     * Project current file-level download state into durable book metadata.
     * Startup recovery depends only on this command and does not need the concrete sync service implementation.
     */
    suspend fun reconcileBook(bookId: String)
}

class DownloadSyncService(
    private val downloadableBookFileSelector: DownloadableBookFileSelector,
    private val downloadMetadataDao: DownloadMetadataDao,
    private val downloadIndexSnapshotReader: DownloadIndexSnapshotReader,
    private val manualDownloadNotificationGateway: ManualDownloadNotificationGateway = ManualDownloadNotificationGateway.NoOp,
    private val nowProvider: () -> Long = { System.currentTimeMillis() }
) : DownloadBookReconciler {
    override suspend fun reconcileBook(bookId: String) = withContext(Dispatchers.IO) {
        val mark = DownloadSyncLogger.mark()
        runCatching {
            val files = downloadableBookFileSelector.remoteAudioFilesForBook(bookId)
            val existing = downloadMetadataDao.getMetadata(bookId)
            if (existing == null) {
                manualDownloadNotificationGateway.cancel(bookId)
                return@withContext
            }
            val snapshots = files.mapNotNull { file ->
                downloadIndexSnapshotReader.getSnapshot(file.id)
            }
            val aggregate = BookDownloadAggregator.aggregate(
                bookId = bookId,
                files = files,
                snapshots = snapshots,
                existing = existing,
                nowMillis = nowProvider()
            )
            if (aggregate == null) {
                downloadMetadataDao.deleteByBookId(bookId)
                manualDownloadNotificationGateway.cancel(bookId)
            } else {
                downloadMetadataDao.insertOrReplace(aggregate)
                manualDownloadNotificationGateway.publish(aggregate)
                DownloadSyncLogger.logBookReconciled(
                    bookId = bookId,
                    status = aggregate.status.name,
                    completedFiles = aggregate.completedFiles,
                    totalFiles = aggregate.totalFiles,
                    costMs = DownloadSyncLogger.elapsedMs(mark)
                )
            }
        }.onFailure { error ->
            DownloadSyncLogger.logBookReconcileFailure(
                bookId = bookId,
                errorClass = error::class.java.simpleName,
                message = error.message
            )
        }.getOrThrow()
    }
}
