package com.viel.oto.application.download

import com.viel.oto.data.dao.DownloadMetadataDao
import com.viel.oto.data.entity.DownloadMetadataEntity
import com.viel.oto.data.entity.DownloadStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class CacheMaintenanceCommandsTest {
    @Test
    fun `deleteAllManualDownloads should delegate distinct book ids to download controller`() = runBlocking {
        val downloadController = RecordingDownloadController()
        val commands = DefaultCacheMaintenanceCommands(
            downloadMetadataDao = StaticDownloadMetadataDao(
                listOf(
                    metadata("book-1"),
                    metadata("book-2"),
                    metadata("book-1")
                )
            ),
            downloadController = downloadController
        )

        commands.deleteAllManualDownloads()

        assertEquals(listOf("book-1", "book-2"), downloadController.deletedBookIds)
    }

    private class RecordingDownloadController : DownloadController {
        val deletedBookIds: MutableList<String> = mutableListOf()

        override suspend fun downloadBook(bookId: String) = Unit
        override suspend fun cancelDownload(bookId: String) = Unit
        override suspend fun pauseDownload(bookId: String) = Unit
        override suspend fun resumeDownload(bookId: String) = Unit
        override suspend fun deleteDownload(bookId: String) {
            deletedBookIds += bookId
        }
    }

    private class StaticDownloadMetadataDao(
        private val metadata: List<DownloadMetadataEntity>
    ) : DownloadMetadataDao {
        override suspend fun insertOrReplace(metadata: DownloadMetadataEntity) = Unit
        override suspend fun getMetadata(bookId: String): DownloadMetadataEntity? = metadata.firstOrNull { it.bookId == bookId }
        override fun observeMetadata(bookId: String): Flow<DownloadMetadataEntity?> = flowOf(metadata.firstOrNull { it.bookId == bookId })
        override fun observeAllMetadata(): Flow<List<DownloadMetadataEntity>> = flowOf(metadata)
        override suspend fun getAllMetadata(): List<DownloadMetadataEntity> = metadata
        override suspend fun getRecoverableTasks(): List<DownloadMetadataEntity> = metadata
        override suspend fun hasRecoverableTasks(): Boolean = metadata.isNotEmpty()
        override suspend fun getCompletedTaskCount(): Int = metadata.count { it.status == DownloadStatus.COMPLETED }
        override suspend fun deleteByBookId(bookId: String) = Unit
        override suspend fun delete(metadata: DownloadMetadataEntity) = Unit
    }

    private companion object {
        private fun metadata(bookId: String): DownloadMetadataEntity =
            DownloadMetadataEntity(
                bookId = bookId,
                status = DownloadStatus.COMPLETED,
                totalFiles = 1,
                completedFiles = 1,
                totalBytes = 100L,
                downloadedBytes = 100L,
                createdAt = 1L,
                updatedAt = 2L
            )
    }
}
