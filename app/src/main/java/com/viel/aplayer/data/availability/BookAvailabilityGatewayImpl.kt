package com.viel.aplayer.data.availability

import com.viel.aplayer.data.dao.BookDao
import com.viel.aplayer.data.dao.LibraryRootDao
import com.viel.aplayer.data.db.AudiobookSchema
import com.viel.aplayer.data.entity.BookFileEntity
import com.viel.aplayer.data.entity.BookProgressEntity
import com.viel.aplayer.library.availability.AvailabilityChecker
import com.viel.aplayer.library.availability.AvailabilityResult
import com.viel.aplayer.timeline.PositionMapper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.milliseconds

/**
 * Single write-status seam for audiobook reachability.
 *
 * Centralizes book and track availability workflows so callers can tell from method names whether a call only probes storage
 * or also refreshes Room status fields.
 */
class BookAvailabilityGatewayImpl(
    private val bookDao: BookDao,
    private val libraryRootDao: LibraryRootDao,
    private val availabilityChecker: AvailabilityChecker
) : BookAvailabilityGateway {
    /**
     * Checks reachability without mutating stored status.
     *
     * Used before destructive book deletion where the caller only needs physical existence and must not rewrite availability state.
     */
    override suspend fun checkPrimaryAudioFileExistsWithoutStatusRefresh(bookId: String): Boolean = withContext(Dispatchers.IO) {
        val primaryFile = bookDao.getFilesForBookList(bookId).firstOrNull() ?: return@withContext false
        availabilityChecker.checkBookFile(primaryFile).isAvailable
    }

    /**
     * Probes all book files and writes file/book statuses.
     *
     * Detail screens need a current playability decision, so this method explicitly refreshes READY/MISSING file rows and the aggregate book status.
     */
    override suspend fun refreshDetailAvailabilityStatus(bookId: String): Boolean {
        return refreshDetailAvailabilityStatusWithResult(bookId).isAvailable
    }

    /**
     * Probes all book files and writes file/book statuses.
     *
     * Keeps the full aggregation result local to BookAvailabilityGatewayImpl for tests or future diagnostics without widening the facade contract.
     */
    suspend fun refreshDetailAvailabilityStatusWithResult(bookId: String): BookAvailabilityRefreshResult = withContext(Dispatchers.IO) {
        val files = bookDao.getFilesForBookList(bookId)
        if (files.isEmpty()) {
            bookDao.updateBookStatus(bookId, AudiobookSchema.BookStatus.UNAVAILABLE)
            return@withContext BookAvailabilityRefreshResult(
                isAvailable = false,
                bookStatus = AudiobookSchema.BookStatus.UNAVAILABLE,
                readyAudioCount = 0,
                missingAudioCount = 0
            )
        }

        val availabilityByFileId = availabilityChecker.checkBookFiles(files)
        val readyFileIds = mutableListOf<String>()
        val missingFileIds = mutableListOf<String>()
        val nextFileStatuses = files.associate { file ->
            val result = availabilityByFileId[file.id] ?: transientUnknownResult()
            val nextStatus = AvailabilityPersistencePolicy.nextFileStatus(
                previousStatus = file.status,
                result = result
            )
            when (nextStatus) {
                AudiobookSchema.FileStatus.READY -> if (file.status != AudiobookSchema.FileStatus.READY) readyFileIds.add(file.id)
                AudiobookSchema.FileStatus.MISSING -> if (file.status != AudiobookSchema.FileStatus.MISSING) missingFileIds.add(file.id)
            }
            file.id to nextStatus
        }
        refreshFileStatuses(readyFileIds, missingFileIds)

        val readyCount = nextFileStatuses.values.count { it == AudiobookSchema.FileStatus.READY }
        val missingCount = nextFileStatuses.values.count { it == AudiobookSchema.FileStatus.MISSING }
        val bookStatus = bookStatusFromCounts(fileCount = files.size, readyCount = readyCount, missingCount = missingCount)
        bookDao.updateBookStatus(bookId, bookStatus)

        BookAvailabilityRefreshResult(
            isAvailable = readyCount > 0,
            bookStatus = bookStatus,
            readyAudioCount = readyCount,
            missingAudioCount = missingCount
        )
    }

    /**
     * Probes the active progress track and writes file/book statuses.
     *
     * Playback restore and mini-player availability checks need this explicit refresh path because the result updates Room state.
     */
    override suspend fun refreshCurrentPlaybackFileAvailabilityStatus(bookId: String): Boolean = withContext(Dispatchers.IO) {
        val files = bookDao.getFilesForBookList(bookId)
        if (files.isEmpty()) {
            bookDao.updateBookStatus(bookId, AudiobookSchema.BookStatus.UNAVAILABLE)
            return@withContext false
        }

        val progress = bookDao.getProgressForBookSync(bookId)
        val targetFile = resolveProgressFile(progress, files) ?: files.first()
        val result = checkFileAvailability(targetFile)
        refreshPlaybackFileStatus(targetFile, result)
        refreshPlaybackBookStatus(bookId)
        result.isAvailable
    }

    /**
     * Revalidates a failed queue item and writes its status.
     *
     * ExoPlayer failure handling calls this path after IO errors so temporary remote failures can stay READY when retry probes succeed.
     */
    override suspend fun refreshPlaybackFileUnavailableStatus(bookId: String, queueIndex: Int) {
        withContext(Dispatchers.IO) {
            val files = bookDao.getFilesForBookList(bookId)
            files.getOrNull(queueIndex)?.let { failedFile ->
                val result = checkFileAvailability(failedFile)
                refreshPlaybackFileStatus(failedFile, result)
                refreshPlaybackBookStatus(bookId)
            }
        }
    }

    /**
     * Finds the next playable file and writes checked statuses.
     *
     * Disaster recovery needs both the next queue target and persisted READY/MISSING updates for every candidate it inspects.
     */
    override suspend fun findNextAvailablePlaybackFileAndRefreshStatus(
        bookId: String,
        afterQueueIndex: Int
    ): Pair<Int, BookFileEntity>? = withContext(Dispatchers.IO) {
        val files = bookDao.getFilesForBookList(bookId)
        if (files.isEmpty()) {
            bookDao.updateBookStatus(bookId, AudiobookSchema.BookStatus.UNAVAILABLE)
            return@withContext null
        }

        val candidateFiles = files.drop(afterQueueIndex + 1)
        if (!allLocalFiles(candidateFiles)) {
            return@withContext findNextAvailableRemoteAwareAndRefreshStatus(bookId, afterQueueIndex, files)
        }

        val availabilityByFileId = availabilityChecker.checkBookFiles(candidateFiles)
        val readyFileIds = mutableListOf<String>()
        val missingFileIds = mutableListOf<String>()
        var nextAvailable: Pair<Int, BookFileEntity>? = null
        candidateFiles.forEachIndexed { offset, candidate ->
            val result = availabilityByFileId[candidate.id] ?: transientUnknownResult()
            val nextStatus = AvailabilityPersistencePolicy.nextFileStatus(
                previousStatus = candidate.status,
                result = result
            )
            when (nextStatus) {
                AudiobookSchema.FileStatus.READY -> if (candidate.status != AudiobookSchema.FileStatus.READY) readyFileIds.add(candidate.id)
                AudiobookSchema.FileStatus.MISSING -> if (candidate.status != AudiobookSchema.FileStatus.MISSING) missingFileIds.add(candidate.id)
            }
            if (result.isAvailable && nextAvailable == null) {
                nextAvailable = afterQueueIndex + 1 + offset to candidate
            }
        }
        refreshFileStatuses(readyFileIds, missingFileIds)
        refreshPlaybackBookStatus(bookId)
        nextAvailable
    }

    private suspend fun checkFileAvailability(file: BookFileEntity): AvailabilityResult {
        val root = libraryRootDao.getRootById(file.rootId) ?: return notFoundResult()
        val isLocal = root.sourceType == AudiobookSchema.LibrarySourceType.SAF
        return if (isLocal) {
            availabilityChecker.checkBookFile(file)
        } else {
            checkRemoteFileAvailabilityWithGrace(file)
        }
    }

    private suspend fun checkRemoteFileAvailabilityWithGrace(file: BookFileEntity): AvailabilityResult {
        var lastResult: AvailabilityResult = transientUnknownResult()
        REMOTE_REACHABILITY_RETRY_DELAYS_MS.forEachIndexed { attempt, delayMs ->
            val result = availabilityChecker.checkBookFile(file)
            lastResult = result
            if (result.isAvailable || AvailabilityPersistencePolicy.isDefiniteMissing(result)) return result
            if (attempt < REMOTE_REACHABILITY_RETRY_DELAYS_MS.lastIndex) delay(delayMs.milliseconds)
        }
        return lastResult
    }

    private suspend fun allLocalFiles(files: List<BookFileEntity>): Boolean {
        val sourceTypesByRootId = files.map { it.rootId }.distinct().associateWith { rootId ->
            libraryRootDao.getRootById(rootId)?.sourceType
        }
        return files.all { file -> sourceTypesByRootId[file.rootId] == AudiobookSchema.LibrarySourceType.SAF }
    }

    private suspend fun findNextAvailableRemoteAwareAndRefreshStatus(
        bookId: String,
        afterQueueIndex: Int,
        files: List<BookFileEntity>
    ): Pair<Int, BookFileEntity>? {
        for (queueIndex in (afterQueueIndex + 1)..files.lastIndex) {
            val candidate = files[queueIndex]
            val result = checkFileAvailability(candidate)
            refreshPlaybackFileStatus(candidate, result)
            if (result.isAvailable) {
                refreshPlaybackBookStatus(bookId)
                return queueIndex to candidate
            }
        }
        refreshPlaybackBookStatus(bookId)
        return null
    }

    private fun resolveProgressFile(progress: BookProgressEntity?, files: List<BookFileEntity>): BookFileEntity? {
        if (progress == null) return null
        return progress.bookFileId?.let { id -> files.firstOrNull { it.id == id } }
            ?: files.getOrNull(progress.currentFileIndex)
            ?: files.getOrNull(PositionMapper.globalToFilePosition(progress.globalPositionMs, files).first)
    }

    private suspend fun refreshFileStatuses(readyFileIds: List<String>, missingFileIds: List<String>) {
        if (readyFileIds.isNotEmpty()) {
            bookDao.updateBookFileStatuses(readyFileIds, AudiobookSchema.FileStatus.READY)
        }
        if (missingFileIds.isNotEmpty()) {
            bookDao.updateBookFileStatuses(missingFileIds, AudiobookSchema.FileStatus.MISSING)
        }
    }

    private suspend fun refreshPlaybackFileStatus(file: BookFileEntity, result: AvailabilityResult) {
        val status = AvailabilityPersistencePolicy.nextFileStatus(
            previousStatus = file.status,
            result = result
        )
        if (status != file.status) {
            bookDao.updateBookFileStatus(file.id, status)
        }
    }

    private suspend fun refreshPlaybackBookStatus(bookId: String) {
        val files = bookDao.getFilesForBookList(bookId)
        bookDao.updateBookStatus(bookId, playbackBookStatusFromFiles(files))
    }

    private fun bookStatusFromCounts(fileCount: Int, readyCount: Int, missingCount: Int): AudiobookSchema.BookStatus =
        when {
            fileCount == 0 || readyCount == 0 -> AudiobookSchema.BookStatus.UNAVAILABLE
            missingCount > 0 -> AudiobookSchema.BookStatus.PARTIAL
            else -> AudiobookSchema.BookStatus.READY
        }

    private fun playbackBookStatusFromFiles(files: List<BookFileEntity>): AudiobookSchema.BookStatus {
        val readyCount = files.count { it.status == AudiobookSchema.FileStatus.READY }
        val missingCount = files.count { it.status == AudiobookSchema.FileStatus.MISSING }
        return bookStatusFromCounts(fileCount = files.size, readyCount = readyCount, missingCount = missingCount)
    }

    private fun notFoundResult(): AvailabilityResult =
        AvailabilityResult(
            status = AudiobookSchema.AvailabilityStatus.NOT_FOUND,
            errorCode = AudiobookSchema.AvailabilityStatus.NOT_FOUND.name
        )

    private fun transientUnknownResult(): AvailabilityResult =
        AvailabilityResult(
            status = AudiobookSchema.AvailabilityStatus.UNKNOWN,
            errorCode = AudiobookSchema.AvailabilityStatus.UNKNOWN.name
        )

    private companion object {
        val REMOTE_REACHABILITY_RETRY_DELAYS_MS = longArrayOf(800L, 1_500L, 0L)
    }
}

/**
 * Separates probe results from durable file-status writes.
 * Only confirmed file absence changes Room to MISSING; temporary remote states preserve locality by leaving previous status untouched.
 */
internal object AvailabilityPersistencePolicy {
    /**
     * Maps a probe result to the durable file status that should remain in Room.
     * AVAILABLE always restores READY, NOT_FOUND confirms MISSING, and transient or credential failures keep the previous persisted status.
     */
    fun nextFileStatus(previousStatus: AudiobookSchema.FileStatus, result: AvailabilityResult): AudiobookSchema.FileStatus =
        when {
            result.isAvailable -> AudiobookSchema.FileStatus.READY
            isDefiniteMissing(result) -> AudiobookSchema.FileStatus.MISSING
            else -> previousStatus
        }

    /**
     * Identifies probe results that truly mean the physical file is absent.
     * Network, timeout, server, authentication, and permission failures are intentionally excluded because they describe access state, not file existence.
     */
    fun isDefiniteMissing(result: AvailabilityResult): Boolean =
        result.status == AudiobookSchema.AvailabilityStatus.NOT_FOUND
}

/**
 * Reports persisted availability aggregation.
 *
 * Carries the book-level status written by BookAvailabilityGatewayImpl plus the file counts used to derive it.
 */
data class BookAvailabilityRefreshResult(
    val isAvailable: Boolean,
    val bookStatus: AudiobookSchema.BookStatus,
    val readyAudioCount: Int,
    val missingAudioCount: Int
)
