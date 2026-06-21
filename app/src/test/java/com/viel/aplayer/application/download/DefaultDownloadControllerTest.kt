package com.viel.aplayer.application.download

import androidx.media3.exoplayer.offline.DownloadRequest
import com.viel.aplayer.data.dao.DownloadMetadataDao
import com.viel.aplayer.data.db.AudiobookSchema
import com.viel.aplayer.data.entity.BookFileEntity
import com.viel.aplayer.data.entity.DownloadMetadataEntity
import com.viel.aplayer.data.entity.DownloadStatus
import com.viel.aplayer.data.entity.LibraryRootEntity
import com.viel.aplayer.media.PlaybackRootLookup
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Runs with Robolectric because DownloadRequest construction uses android.net.Uri.
 * The test still fakes download boundaries, but Android URI parsing must match the runtime environment.
 */
@RunWith(RobolectricTestRunner::class)
class DefaultDownloadControllerTest {
    @Test
    fun `download book should submit missing remote audio files in playback order`() = runBlocking {
        val metadataDao = InMemoryDownloadMetadataDao()
        val runtimeGateway = RecordingDownloadRuntimeGateway()
        val notifier = RecordingManualDownloadNotificationGateway()
        val controller = controllerFor(
            files = listOf(
                testFile("webdav-2", "webdav-root", index = 2),
                testFile("local-1", "saf-root", index = 1),
                testFile("manifest-1", "webdav-root", index = 0, role = AudiobookSchema.FileRole.SOURCE_MANIFEST),
                testFile("abs-1", "abs-root", index = 1),
                testFile("webdav-3", "webdav-root", index = 3)
            ),
            metadataDao = metadataDao,
            runtimeGateway = runtimeGateway,
            existingFileIds = setOf("webdav-2"),
            notifier = notifier
        )

        controller.downloadBook(BOOK_ID)

        assertEquals(listOf("abs-1", "webdav-3"), runtimeGateway.addedRequestIds)
        val metadata = metadataDao.rows[BOOK_ID]
        assertEquals(DownloadStatus.QUEUED, metadata?.status)
        assertEquals(3, metadata?.totalFiles)
        assertEquals(600L, metadata?.totalBytes)
        assertEquals(1_000L, metadata?.createdAt)
        assertEquals(1_000L, metadata?.updatedAt)
        assertEquals(listOf(metadata), notifier.published)
    }

    @Test
    fun `download book should clear metadata when no remote audio files exist`() = runBlocking {
        val metadataDao = InMemoryDownloadMetadataDao(
            DownloadMetadataEntity(
                bookId = BOOK_ID,
                status = DownloadStatus.QUEUED,
                totalFiles = 1,
                completedFiles = 0,
                totalBytes = 100L,
                downloadedBytes = 0L,
                createdAt = 1L,
                updatedAt = 1L
            )
        )
        val runtimeGateway = RecordingDownloadRuntimeGateway()
        val notifier = RecordingManualDownloadNotificationGateway()
        val controller = controllerFor(
            files = listOf(testFile("local-1", "saf-root", index = 1)),
            metadataDao = metadataDao,
            runtimeGateway = runtimeGateway,
            notifier = notifier
        )

        controller.downloadBook(BOOK_ID)

        assertNull(metadataDao.rows[BOOK_ID])
        assertEquals(emptyList<String>(), runtimeGateway.addedRequestIds)
        assertEquals(listOf(BOOK_ID), notifier.cancelled)
    }

    @Test
    fun `delete download should remove remote file records and clear metadata`() = runBlocking {
        val metadataDao = InMemoryDownloadMetadataDao(
            DownloadMetadataEntity(
                bookId = BOOK_ID,
                status = DownloadStatus.COMPLETED,
                totalFiles = 2,
                completedFiles = 2,
                totalBytes = 300L,
                downloadedBytes = 300L,
                createdAt = 1L,
                updatedAt = 2L
            )
        )
        val runtimeGateway = RecordingDownloadRuntimeGateway()
        val notifier = RecordingManualDownloadNotificationGateway()
        val controller = controllerFor(
            files = listOf(
                testFile("webdav-1", "webdav-root", index = 1),
                testFile("local-1", "saf-root", index = 2),
                testFile("abs-1", "abs-root", index = 3)
            ),
            metadataDao = metadataDao,
            runtimeGateway = runtimeGateway,
            notifier = notifier
        )

        controller.deleteDownload(BOOK_ID)

        assertEquals(listOf("webdav-1", "abs-1"), runtimeGateway.removedFileIds)
        assertNull(metadataDao.rows[BOOK_ID])
        assertEquals(listOf(BOOK_ID), notifier.cancelled)
    }

    @Test
    fun `pause and resume download should set stop reason only for remote audio files`() = runBlocking {
        val metadataDao = InMemoryDownloadMetadataDao(
            DownloadMetadataEntity(
                bookId = BOOK_ID,
                status = DownloadStatus.DOWNLOADING,
                totalFiles = 2,
                completedFiles = 0,
                totalBytes = 300L,
                downloadedBytes = 100L,
                createdAt = 1L,
                updatedAt = 2L
            )
        )
        val runtimeGateway = RecordingDownloadRuntimeGateway()
        val notifier = RecordingManualDownloadNotificationGateway()
        val controller = controllerFor(
            files = listOf(
                testFile("webdav-1", "webdav-root", index = 1),
                testFile("local-1", "saf-root", index = 2),
                testFile("manifest-1", "webdav-root", index = 0, role = AudiobookSchema.FileRole.SOURCE_MANIFEST),
                testFile("abs-1", "abs-root", index = 3)
            ),
            metadataDao = metadataDao,
            runtimeGateway = runtimeGateway,
            notifier = notifier
        )

        controller.pauseDownload(BOOK_ID)
        controller.resumeDownload(BOOK_ID)

        assertEquals(
            listOf("webdav-1" to 1, "abs-1" to 1, "webdav-1" to 0, "abs-1" to 0),
            runtimeGateway.stopReasons
        )
        assertEquals(DownloadStatus.QUEUED, metadataDao.rows[BOOK_ID]?.status)
        assertEquals(listOf(DownloadStatus.PAUSED, DownloadStatus.QUEUED), notifier.published.map { metadata -> metadata.status })
    }

    private fun controllerFor(
        files: List<BookFileEntity>,
        metadataDao: InMemoryDownloadMetadataDao,
        runtimeGateway: RecordingDownloadRuntimeGateway,
        existingFileIds: Set<String> = emptySet(),
        notifier: RecordingManualDownloadNotificationGateway = RecordingManualDownloadNotificationGateway()
    ): DefaultDownloadController =
        DefaultDownloadController(
            downloadableBookFileSelector = DownloadableBookFileSelector(
                downloadBookFileReader = StaticDownloadBookFileReader(files),
                playbackRootLookup = StaticPlaybackRootLookup(
                    mapOf(
                        "saf-root" to testRoot("saf-root", AudiobookSchema.LibrarySourceType.SAF),
                        "webdav-root" to testRoot("webdav-root", AudiobookSchema.LibrarySourceType.WEBDAV),
                        "abs-root" to testRoot("abs-root", AudiobookSchema.LibrarySourceType.ABS)
                    )
                )
            ),
            downloadMetadataDao = metadataDao,
            downloadRuntimeGateway = runtimeGateway,
            downloadRequestRepairer = DownloadRequestRepairer(StaticDownloadIndexSnapshotReader(existingFileIds)),
            manualDownloadNotificationGateway = notifier,
            nowProvider = { 1_000L }
        )

    private class StaticDownloadBookFileReader(
        private val files: List<BookFileEntity>
    ) : DownloadBookFileReader {
        override suspend fun getDownloadFilesForBook(bookId: String): List<BookFileEntity> = files
    }

    private class StaticPlaybackRootLookup(
        private val roots: Map<String, LibraryRootEntity>
    ) : PlaybackRootLookup {
        override suspend fun getRootById(rootId: String): LibraryRootEntity? = roots[rootId]
    }

    private class StaticDownloadIndexSnapshotReader(
        private val existingFileIds: Set<String>
    ) : DownloadIndexSnapshotReader {
        override suspend fun getSnapshot(fileId: String): FileDownloadSnapshot? =
            if (fileId in existingFileIds) {
                FileDownloadSnapshot(fileId, FileDownloadState.QUEUED, 0L, 100L)
            } else {
                null
            }
    }

    private class RecordingDownloadRuntimeGateway : DownloadRuntimeGateway {
        val addedRequestIds = mutableListOf<String>()
        val removedFileIds = mutableListOf<String>()
        val stopReasons = mutableListOf<Pair<String, Int>>()

        override fun addDownload(request: DownloadRequest) {
            addedRequestIds += request.id
        }

        override fun removeDownload(fileId: String) {
            removedFileIds += fileId
        }

        override fun pauseDownloads() = Unit
        override fun resumeDownloads() = Unit
        override fun setStopReason(fileId: String, reason: Int) {
            stopReasons += fileId to reason
        }
        override fun updateRequirements(wifiOnly: Boolean) = Unit
    }

    private class InMemoryDownloadMetadataDao(
        initial: DownloadMetadataEntity? = null
    ) : DownloadMetadataDao {
        val rows = mutableMapOf<String, DownloadMetadataEntity>()

        init {
            initial?.let { metadata -> rows[metadata.bookId] = metadata }
        }

        override suspend fun insertOrReplace(metadata: DownloadMetadataEntity) {
            rows[metadata.bookId] = metadata
        }

        override suspend fun getMetadata(bookId: String): DownloadMetadataEntity? = rows[bookId]
        override fun observeMetadata(bookId: String): Flow<DownloadMetadataEntity?> = flowOf(rows[bookId])
        override fun observeAllMetadata(): Flow<List<DownloadMetadataEntity>> = flowOf(rows.values.toList())
        override suspend fun getAllMetadata(): List<DownloadMetadataEntity> = rows.values.toList()
        override suspend fun getRecoverableTasks(): List<DownloadMetadataEntity> = rows.values.toList()
        override suspend fun hasRecoverableTasks(): Boolean = rows.isNotEmpty()
        override suspend fun getCompletedTaskCount(): Int = rows.values.count { metadata -> metadata.status == DownloadStatus.COMPLETED }
        override suspend fun deleteByBookId(bookId: String) {
            rows.remove(bookId)
        }

        override suspend fun delete(metadata: DownloadMetadataEntity) {
            rows.remove(metadata.bookId)
        }
    }

    private class RecordingManualDownloadNotificationGateway : ManualDownloadNotificationGateway {
        val published = mutableListOf<DownloadMetadataEntity>()
        val cancelled = mutableListOf<String>()

        override suspend fun publish(metadata: DownloadMetadataEntity) {
            published += metadata
        }

        override suspend fun cancel(bookId: String) {
            cancelled += bookId
        }
    }

    private companion object {
        private const val BOOK_ID = "book-1"

        private fun testRoot(id: String, sourceType: AudiobookSchema.LibrarySourceType): LibraryRootEntity =
            LibraryRootEntity(
                id = id,
                sourceType = sourceType,
                sourceUri = "$id://root",
                displayName = id
            )

        private fun testFile(
            id: String,
            rootId: String,
            index: Int,
            role: AudiobookSchema.FileRole = AudiobookSchema.FileRole.AUDIO
        ): BookFileEntity =
            BookFileEntity(
                id = id,
                bookId = BOOK_ID,
                fileRole = role,
                rootId = rootId,
                index = index,
                sourcePath = "$id.mp3",
                sourceIdentity = id,
                displayName = "$id.mp3",
                durationMs = 1_000L,
                fileSize = 100L * index,
                lastModified = 0L
            )
    }
}
