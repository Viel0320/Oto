package com.viel.oto.data.dao

import androidx.room.Room
import com.viel.oto.data.db.AppDatabase
import com.viel.oto.data.entity.DownloadMetadataEntity
import com.viel.oto.data.entity.DownloadStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class DownloadMetadataDaoTest {

    @Test
    fun `missing metadata should represent none without persisting none status`() = runBlocking {
        val database = inMemoryDatabase()
        try {
            val dao = database.downloadMetadataDao()

            assertNull(dao.getMetadata(BOOK_ID))
            assertNull(dao.observeMetadata(BOOK_ID).first())
        } finally {
            database.close()
        }
    }

    @Test
    fun `recoverable query should exclude completed aggregates`() = runBlocking {
        val database = inMemoryDatabase()
        try {
            val dao = database.downloadMetadataDao()
            dao.insertOrReplace(metadata(bookId = "queued", status = DownloadStatus.QUEUED, updatedAt = 40L))
            dao.insertOrReplace(metadata(bookId = "downloading", status = DownloadStatus.DOWNLOADING, updatedAt = 30L))
            dao.insertOrReplace(metadata(bookId = "paused", status = DownloadStatus.PAUSED, updatedAt = 20L))
            dao.insertOrReplace(metadata(bookId = "failed", status = DownloadStatus.FAILED, updatedAt = 10L))
            dao.insertOrReplace(metadata(bookId = "completed", status = DownloadStatus.COMPLETED, updatedAt = 50L))

            val recoverable = dao.getRecoverableTasks().map { it.bookId }

            assertEquals(listOf("queued", "downloading", "paused", "failed"), recoverable)
            assertTrue(dao.hasRecoverableTasks())
        } finally {
            database.close()
        }
    }

    @Test
    fun `delete by book id should return status to derived none`() = runBlocking {
        val database = inMemoryDatabase()
        try {
            val dao = database.downloadMetadataDao()
            dao.insertOrReplace(metadata(bookId = BOOK_ID, status = DownloadStatus.FAILED))
            dao.deleteByBookId(BOOK_ID)

            assertNull(dao.getMetadata(BOOK_ID))
            assertFalse(dao.hasRecoverableTasks())
        } finally {
            database.close()
        }
    }

    private fun inMemoryDatabase(): AppDatabase =
        Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            AppDatabase::class.java
        )
            .allowMainThreadQueries()
            .build()

    private fun metadata(
        bookId: String,
        status: DownloadStatus,
        updatedAt: Long = 1L
    ): DownloadMetadataEntity =
        DownloadMetadataEntity(
            bookId = bookId,
            status = status,
            totalFiles = 2,
            completedFiles = if (status == DownloadStatus.COMPLETED) 2 else 1,
            totalBytes = 200L,
            downloadedBytes = 100L,
            createdAt = 1L,
            updatedAt = updatedAt
        )

    private companion object {
        const val BOOK_ID = "book-download-metadata"
    }
}
