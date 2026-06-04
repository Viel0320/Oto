package com.viel.aplayer.data.service

import com.viel.aplayer.data.dao.BookDao
import com.viel.aplayer.data.entity.BookFileEntity
import com.viel.aplayer.data.entity.BookProgressEntity
import com.viel.aplayer.data.gateway.ProgressGateway
import com.viel.aplayer.library.availability.PlaybackReachabilityManager
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
 * 1. Eradicate God-Class Repositories: Connects BookDao and PlaybackReachabilityManager in the M6b phase, fully decoupling from the legacy PlaybackHistoryRepository.
 * 2. Bounded Concurrency Progress Synchronization: Manages a private coroutine scope to process high-frequency status swaps (e.g. finished thresholds, failover checks, ENOENT corrections).
 */
class ProgressService(
    private val bookDao: BookDao,
    private val reachabilityManager: PlaybackReachabilityManager
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

    override suspend fun checkCurrentPlaybackFileAvailability(bookId: String): Boolean = withContext(Dispatchers.IO) {
        // Access-Control Authorization Verification (Physical reachability audit)
        // Delegates to the reachability manager to verify permissions and physical existences before starting playback.
        reachabilityManager.checkCurrentPlaybackFileAvailability(bookId)
    }

    override suspend fun markPlaybackFileUnavailable(bookId: String, queueIndex: Int) = withContext(Dispatchers.IO) {
        // Mark Corrupt Ingested Track (Playback crash resilience)
        // Flags the current track as invalid and timestamps the failover attempt when ExoPlayer throws IO exceptions.
        reachabilityManager.markPlaybackFileUnavailable(bookId, queueIndex)
    }

    override suspend fun findNextAvailablePlaybackFile(
        bookId: String,
        afterQueueIndex: Int
    ): Pair<Int, BookFileEntity>? = withContext(Dispatchers.IO) {
        // Resolve Sibling Failover Track (Disaster recovery scan)
        // Searches the queue sequentially to match the next available track when active files are missing or unreadable.
        reachabilityManager.findNextAvailablePlaybackFile(bookId, afterQueueIndex)
    }

    override fun close() {
        // Explicit Coroutine Scope Cancellation (Memory leak prevention)
        // Cancels the private scope upon service teardown to ensure pending transactions abort and free memory resources.
        scope.cancel()
    }
}
