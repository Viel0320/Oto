package com.viel.oto.application.download

import com.viel.oto.data.dao.DownloadMetadataDao
import com.viel.oto.data.db.AudiobookSchema
import com.viel.oto.data.entity.BookFileEntity
import com.viel.oto.data.entity.DownloadMetadataEntity
import com.viel.oto.data.entity.DownloadStatus
import com.viel.oto.data.entity.LibraryRootEntity
import com.viel.oto.media.PlaybackRootLookup
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DownloadSyncServiceTest {
    @Test
    fun `reconcile book should persist aggregate from file snapshots`() = runBlocking {
        val dao = FakeDownloadMetadataDao()
        val notifier = RecordingManualDownloadNotificationGateway()
        dao.insertOrReplace(existingMetadata())
        val service = DownloadSyncService(
            downloadableBookFileSelector = selectorFor(
                listOf(testFile("file-1", 100L), testFile("file-2", 200L))
            ),
            downloadMetadataDao = dao,
            downloadIndexSnapshotReader = StaticDownloadIndexSnapshotReader(
                mapOf(
                    "file-1" to FileDownloadSnapshot("file-1", FileDownloadState.COMPLETED, 100L, 100L),
                    "file-2" to FileDownloadSnapshot("file-2", FileDownloadState.DOWNLOADING, 50L, 200L)
                )
            ),
            manualDownloadNotificationGateway = notifier,
            nowProvider = { 50L }
        )

        service.reconcileBook(BOOK_ID)

        val metadata = dao.getMetadata(BOOK_ID)
        requireNotNull(metadata)
        assertEquals(DownloadStatus.DOWNLOADING, metadata.status)
        assertEquals(1, metadata.completedFiles)
        assertEquals(2, metadata.totalFiles)
        assertEquals(150L, metadata.downloadedBytes)
        assertEquals(300L, metadata.totalBytes)
        assertEquals(listOf(metadata), notifier.published)
    }

    @Test
    fun `reconcile book should not recreate deleted metadata when requests are missing`() = runBlocking {
        val dao = FakeDownloadMetadataDao()
        val notifier = RecordingManualDownloadNotificationGateway()
        val service = DownloadSyncService(
            downloadableBookFileSelector = selectorFor(
                listOf(testFile("file-1", 100L))
            ),
            downloadMetadataDao = dao,
            downloadIndexSnapshotReader = StaticDownloadIndexSnapshotReader(emptyMap()),
            manualDownloadNotificationGateway = notifier,
            nowProvider = { 55L }
        )

        service.reconcileBook(BOOK_ID)

        assertNull(dao.getMetadata(BOOK_ID))
        assertEquals(listOf(BOOK_ID), notifier.cancelled)
    }

    @Test
    fun `reconcile book without downloadable files should delete stale metadata`() = runBlocking {
        val dao = FakeDownloadMetadataDao()
        val notifier = RecordingManualDownloadNotificationGateway()
        dao.insertOrReplace(existingMetadata())
        val service = DownloadSyncService(
            downloadableBookFileSelector = selectorFor(emptyList()),
            downloadMetadataDao = dao,
            downloadIndexSnapshotReader = StaticDownloadIndexSnapshotReader(emptyMap()),
            manualDownloadNotificationGateway = notifier,
            nowProvider = { 60L }
        )

        service.reconcileBook(BOOK_ID)

        assertNull(dao.getMetadata(BOOK_ID))
        assertEquals(listOf(BOOK_ID), notifier.cancelled)
    }

    @Test
    fun `reconcile book should ignore local audio rows when publishing aggregate`() = runBlocking {
        val dao = FakeDownloadMetadataDao()
        val notifier = RecordingManualDownloadNotificationGateway()
        dao.insertOrReplace(existingMetadata())
        val service = DownloadSyncService(
            downloadableBookFileSelector = selectorFor(
                listOf(
                    testFile("local-1", 500L, rootId = "saf-root"),
                    testFile("remote-1", 200L, rootId = "webdav-root")
                ),
                roots = mapOf(
                    "saf-root" to AudiobookSchema.LibrarySourceType.SAF,
                    "webdav-root" to AudiobookSchema.LibrarySourceType.WEBDAV
                )
            ),
            downloadMetadataDao = dao,
            downloadIndexSnapshotReader = StaticDownloadIndexSnapshotReader(
                mapOf("remote-1" to FileDownloadSnapshot("remote-1", FileDownloadState.COMPLETED, 200L, 200L))
            ),
            manualDownloadNotificationGateway = notifier,
            nowProvider = { 70L }
        )

        service.reconcileBook(BOOK_ID)

        val metadata = dao.getMetadata(BOOK_ID)
        requireNotNull(metadata)
        assertEquals(DownloadStatus.COMPLETED, metadata.status)
        assertEquals(1, metadata.completedFiles)
        assertEquals(1, metadata.totalFiles)
        assertEquals(200L, metadata.downloadedBytes)
        assertEquals(200L, metadata.totalBytes)
        assertEquals(listOf(metadata), notifier.published)
    }

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
        private val snapshots: Map<String, FileDownloadSnapshot>
    ) : DownloadIndexSnapshotReader {
        override suspend fun getSnapshot(fileId: String): FileDownloadSnapshot? = snapshots[fileId]
    }

    private class FakeDownloadMetadataDao : DownloadMetadataDao {
        private val rows = linkedMapOf<String, DownloadMetadataEntity>()

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

    private fun existingMetadata(): DownloadMetadataEntity =
        DownloadMetadataEntity(
            bookId = BOOK_ID,
            status = DownloadStatus.DOWNLOADING,
            totalFiles = 1,
            completedFiles = 0,
            totalBytes = 100L,
            downloadedBytes = 10L,
            createdAt = 1L,
            updatedAt = 2L
        )

    private companion object {
        const val BOOK_ID = "book-1"

        fun selectorFor(
            files: List<BookFileEntity>,
            roots: Map<String, AudiobookSchema.LibrarySourceType> = mapOf(
                "root-1" to AudiobookSchema.LibrarySourceType.WEBDAV
            )
        ): DownloadableBookFileSelector =
            DownloadableBookFileSelector(
                downloadBookFileReader = StaticDownloadBookFileReader(files),
                playbackRootLookup = StaticPlaybackRootLookup(
                    roots.map { (id, sourceType) ->
                        id to LibraryRootEntity(
                            id = id,
                            sourceType = sourceType,
                            sourceUri = "$id://root",
                            displayName = id
                        )
                    }.toMap()
                )
            )

        fun testFile(
            id: String,
            size: Long,
            rootId: String = "root-1"
        ): BookFileEntity =
            BookFileEntity(
                id = id,
                bookId = BOOK_ID,
                rootId = rootId,
                index = id.takeLast(1).toInt(),
                sourcePath = "$id.mp3",
                sourceIdentity = id,
                displayName = "$id.mp3",
                durationMs = 1_000L,
                fileSize = size,
                lastModified = 0L
            )
    }
}
