package com.viel.aplayer.application.download

import com.viel.aplayer.data.dao.BookDao
import com.viel.aplayer.data.dao.DownloadMetadataDao
import com.viel.aplayer.data.db.AudiobookSchema
import com.viel.aplayer.data.entity.DownloadMetadataEntity
import com.viel.aplayer.data.entity.DownloadStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/**
 * Book Cache State (UI-facing manual offline cache state machine)
 * NONE is derived by the read model from missing metadata, while all other values mirror durable download aggregate states.
 */
enum class BookCacheState {
    NONE,
    // Native Local Source Status (Flags local SAF books that require no server synchronization)
    // Presentation modules check this state to completely omit manual download action buttons.
    LOCAL,
    QUEUED,
    DOWNLOADING,
    PAUSED,
    COMPLETED,
    FAILED
}

/**
 * Book Cache Status (Immutable detail and management screen cache projection)
 * Carries only display-safe aggregate values so presentation code never needs DownloadMetadataEntity or Media3 Download objects.
 */
data class BookCacheStatus(
    val state: BookCacheState,
    val totalFiles: Int,
    val completedFiles: Int,
    val totalBytes: Long,
    val downloadedBytes: Long
) {
    val progressPercent: Int
        get() = if (totalBytes > 0L) {
            ((downloadedBytes.coerceIn(0L, totalBytes) * 100L) / totalBytes).toInt()
        } else if (totalFiles > 0) {
            ((completedFiles.coerceIn(0, totalFiles) * 100) / totalFiles)
        } else {
            0
        }

    companion object {
        // No Cache Status (Represent the UI-facing absence of a manual download row)
        // NONE is derived outside Room so DownloadMetadataEntity can keep only durable Media3 aggregate states.
        fun none(): BookCacheStatus =
            BookCacheStatus(
                state = BookCacheState.NONE,
                totalFiles = 0,
                completedFiles = 0,
                totalBytes = 0L,
                downloadedBytes = 0L
            )

        /**
         * Local Cache Status (Represents a book that resides permanently on the local filesystem)
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
 * Download Status Read Model (Presentation boundary for manual cache status)
 * Keeps UI state derivation in the download application module instead of duplicating Room mapping in individual screens.
 */
interface DownloadStatusReadModel {
    /**
     * Observe Book Cache Status (Expose one book-level manual cache state to presentation)
     * UI layers consume this derived status instead of reading DownloadMetadataEntity or Media3 DownloadIndex directly.
     */
    fun observeBookCacheStatus(bookId: String): Flow<BookCacheStatus>
}

/**
 * Room Download Status Read Model (Adapt durable download metadata into UI cache status)
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
            // Local Source Interception (Detect local storage assets before processing remote queue details)
            // Books under SAF roots reside permanently in local sandboxed storage and require no manual downloads.
            if (sourceType == AudiobookSchema.LibrarySourceType.SAF) {
                BookCacheStatus.local()
            } else {
                metadata.toBookCacheStatus()
            }
        }
}

// Download Metadata Projection (Convert durable Room aggregates into UI cache status)
// Missing metadata becomes BookCacheState.NONE, keeping every presentation surface on the same derived state machine.
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
