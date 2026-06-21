package com.viel.aplayer.application.download

import com.viel.aplayer.data.dao.DownloadMetadataDao
import com.viel.aplayer.data.entity.DownloadStatus
import com.viel.aplayer.logger.DownloadSyncLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

class DownloadProgressPoller(
    private val downloadMetadataDao: DownloadMetadataDao,
    private val downloadBookReconcilerProvider: () -> DownloadBookReconciler,
    private val scope: CoroutineScope,
    private val pollIntervalMs: Long = DEFAULT_POLL_INTERVAL_MS
) {
    private var pollJob: Job? = null

    @Synchronized
    fun start() {
        if (pollJob?.isActive == true) return
        pollJob = scope.launch {
            runPollingLoop()
        }
    }

    @Synchronized
    fun stop() {
        pollJob?.cancel()
        pollJob = null
    }

    private suspend fun runPollingLoop() {
        while (true) {
            val activeBookIds = downloadMetadataDao.getAllMetadata()
                .asSequence()
                .filter { metadata -> metadata.status in ACTIVE_PROGRESS_STATUSES }
                .map { metadata -> metadata.bookId }
                .distinct()
                .toList()
            if (activeBookIds.isEmpty()) return

            val reconciler = downloadBookReconcilerProvider()
            activeBookIds.forEach { bookId ->
                runCatching {
                    reconciler.reconcileBook(bookId)
                }.onFailure { error ->
                    DownloadSyncLogger.logBookReconcileFailure(
                        bookId = bookId,
                        errorClass = error::class.java.simpleName,
                        message = error.message
                    )
                }
            }
            delay(pollIntervalMs.milliseconds)
        }
    }

    private companion object {
        private const val DEFAULT_POLL_INTERVAL_MS = 1_000L
        private val ACTIVE_PROGRESS_STATUSES = setOf(
            DownloadStatus.QUEUED,
            DownloadStatus.DOWNLOADING
        )
    }
}
