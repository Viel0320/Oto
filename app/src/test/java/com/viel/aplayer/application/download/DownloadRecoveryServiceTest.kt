package com.viel.aplayer.application.download

import com.viel.aplayer.data.dao.DownloadMetadataDao
import com.viel.aplayer.data.entity.DownloadMetadataEntity
import com.viel.aplayer.data.entity.DownloadStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadRecoveryServiceTest {
    @Test
    fun `recovery should not resolve sync service when no recoverable tasks exist`() = runBlocking {
        val dao = FakeDownloadMetadataDao(hasRecoverable = false)
        var providerCalls = 0
        var progressPollerStarts = 0
        val recovery = DownloadRecoveryService(
            downloadMetadataDao = dao,
            downloadBookReconcilerProvider = {
                providerCalls += 1
                error("Sync service should stay lazy")
            },
            progressPollerStarter = { progressPollerStarts += 1 }
        )

        val recovered = recovery.recoverIfNeeded()

        // Smart Startup Gate (Skips DownloadManager-backed sync when Room has no recoverable tasks)
        // This locks the design requirement that completed or absent downloads do not start download runtime on app launch.
        assertFalse(recovered)
        assertEquals(0, providerCalls)
        assertEquals(0, progressPollerStarts)
    }

    @Test
    fun `recovery should reconcile every recoverable task`() = runBlocking {
        val dao = FakeDownloadMetadataDao(hasRecoverable = true)
        val reconciled = mutableListOf<String>()
        var progressPollerStarts = 0
        val recovery = DownloadRecoveryService(
            downloadMetadataDao = dao,
            downloadBookReconcilerProvider = {
                object : DownloadBookReconciler {
                    override suspend fun reconcileBook(bookId: String) {
                        reconciled += bookId
                    }
                }
            },
            progressPollerStarter = { progressPollerStarts += 1 }
        )

        val recovered = recovery.recoverIfNeeded()

        // Recoverable Task Reconciliation (Every durable non-terminal row is passed through sync and polling resumes)
        // Startup recovery can resume active tasks without a user click, so it must also wake the byte-progress sampler after reconciliation.
        assertTrue(recovered)
        assertEquals(listOf("book-1", "book-2"), reconciled)
        assertEquals(1, progressPollerStarts)
    }

    private class FakeDownloadMetadataDao(
        private val hasRecoverable: Boolean
    ) : DownloadMetadataDao {
        override suspend fun insertOrReplace(metadata: DownloadMetadataEntity) = Unit
        override suspend fun getMetadata(bookId: String): DownloadMetadataEntity? = null
        override fun observeMetadata(bookId: String): Flow<DownloadMetadataEntity?> = flowOf(null)
        override fun observeAllMetadata(): Flow<List<DownloadMetadataEntity>> = flowOf(emptyList())
        override suspend fun getAllMetadata(): List<DownloadMetadataEntity> = getRecoverableTasks()
        override suspend fun getRecoverableTasks(): List<DownloadMetadataEntity> =
            if (hasRecoverable) {
                listOf(metadata("book-1"), metadata("book-2"))
            } else {
                emptyList()
            }

        override suspend fun hasRecoverableTasks(): Boolean = hasRecoverable
        override suspend fun getCompletedTaskCount(): Int = 0
        override suspend fun deleteByBookId(bookId: String) = Unit
        override suspend fun delete(metadata: DownloadMetadataEntity) = Unit

        private fun metadata(bookId: String): DownloadMetadataEntity =
            DownloadMetadataEntity(
                bookId = bookId,
                status = DownloadStatus.QUEUED,
                totalFiles = 1,
                completedFiles = 0,
                totalBytes = 100L,
                downloadedBytes = 0L,
                createdAt = 1L,
                updatedAt = 1L
            )
    }

}
