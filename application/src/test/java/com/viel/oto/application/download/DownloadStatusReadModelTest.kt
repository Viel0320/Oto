package com.viel.oto.application.download

import com.viel.oto.data.dao.BookDao
import com.viel.oto.data.dao.DownloadMetadataDao
import com.viel.oto.data.db.AudiobookSchema
import com.viel.oto.data.entity.DownloadMetadataEntity
import com.viel.oto.data.entity.DownloadStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import java.lang.reflect.Proxy

class DownloadStatusReadModelTest {
    @Test
    fun `missing metadata should emit none cache status`() = runBlocking {
        val readModel = RoomDownloadStatusReadModel(
            InMemoryDownloadMetadataDao(),
            fakeBookDao()
        )

        val status = readModel.observeBookCacheStatus(BOOK_ID).first()

        assertEquals(BookCacheState.NONE, status.state)
        assertEquals(0, status.progressPercent)
        assertEquals(true, status.supportsManualCacheAction)
    }

    @Test
    fun `metadata should emit bounded progress cache status`() = runBlocking {
        val readModel = RoomDownloadStatusReadModel(
            InMemoryDownloadMetadataDao(
                DownloadMetadataEntity(
                    bookId = BOOK_ID,
                    status = DownloadStatus.DOWNLOADING,
                    totalFiles = 4,
                    completedFiles = 2,
                    totalBytes = 1_000L,
                    downloadedBytes = 250L,
                    createdAt = 1L,
                    updatedAt = 2L
                )
            ),
            fakeBookDao()
        )

        val status = readModel.observeBookCacheStatus(BOOK_ID).first()

        assertEquals(BookCacheState.DOWNLOADING, status.state)
        assertEquals(4, status.totalFiles)
        assertEquals(2, status.completedFiles)
        assertEquals(25, status.progressPercent)
        assertEquals(true, status.supportsManualCacheAction)
    }

    @Test
    fun `saf source should emit local cache status`() = runBlocking {
        val readModel = RoomDownloadStatusReadModel(
            InMemoryDownloadMetadataDao(),
            fakeBookDao(AudiobookSchema.LibrarySourceType.SAF)
        )

        val status = readModel.observeBookCacheStatus(BOOK_ID).first()

        assertEquals(BookCacheState.LOCAL, status.state)
        assertEquals(0, status.progressPercent)
        assertEquals(false, status.supportsManualCacheAction)
    }

    private fun fakeBookDao(sourceType: AudiobookSchema.LibrarySourceType? = null): BookDao =
        Proxy.newProxyInstance(
            BookDao::class.java.classLoader,
            arrayOf(BookDao::class.java)
        ) { _, method, _ ->
            when (method.name) {
                "observeBookLibrarySourceType" -> flowOf(sourceType)
                else -> throw UnsupportedOperationException("Unexpected method: ${method.name}")
            }
        } as BookDao

    private class InMemoryDownloadMetadataDao(
        private val metadata: DownloadMetadataEntity? = null
    ) : DownloadMetadataDao {
        override suspend fun insertOrReplace(metadata: DownloadMetadataEntity) = Unit
        override suspend fun getMetadata(bookId: String): DownloadMetadataEntity? = metadata
        override fun observeMetadata(bookId: String): Flow<DownloadMetadataEntity?> = flowOf(metadata)
        override fun observeAllMetadata(): Flow<List<DownloadMetadataEntity>> = flowOf(listOfNotNull(metadata))
        override suspend fun getAllMetadata(): List<DownloadMetadataEntity> = listOfNotNull(metadata)
        override suspend fun getRecoverableTasks(): List<DownloadMetadataEntity> = listOfNotNull(metadata)
        override suspend fun hasRecoverableTasks(): Boolean = metadata != null
        override suspend fun getCompletedTaskCount(): Int = if (metadata?.status == DownloadStatus.COMPLETED) 1 else 0
        override suspend fun deleteByBookId(bookId: String) = Unit
        override suspend fun delete(metadata: DownloadMetadataEntity) = Unit
    }

    private companion object {
        private const val BOOK_ID = "book-1"
    }
}
