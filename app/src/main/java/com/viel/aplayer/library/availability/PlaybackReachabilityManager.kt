package com.viel.aplayer.library.availability

import android.content.Context
import com.viel.aplayer.data.dao.BookDao
import com.viel.aplayer.data.dao.LibraryRootDao
import com.viel.aplayer.data.db.AudiobookSchema
import com.viel.aplayer.data.entity.BookFileEntity
import com.viel.aplayer.data.entity.BookProgressEntity
import com.viel.aplayer.media.PositionMapper
import kotlinx.coroutines.delay

/**
 * Playback Reachability and Failover Manager (Core Service)
 *
 * Verifies physical file presence during playback, handles reachability degradation,
 * and performs failover routing to identify next playable track.
 * Decoupled from LibraryRepository to simplify core data access layers.
 */
class PlaybackReachabilityManager(
    context: Context,
    private val bookDao: BookDao,
    private val libraryRootDao: LibraryRootDao
) {
    // Route to AvailabilityChecker (Unified Check Path)
    // Directs playback reachability check to the shared AvailabilityChecker, open for WebDAV extension.
    private val availabilityChecker = AvailabilityChecker(context.applicationContext)

    /**
     * Determine File Reachability (Tiered Strategy Pattern)
     *
     * Local files are checked instantly, while remote files employ a retry strategy with grace windows.
     */
    private suspend fun checkFileAvailable(file: BookFileEntity): Boolean {
        val root = libraryRootDao.getRootById(file.rootId) ?: return false
        val isLocal = root.sourceType == AudiobookSchema.LibrarySourceType.SAF
        return if (isLocal) {
            availabilityChecker.checkBookFile(file).isAvailable
        } else {
            checkRemoteFileAvailableWithGrace(file)
        }
    }

    private suspend fun checkRemoteFileAvailableWithGrace(file: BookFileEntity): Boolean {
        // Retry Remote Availability Checks (Network Resiliency)
        // Accommodates temporary network hiccups; error states apply only after consecutive failures.
        REMOTE_REACHABILITY_RETRY_DELAYS_MS.forEachIndexed { attempt, delayMs ->
            if (availabilityChecker.checkBookFile(file).isAvailable) return true
            if (attempt < REMOTE_REACHABILITY_RETRY_DELAYS_MS.lastIndex) delay(delayMs)
        }
        return false
    }

    /**
     * Verify Active Track Availability (State Synchronization)
     *
     * Inspects the track mapped to the book's current playback progress.
     * Updates states and recalculates the book-wide reachability level (e.g. READY, PARTIAL, UNAVAILABLE).
     */
    suspend fun checkCurrentPlaybackFileAvailability(bookId: String): Boolean {
        val files = bookDao.getFilesForBookList(bookId)
        if (files.isEmpty()) {
            // Mark Book as Unavailable (Empty Track Guard)
            // Books missing any file references cannot be played; updates status to UNAVAILABLE.
            bookDao.updateBookStatus(bookId, AudiobookSchema.BookStatus.UNAVAILABLE)
            return false
        }

        val progress = bookDao.getProgressForBookSync(bookId)
        val targetFile = resolveProgressFile(progress, files) ?: files.first()
        val isReady = checkFileAvailable(targetFile)
        updatePlaybackFileStatus(targetFile, isReady)
        recalculatePlaybackBookStatus(bookId)
        return isReady
    }

    /**
     * Flag Failed Track (Playback Failover Trigger)
     *
     * Triggered by player preparation errors to label a track as MISSING and trigger recalculation.
     */
    suspend fun markPlaybackFileUnavailable(bookId: String, queueIndex: Int) {
        val files = bookDao.getFilesForBookList(bookId)
        files.getOrNull(queueIndex)?.let { failedFile ->
            // Safe Failure Transitions (State Protection)
            // Local files degrade immediately, whereas remote files execute checks with retries to prevent false alarms during dropouts.
            if (checkFileAvailable(failedFile)) {
                bookDao.updateBookFileStatus(failedFile.id, AudiobookSchema.FileStatus.READY)
            } else {
                bookDao.updateBookFileStatus(failedFile.id, AudiobookSchema.FileStatus.MISSING)
            }
            recalculatePlaybackBookStatus(bookId)
        }
    }

    /**
     * Find Next Playable Track (Failover Logic)
     *
     * Traverses the queue to locate the next READY track to avoid crash loops and provide robust automatic skip capabilities.
     */
    suspend fun findNextAvailablePlaybackFile(bookId: String, afterQueueIndex: Int): Pair<Int, BookFileEntity>? {
        val files = bookDao.getFilesForBookList(bookId)
        if (files.isEmpty()) {
            bookDao.updateBookStatus(bookId, AudiobookSchema.BookStatus.UNAVAILABLE)
            return null
        }

        val candidateFiles = files.drop(afterQueueIndex + 1)
        if (!allLocalFiles(candidateFiles)) {
            return findNextAvailableRemoteAware(bookId, afterQueueIndex, files)
        }
        // Batch Check Downstream Tracks (Performance Optimization)
        // Scans all remaining tracks in a single folder query to avoid repeated SAF directory walk overhead.
        val availabilityByFileId = availabilityChecker.checkBookFiles(candidateFiles)
        val readyFileIds = mutableListOf<String>()
        val missingFileIds = mutableListOf<String>()
        var nextAvailable: Pair<Int, BookFileEntity>? = null
        candidateFiles.forEachIndexed { offset, candidate ->
            val isReady = availabilityByFileId[candidate.id]?.isAvailable == true
            if (isReady) {
                readyFileIds.add(candidate.id)
            } else {
                missingFileIds.add(candidate.id)
            }
            if (isReady) {
                val queueIndex = afterQueueIndex + 1 + offset
                if (nextAvailable == null) nextAvailable = queueIndex to candidate
            }
        }
        if (readyFileIds.isNotEmpty()) {
            bookDao.updateBookFileStatuses(readyFileIds, AudiobookSchema.FileStatus.READY)
        }
        if (missingFileIds.isNotEmpty()) {
            bookDao.updateBookFileStatuses(missingFileIds, AudiobookSchema.FileStatus.MISSING)
        }

        recalculatePlaybackBookStatus(bookId)
        return nextAvailable
    }

    private suspend fun allLocalFiles(files: List<BookFileEntity>): Boolean {
        // Validate Local Source Constraints (Protocol Optimization)
        // Restricts bulk checks to local SAF root paths; remote sources retain single-file verification with retry windows.
        val sourceTypesByRootId = files.map { it.rootId }.distinct().associateWith { rootId ->
            libraryRootDao.getRootById(rootId)?.sourceType
        }
        return files.all { file -> sourceTypesByRootId[file.rootId] == AudiobookSchema.LibrarySourceType.SAF }
    }

    private suspend fun findNextAvailableRemoteAware(
        bookId: String,
        afterQueueIndex: Int,
        files: List<BookFileEntity>
    ): Pair<Int, BookFileEntity>? {
        for (queueIndex in (afterQueueIndex + 1)..files.lastIndex) {
            val candidate = files[queueIndex]
            val isReady = checkFileAvailable(candidate)
            updatePlaybackFileStatus(candidate, isReady)
            if (isReady) {
                recalculatePlaybackBookStatus(bookId)
                return queueIndex to candidate
            }
        }
        recalculatePlaybackBookStatus(bookId)
        return null
    }

    /**
     * Map Progress to Audiobook File (Track Resolution Helper)
     *
     * Maps the progress pointer to its respective audiobook track based on file IDs, sequence indices, or global offset.
     */
    fun resolveProgressFile(progress: BookProgressEntity?, files: List<BookFileEntity>): BookFileEntity? {
        if (progress == null) return null
        return progress.bookFileId?.let { id -> files.firstOrNull { it.id == id } }
            ?: files.getOrNull(progress.currentFileIndex)
            ?: files.getOrNull(PositionMapper.globalToFilePosition(progress.globalPositionMs, files).first)
    }

    /**
     * Update Single Track State (Persistence Handler)
     */
    private suspend fun updatePlaybackFileStatus(file: BookFileEntity, isReady: Boolean) {
        val status = if (isReady) AudiobookSchema.FileStatus.READY else AudiobookSchema.FileStatus.MISSING
        bookDao.updateBookFileStatus(file.id, status)
    }

    /**
     * Recalculate Book-wide Availability Status (State Recalculation)
     */
    private suspend fun recalculatePlaybackBookStatus(bookId: String) {
        val files = bookDao.getFilesForBookList(bookId)
        bookDao.updateBookStatus(bookId, playbackBookStatusFromFiles(files))
    }

    /**
     * Compute Book Status from File Statuses (Availability Resolution Matrix)
     */
    private fun playbackBookStatusFromFiles(files: List<BookFileEntity>): String {
        val readyCount = files.count { it.status == AudiobookSchema.FileStatus.READY }
        val missingCount = files.count { it.status == AudiobookSchema.FileStatus.MISSING }
        return when {
            files.isEmpty() || readyCount == 0 -> AudiobookSchema.BookStatus.UNAVAILABLE
            missingCount > 0 -> AudiobookSchema.BookStatus.PARTIAL
            else -> AudiobookSchema.BookStatus.READY
        }
    }

    private companion object {
        // Network Recovery Wait Budget (Resiliency Configuration)
        // Limits remote checks to three tries over ~2.3s, allowing brief connection restoration before skipping.
        val REMOTE_REACHABILITY_RETRY_DELAYS_MS = longArrayOf(800L, 1_500L, 0L)
    }
}