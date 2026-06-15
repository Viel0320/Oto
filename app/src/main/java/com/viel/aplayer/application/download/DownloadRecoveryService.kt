package com.viel.aplayer.application.download

import com.viel.aplayer.data.dao.DownloadMetadataDao
import com.viel.aplayer.logger.DownloadSyncLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class DownloadRecoveryService(
    private val downloadMetadataDao: DownloadMetadataDao,
    private val downloadBookReconcilerProvider: () -> DownloadBookReconciler,
    private val progressPollerStarter: () -> Unit = {}
) {
    // Smart Recovery Gate (Check durable metadata before constructing DownloadManager-backed sync services)
    // Completed rows stay durable and do not start the download runtime during process startup.
    suspend fun recoverIfNeeded(): Boolean = withContext(Dispatchers.IO) {
        if (!downloadMetadataDao.hasRecoverableTasks()) {
            DownloadSyncLogger.logRecoverySkipped()
            return@withContext false
        }
        val tasks = downloadMetadataDao.getRecoverableTasks()
        DownloadSyncLogger.logRecoveryStarted(tasks.size)
        val syncService = downloadBookReconcilerProvider()
        tasks.forEach { task ->
            syncService.reconcileBook(task.bookId)
        }
        // Recovery Progress Polling (Resume byte-progress sampling after startup reprojects recoverable tasks)
        // Startup reconciliation can recreate active rows without a fresh user command, so recovery explicitly restarts the same polling path used by live events.
        progressPollerStarter()
        true
    }
}
