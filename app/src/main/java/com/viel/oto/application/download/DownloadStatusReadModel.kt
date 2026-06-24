package com.viel.oto.application.download

import com.viel.oto.data.dao.BookDao
import com.viel.oto.data.dao.DownloadMetadataDao
import com.viel.oto.data.db.AudiobookSchema
import com.viel.oto.data.entity.DownloadMetadataEntity
import com.viel.oto.data.entity.DownloadStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/**
 * UI-facing manual offline cache state machine.
 * NONE is derived by the read model from missing metadata, while all other values mirror durable download aggregate states.
 */
enum class BookCacheState {
    NONE,
    LOCAL,
    QUEUED,
    DOWNLOADING,
    PAUSED,
    COMPLETED,
    FAILED
}

/**
 * Immutable detail and management screen cache projection.
 * Carries only display-safe aggregate values so presentation code never needs DownloadMetadataEntity or Media3 Download objects.
 */
data class BookCacheStatus(
    val state: BookCacheState,
    val totalFiles: Int,
    val completedFiles: Int,
    val totalBytes: Long,
    val downloadedBytes: Long
) {
    /**
     * Exposes whether screens should offer manual cache controls for this projected book state.
     * Local filesystem books are already device-local, so manual cache commands are not meaningful.
     */
    val supportsManualCacheAction: Boolean
        get() = state != BookCacheState.LOCAL

    val progressPercent: Int
        get() = if (totalBytes > 0L) {
            ((downloadedBytes.coerceIn(0L, totalBytes) * 100L) / totalBytes).toInt()
        } else if (totalFiles > 0) {
            ((completedFiles.coerceIn(0, totalFiles) * 100) / totalFiles)
        } else {
            0
        }

    companion object {
        fun none(): BookCacheStatus =
            BookCacheStatus(
                state = BookCacheState.NONE,
                totalFiles = 0,
                completedFiles = 0,
                totalBytes = 0L,
                downloadedBytes = 0L
            )

        /**
         * Represents a book that resides permanently on the local filesystem.
         * Omit manual cache metrics and return a static local status instance.
         */
        fun local(): BookCacheStatus =
            BookCacheStatus(
                state = BookCacheState.LOCAL,
                totalFiles = 0,
                completedFiles = 0,
                totalBytes = 0L,
                downloadedBytes = 0L
            )
    }
}

/**
 * Presentation boundary for manual cache status.
 * Keeps UI state derivation in the download application module instead of duplicating Room mapping in individual screens.
 */
interface DownloadStatusReadModel {
    /**
     * Expose one book-level manual cache state to presentation.
     * UI layers consume this derived status instead of reading DownloadMetadataEntity or Media3 DownloadIndex directly.
     */
    fun observeBookCacheStatus(bookId: String): Flow<BookCacheStatus>
}

/**
 * Adapt durable download metadata into UI cache status.
 * Converts missing rows into NONE and clamps progress fields before they reach Compose state.
 */
class RoomDownloadStatusReadModel(
    private val downloadMetadataDao: DownloadMetadataDao,
    private val bookDao: BookDao
) : DownloadStatusReadModel {
    override fun observeBookCacheStatus(bookId: String): Flow<BookCacheStatus> =
        combine(
            downloadMetadataDao.observeMetadata(bookId),
            bookDao.observeBookLibrarySourceType(bookId)
        ) { metadata, sourceType ->
            if (sourceType == AudiobookSchema.LibrarySourceType.SAF) {
                BookCacheStatus.local()
            } else {
                metadata.toBookCacheStatus()
            }
        }
}

internal fun DownloadMetadataEntity?.toBookCacheStatus(): BookCacheStatus {
    if (this == null) return BookCacheStatus.none()
    return BookCacheStatus(
        state = when (status) {
            DownloadStatus.QUEUED -> BookCacheState.QUEUED
            DownloadStatus.DOWNLOADING -> BookCacheState.DOWNLOADING
            DownloadStatus.PAUSED -> BookCacheState.PAUSED
            DownloadStatus.COMPLETED -> BookCacheState.COMPLETED
            DownloadStatus.FAILED -> BookCacheState.FAILED
        },
        totalFiles = totalFiles.coerceAtLeast(0),
        completedFiles = completedFiles.coerceIn(0, totalFiles.coerceAtLeast(0)),
        totalBytes = totalBytes.coerceAtLeast(0L),
        downloadedBytes = downloadedBytes.coerceAtLeast(0L)
    )
}
