package com.viel.aplayer.application.download

import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheSpan
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.datasource.cache.ContentMetadataMutations
import androidx.media3.datasource.cache.DefaultContentMetadata
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
import org.junit.Test
import java.io.File
import java.util.NavigableSet
import java.util.TreeSet

class ManualDownloadOrphanCleanerTest {
    @Test
    fun `cleaner should remove manual cache keys without durable metadata ownership`() = runBlocking {
        val manualCache = MutableKeyCache(
            linkedMapOf(
                "keep-file" to 100L,
                "orphan-file" to 50L
            )
        )
        val cleaner = ManualDownloadOrphanCleaner(
            downloadCacheAccess = StaticDownloadCacheAccess(manualCache),
            downloadMetadataDao = StaticDownloadMetadataDao(listOf(metadata(BOOK_ID))),
            downloadableBookFileSelector = DownloadableBookFileSelector(
                downloadBookFileReader = StaticDownloadBookFileReader(listOf(testFile("keep-file"))),
                playbackRootLookup = StaticPlaybackRootLookup(testRoot())
            )
        )

        val result = cleaner.cleanOrphans()

        // Manual Cache Ownership Cleanup (Only keys not selected by durable download metadata are removed)
        // The kept file remains because its parent book still owns a manual cache row, while stale keys are deleted by resource key.
        assertEquals(setOf("keep-file"), manualCache.keys)
        assertEquals(2, result.scannedKeys)
        assertEquals(1, result.removedKeys)
        assertEquals(150L, result.bytesBefore)
        assertEquals(100L, result.bytesAfter)
    }

    private class StaticDownloadCacheAccess(
        override val manualCache: Cache
    ) : DownloadCacheAccess

    private class StaticDownloadMetadataDao(
        private val metadata: List<DownloadMetadataEntity>
    ) : DownloadMetadataDao {
        override suspend fun insertOrReplace(metadata: DownloadMetadataEntity) = Unit
        override suspend fun getMetadata(bookId: String): DownloadMetadataEntity? = metadata.firstOrNull { it.bookId == bookId }
        override fun observeMetadata(bookId: String): Flow<DownloadMetadataEntity?> = flowOf(null)
        override fun observeAllMetadata(): Flow<List<DownloadMetadataEntity>> = flowOf(metadata)
        override suspend fun getAllMetadata(): List<DownloadMetadataEntity> = metadata
        override suspend fun getRecoverableTasks(): List<DownloadMetadataEntity> = metadata
        override suspend fun hasRecoverableTasks(): Boolean = metadata.isNotEmpty()
        override suspend fun getCompletedTaskCount(): Int = metadata.count { it.status == DownloadStatus.COMPLETED }
        override suspend fun deleteByBookId(bookId: String) = Unit
        override suspend fun delete(metadata: DownloadMetadataEntity) = Unit
    }

    private class StaticDownloadBookFileReader(
        private val files: List<BookFileEntity>
    ) : DownloadBookFileReader {
        override suspend fun getDownloadFilesForBook(bookId: String): List<BookFileEntity> = files
    }

    private class StaticPlaybackRootLookup(
        private val root: LibraryRootEntity
    ) : PlaybackRootLookup {
        override suspend fun getRootById(rootId: String): LibraryRootEntity? = root
    }

    private class MutableKeyCache(
        private val keyBytes: MutableMap<String, Long>
    ) : Cache {
        override fun getUid(): Long = 1L
        override fun release() = Unit
        override fun addListener(key: String, listener: Cache.Listener): NavigableSet<CacheSpan> = TreeSet()
        override fun removeListener(key: String, listener: Cache.Listener) = Unit
        override fun getCachedSpans(key: String): NavigableSet<CacheSpan> = TreeSet()
        override fun getKeys(): Set<String> = keyBytes.keys.toSet()
        override fun getCacheSpace(): Long = keyBytes.values.sum()
        override fun startReadWrite(key: String, position: Long, length: Long): CacheSpan = unexpected()
        override fun startReadWriteNonBlocking(key: String, position: Long, length: Long): CacheSpan? = unexpected()
        override fun startFile(key: String, position: Long, length: Long): File = unexpected()
        override fun commitFile(file: File, length: Long) = unexpected()
        override fun releaseHoleSpan(holeSpan: CacheSpan) = unexpected()
        override fun removeResource(key: String) {
            keyBytes.remove(key)
        }

        override fun removeSpan(span: CacheSpan) = unexpected()
        override fun isCached(key: String, position: Long, length: Long): Boolean = key in keyBytes
        override fun getCachedLength(key: String, position: Long, length: Long): Long = keyBytes[key] ?: -length
        override fun getCachedBytes(key: String, position: Long, length: Long): Long = keyBytes[key] ?: 0L
        override fun applyContentMetadataMutations(key: String, mutations: ContentMetadataMutations) = Unit
        override fun getContentMetadata(key: String): ContentMetadata = DefaultContentMetadata.EMPTY

        private fun unexpected(): Nothing {
            error("Unexpected Cache method in ManualDownloadOrphanCleanerTest")
        }
    }

    private companion object {
        private const val BOOK_ID = "book-1"

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

        private fun testRoot(): LibraryRootEntity =
            LibraryRootEntity(
                id = "remote-root",
                sourceType = AudiobookSchema.LibrarySourceType.WEBDAV,
                sourceUri = "https://example.invalid",
                displayName = "Remote"
            )

        private fun testFile(id: String): BookFileEntity =
            BookFileEntity(
                id = id,
                bookId = BOOK_ID,
                rootId = "remote-root",
                index = 1,
                sourcePath = "$id.mp3",
                sourceIdentity = id,
                displayName = "$id.mp3",
                durationMs = 1_000L,
                fileSize = 100L,
                lastModified = 0L
            )
    }
}
