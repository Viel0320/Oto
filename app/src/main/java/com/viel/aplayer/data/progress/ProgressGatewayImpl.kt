package com.viel.aplayer.data.progress

import com.viel.aplayer.data.dao.BookDao
import com.viel.aplayer.data.entity.BookProgressEntity
import com.viel.aplayer.logger.PlaybackWorkflowLogger
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong

/**
 * Playback Progress Tracking Application Service (Implements ProgressGateway)
 * 
 * Core Design Goals:
 * 1. Eradicate God-Class Repositories: Connects BookDao while staying fully decoupled from the legacy PlaybackHistoryRepository.
 * 2. Bounded Concurrency Progress Synchronization: Manages a private coroutine scope to process high-frequency position writes.
 */
class ProgressGatewayImpl(
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

    // Progress Write Clock (Creates strictly increasing timestamps for asynchronous gateway writes)
    // Multiple progress updates can be captured inside the same millisecond, so this clock prevents equal timestamps from reintroducing stale overwrite ambiguity.
    private val progressWriteClock = AtomicLong(0L)

    override fun updateProgress(bookId: String, position: Long) {
        val capturedAt = nextProgressWriteTime()
        // Asynchronous Non-Blocking Position Persistence (Thread-safety guard)
        // Offloads position calculations and Room operations to background tasks, avoiding main loop stutters.
        scope.launch {
            // Transactional Atomic Progress Updates (Race condition avoidance)
            // Invokes updateProgressWithReadStatus to atomize progress lookup, offset computations, and reading state updates.
            // Prevents dirty writes or progress rewinds caused by interleaved updates from concurrent threads.
            bookDao.updateProgressWithReadStatus(bookId, position, capturedAt)
        }
    }

    override suspend fun saveProgress(progress: BookProgressEntity) {
        saveProgressIfNewer(progress)
    }

    override suspend fun saveProgressIfNewer(progress: BookProgressEntity): Boolean = withContext(Dispatchers.IO) {
        // Newer Checkpoint Persistence (Preserves playback ordering across delayed coroutine completions)
        // The DAO returns false for stale lastPlayedAt values, allowing callers to skip follow-up side effects such as ABS progress uploads.
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

    private fun nextProgressWriteTime(): Long {
        // Monotonic Progress Timestamp (Captures ordering before the coroutine is scheduled)
        // Persisted freshness must represent the playback event order, not whichever IO coroutine happens to reach Room first.
        val wallClockTime = System.currentTimeMillis()
        return progressWriteClock.updateAndGet { previousTime ->
            maxOf(wallClockTime, previousTime + 1L)
        }
    }

    override fun close() {
        // Explicit Coroutine Scope Cancellation (Memory leak prevention)
        // Cancels the private scope upon service teardown to ensure pending transactions abort and free memory resources.
        scope.cancel()
    }
}
