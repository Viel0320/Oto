package com.viel.oto.application.download

import com.viel.oto.data.dao.DownloadMetadataDao
import com.viel.oto.logger.DownloadSyncLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class DownloadRecoveryService(
    private val downloadMetadataDao: DownloadMetadataDao,
    private val downloadBookReconcilerProvider: () -> DownloadBookReconciler,
    private val progressPollerStarter: () -> Unit = {}
) {
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
        progressPollerStarter()
        true
    }
}
