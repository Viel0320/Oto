package com.viel.aplayer.application.download

import com.viel.aplayer.data.entity.BookFileEntity
import com.viel.aplayer.data.entity.DownloadMetadataEntity
import com.viel.aplayer.data.entity.DownloadStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BookDownloadAggregatorTest {
    @Test
    fun `aggregate should mark completed only when all files complete`() {
        val aggregate = BookDownloadAggregator.aggregate(
            bookId = BOOK_ID,
            files = listOf(testFile("file-1", 100L), testFile("file-2", 200L)),
            snapshots = listOf(
                FileDownloadSnapshot("file-1", FileDownloadState.COMPLETED, 100L, 100L),
                FileDownloadSnapshot("file-2", FileDownloadState.COMPLETED, 200L, 200L)
            ),
            existing = null,
            nowMillis = 10L
        )

        // Completed Book Projection (Requires every file-level Media3 download to be complete)
        // This prevents a multi-file book from showing as cached when only the first track is already available.
        requireNotNull(aggregate)
        assertEquals(DownloadStatus.COMPLETED, aggregate.status)
        assertEquals(2, aggregate.completedFiles)
        assertEquals(2, aggregate.totalFiles)
        assertEquals(300L, aggregate.downloadedBytes)
        assertEquals(300L, aggregate.totalBytes)
    }

    @Test
    fun `aggregate should prioritize failed over partial progress`() {
        val aggregate = BookDownloadAggregator.aggregate(
            bookId = BOOK_ID,
            files = listOf(testFile("file-1", 100L), testFile("file-2", 200L)),
            snapshots = listOf(
                FileDownloadSnapshot("file-1", FileDownloadState.DOWNLOADING, 50L, 100L),
                FileDownloadSnapshot("file-2", FileDownloadState.FAILED, 0L, 200L)
            ),
            existing = existingMetadata(),
            nowMillis = 20L
        )

        // Failure Dominance (Any failed file makes the book-level manual download retryable)
        // Keeping the existing createdAt preserves the first user action timestamp while updatedAt tracks the latest reconciliation.
        requireNotNull(aggregate)
        assertEquals(DownloadStatus.FAILED, aggregate.status)
        assertEquals(5L, aggregate.createdAt)
        assertEquals(20L, aggregate.updatedAt)
    }

    @Test
    fun `aggregate should treat missing download requests as queued for repair`() {
        val aggregate = BookDownloadAggregator.aggregate(
            bookId = BOOK_ID,
            files = listOf(testFile("file-1", 100L), testFile("file-2", 200L)),
            snapshots = listOf(
                FileDownloadSnapshot("file-1", FileDownloadState.COMPLETED, 100L, 100L)
            ),
            existing = null,
            nowMillis = 30L
        )

        // Missing Request Repair State (Absent DownloadIndex rows keep the book recoverable)
        // Recovery can resubmit only missing files instead of losing the durable book-level task.
        requireNotNull(aggregate)
        assertEquals(DownloadStatus.QUEUED, aggregate.status)
        assertEquals(1, aggregate.completedFiles)
        assertEquals(2, aggregate.totalFiles)
    }

    @Test
    fun `aggregate should return null for books without downloadable files`() {
        val aggregate = BookDownloadAggregator.aggregate(
            bookId = BOOK_ID,
            files = emptyList(),
            snapshots = emptyList(),
            existing = null,
            nowMillis = 40L
        )

        // Empty Remote File Set (No metadata row is persisted when there is no remote file to cache)
        // SAF-only books derive BookCacheStatus.None rather than creating a meaningless download task.
        assertNull(aggregate)
    }

    private fun existingMetadata(): DownloadMetadataEntity =
        DownloadMetadataEntity(
            bookId = BOOK_ID,
            status = DownloadStatus.DOWNLOADING,
            totalFiles = 2,
            completedFiles = 0,
            totalBytes = 300L,
            downloadedBytes = 10L,
            createdAt = 5L,
            updatedAt = 6L
        )

    private fun testFile(id: String, fileSize: Long): BookFileEntity =
        BookFileEntity(
            id = id,
            bookId = BOOK_ID,
            rootId = "root-1",
            index = id.takeLast(1).toInt(),
            sourcePath = "$id.mp3",
            sourceIdentity = id,
            displayName = "$id.mp3",
            durationMs = 1_000L,
            fileSize = fileSize,
            lastModified = 0L
        )

    private companion object {
        const val BOOK_ID = "book-1"
    }
}
