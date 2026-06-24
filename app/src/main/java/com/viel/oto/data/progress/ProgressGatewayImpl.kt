package com.viel.oto.data.progress

import com.viel.oto.data.dao.BookDao
import com.viel.oto.data.entity.BookProgressEntity
import com.viel.oto.logger.PlaybackWorkflowLogger
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong

/**
 * Implements ProgressGateway.
 *
 * Core Design Goals:
 * 1. Eradicate God-Class Repositories: Connects BookDao while staying fully decoupled from the legacy PlaybackHistoryRepository.
 * 2. Bounded Concurrency Progress Synchronization: Manages a private coroutine scope to process high-frequency position writes.
 */
class ProgressGatewayImpl(
    private val bookDao: BookDao
) : ProgressGateway, java.io.Closeable {

    private val exceptionHandler = CoroutineExceptionHandler { _, exception ->
        PlaybackWorkflowLogger.error("progressService coroutine failure", exception)
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob() + exceptionHandler)

    private val progressWriteClock = AtomicLong(0L)

    override fun updateProgress(bookId: String, position: Long) {
        val capturedAt = nextProgressWriteTime()
        scope.launch {
            bookDao.updateProgressWithReadStatus(bookId, position, capturedAt)
        }
    }

    override suspend fun saveProgress(progress: BookProgressEntity) {
        saveProgressIfNewer(progress)
    }

    override suspend fun saveProgressIfNewer(progress: BookProgressEntity): Boolean = withContext(Dispatchers.IO) {
        bookDao.updateProgressWithReadStatus(progress.bookId, progress.globalPositionMs, progress.lastPlayedAt)
    }

    override suspend fun getLastPlayedProgressSync(): BookProgressEntity? = withContext(Dispatchers.IO) {
        bookDao.getLastPlayedProgressSync()
    }

    override suspend fun getProgressForBookSync(bookId: String): BookProgressEntity? = withContext(Dispatchers.IO) {
        bookDao.getProgressForBookSync(bookId)
    }

    private fun nextProgressWriteTime(): Long {
        val wallClockTime = System.currentTimeMillis()
        return progressWriteClock.updateAndGet { previousTime ->
            maxOf(wallClockTime, previousTime + 1L)
        }
    }

    override fun close() {
        scope.cancel()
    }
}
