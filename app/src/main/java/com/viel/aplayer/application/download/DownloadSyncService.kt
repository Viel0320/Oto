package com.viel.aplayer.application.download

import com.viel.aplayer.data.dao.DownloadMetadataDao
import com.viel.aplayer.logger.DownloadSyncLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

interface DownloadBookReconciler {
    /**
     * Reconcile Book Download (Project current file-level download state into durable book metadata)
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
    // Book Reconciliation (Rebuild one durable book-level download aggregate from Media3 file-level state)
    // DownloadIndex remains authoritative for per-file progress while Room stores the app-facing aggregate used by UI and recovery.
    override suspend fun reconcileBook(bookId: String) = withContext(Dispatchers.IO) {
        val mark = DownloadSyncLogger.mark()
        runCatching {
            // Shared Manual Cache Eligibility (Reuse the same remote-audio selector as download submission)
            // Reconciliation must not count SAF media or manifest rows as missing DownloadIndex entries, or mixed-source books would report stale queued progress.
            val files = downloadableBookFileSelector.remoteAudioFilesForBook(bookId)
            val existing = downloadMetadataDao.getMetadata(bookId)
            if (existing == null) {
                // Deleted Metadata Guard (Do not recreate user-deleted manual download tasks from stale callbacks)
                // Media3 removal events and progress polling can arrive after the command deletes Room metadata, so missing rows are treated as intentional absence.
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
