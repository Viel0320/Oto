package com.viel.aplayer.data.service

import com.viel.aplayer.data.dao.BookDao
import com.viel.aplayer.data.entity.BookProgressEntity
import com.viel.aplayer.data.gateway.ProgressGateway
import com.viel.aplayer.logger.PlaybackWorkflowLogger
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Playback Progress Tracking Application Service (Implements ProgressGateway)
 * 
 * Core Design Goals:
 * 1. Eradicate God-Class Repositories: Connects BookDao while staying fully decoupled from the legacy PlaybackHistoryRepository.
 * 2. Bounded Concurrency Progress Synchronization: Manages a private coroutine scope to process high-frequency position writes.
 */
class ProgressService(
    private val bookDao: BookDao
) : ProgressGateway, java.io.Closeable {

    // Private Coroutine Exception Handler (Asynchronous tracking fault barrier)
    // Captures failures in asynchronous tracking threads to prevent uncaught runtime loops.
    private val exceptionHandler = CoroutineExceptionHandler { _, exception ->
        PlaybackWorkflowLogger.error("progressService coroutine failure", exception)
    }

    // Private Progress Disk IO Coroutine Scope (Non-blocking worker pool)
    // Dispatches persistent tasks onto the IO thread pool to isolate write latency from the Main UI thread.
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob() + exceptionHandler)

    override fun updateProgress(bookId: String, position: Long) {
        // Asynchronous Non-Blocking Position Persistence (Thread-safety guard)
        // Offloads position calculations and Room operations to background tasks, avoiding main loop stutters.
        scope.launch {
            // Transactional Atomic Progress Updates (Race condition avoidance)
            // Invokes updateProgressWithReadStatus to atomize progress lookup, offset computations, and reading state updates.
            // Prevents dirty writes or progress rewinds caused by interleaved updates from concurrent threads.
            bookDao.updateProgressWithReadStatus(bookId, position, System.currentTimeMillis())
        }
    }

    override suspend fun saveProgress(progress: BookProgressEntity) = withContext(Dispatchers.IO) {
        // Synchronous Progress Force-Flush (Transactional consistency enforcement)
        // Executes identical transactional operations to write progress synchronously, avoiding data tearing and updating read states.
        bookDao.updateProgressWithReadStatus(progress.bookId, progress.globalPositionMs, progress.lastPlayedAt)
    }

    override suspend fun getLastPlayedProgressSync(): BookProgressEntity? = withContext(Dispatchers.IO) {
        // Fetch Recent Playback State (Cold-start resumption)
        // Queries the last played progress record synchronously to support visual state restore on boot.
        bookDao.getLastPlayedProgressSync()
    }

    override suspend fun getProgressForBookSync(bookId: String): BookProgressEntity? = withContext(Dispatchers.IO) {
        // Targeted Progress Fetch (Single-book conflict resolution lookup)
        // Reads the exact local checkpoint required by ABS progress arbitration without routing through broad catalog flows.
        bookDao.getProgressForBookSync(bookId)
    }

    override fun close() {
        // Explicit Coroutine Scope Cancellation (Memory leak prevention)
        // Cancels the private scope upon service teardown to ensure pending transactions abort and free memory resources.
        scope.cancel()
    }
}
