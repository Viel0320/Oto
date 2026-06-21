package com.viel.aplayer.application.download

import com.viel.aplayer.data.dao.DownloadMetadataDao
import com.viel.aplayer.data.entity.DownloadMetadataEntity
import com.viel.aplayer.data.entity.DownloadStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DownloadProgressPollerTest {
    @Test
    fun `poller should refresh active download progress until terminal state`() = runTest {
        val dao = FakeDownloadMetadataDao(metadata(DownloadStatus.QUEUED, downloadedBytes = 0L))
        val reconciled = mutableListOf<String>()
        val poller = DownloadProgressPoller(
            downloadMetadataDao = dao,
            downloadBookReconcilerProvider = {
                object : DownloadBookReconciler {
                    override suspend fun reconcileBook(bookId: String) {
                        reconciled += bookId
                        val next = when (reconciled.size) {
                            1 -> metadata(DownloadStatus.DOWNLOADING, downloadedBytes = 25L)
                            2 -> metadata(DownloadStatus.DOWNLOADING, downloadedBytes = 75L)
                            else -> metadata(DownloadStatus.COMPLETED, downloadedBytes = 100L)
                        }
                        dao.insertOrReplace(next)
                    }
                }
            },
            scope = this,
            pollIntervalMs = 1_000L
        )

        poller.start()
        poller.start()
        runCurrent()
        advanceTimeBy(1_000L)
        runCurrent()
        advanceTimeBy(1_000L)
        runCurrent()
        advanceTimeBy(1_000L)
        runCurrent()

        assertEquals(listOf(BOOK_ID, BOOK_ID, BOOK_ID), reconciled)
        assertEquals(DownloadStatus.COMPLETED, dao.getMetadata(BOOK_ID)?.status)
        assertEquals(100L, dao.getMetadata(BOOK_ID)?.downloadedBytes)
    }

    private class FakeDownloadMetadataDao(
        initial: DownloadMetadataEntity
    ) : DownloadMetadataDao {
        private val rows = linkedMapOf(initial.bookId to initial)

        override suspend fun insertOrReplace(metadata: DownloadMetadataEntity) {
            rows[metadata.bookId] = metadata
        }

        override suspend fun getMetadata(bookId: String): DownloadMetadataEntity? = rows[bookId]

        override fun observeMetadata(bookId: String): Flow<DownloadMetadataEntity?> = flowOf(rows[bookId])

        override fun observeAllMetadata(): Flow<List<DownloadMetadataEntity>> = flowOf(rows.values.toList())

        override suspend fun getAllMetadata(): List<DownloadMetadataEntity> = rows.values.toList()

        override suspend fun getRecoverableTasks(): List<DownloadMetadataEntity> =
            rows.values.filter { row -> row.status != DownloadStatus.COMPLETED }

        override suspend fun hasRecoverableTasks(): Boolean = getRecoverableTasks().isNotEmpty()

        override suspend fun getCompletedTaskCount(): Int =
            rows.values.count { row -> row.status == DownloadStatus.COMPLETED }

        override suspend fun deleteByBookId(bookId: String) {
            rows.remove(bookId)
        }

        override suspend fun delete(metadata: DownloadMetadataEntity) {
            rows.remove(metadata.bookId)
        }
    }

    private companion object {
        private const val BOOK_ID = "book-1"

        fun metadata(status: DownloadStatus, downloadedBytes: Long): DownloadMetadataEntity =
            DownloadMetadataEntity(
                bookId = BOOK_ID,
                status = status,
                totalFiles = 1,
                completedFiles = if (status == DownloadStatus.COMPLETED) 1 else 0,
                totalBytes = 100L,
                downloadedBytes = downloadedBytes,
                createdAt = 1L,
                updatedAt = downloadedBytes
            )
    }
}
