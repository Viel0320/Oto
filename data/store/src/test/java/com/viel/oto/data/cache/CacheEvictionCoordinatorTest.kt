package com.viel.oto.data.cache

import com.viel.oto.data.dao.BookCoverCachePaths
import com.viel.oto.data.dao.BookDao
import com.viel.oto.data.dao.DirectoryCacheDao
import com.viel.oto.data.dao.DirectoryChildCacheDao
import com.viel.oto.data.db.AudiobookSchema
import com.viel.oto.data.entity.DirectoryCacheEntity
import com.viel.oto.data.entity.DirectoryChildCacheEntity
import com.viel.oto.data.entity.LibraryRootEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.lang.reflect.Proxy
import kotlin.io.path.createTempDirectory

class CacheEvictionCoordinatorTest {

    @Test
    fun `root eviction should delete owned cover files directory rows and source caches`() = runBlocking {
        val appCacheDir = createTempDirectory("cache-eviction").toFile()
        val coversDir = File(appCacheDir, "covers").apply { mkdirs() }
        val cover = File(coversDir, "cover.jpg").apply { writeText("cover") }
        val thumbnail = File(coversDir, "thumb.jpg").apply { writeText("thumb") }
        val outside = File(appCacheDir.parentFile, "outside-cover.jpg").apply { writeText("outside") }
        val bookDao = fakeBookDao(
            listOf(
                BookCoverCachePaths(coverPath = cover.absolutePath, thumbnailPath = thumbnail.absolutePath),
                BookCoverCachePaths(coverPath = outside.absolutePath, thumbnailPath = null)
            )
        )
        val directoryCacheDao = FakeDirectoryCacheDao()
        val directoryChildCacheDao = FakeDirectoryChildCacheDao()
        val sourceCacheEvictor = RecordingRootSourceCacheEvictor(rangeFilesDeleted = 1)
        val coordinator = CacheEvictionCoordinator(
            appCacheDir = appCacheDir,
            bookDao = bookDao,
            directoryCacheDao = directoryCacheDao,
            directoryChildCacheDao = directoryChildCacheDao,
            rootSourceCacheEvictor = sourceCacheEvictor
        )

        val summary = coordinator.evictBeforeRootDelete(sampleRoot("root-1"))

        assertEquals("root-1", summary.rootId)
        assertEquals(2, summary.coverFilesDeleted)
        assertEquals(true, summary.directoryRowsDeleted)
        assertEquals(true, summary.directoryChildRowsDeleted)
        assertEquals(1, summary.rangeFilesDeleted)
        assertFalse(cover.exists())
        assertFalse(thumbnail.exists())
        assertTrue(outside.exists())
        assertEquals("root-1", directoryCacheDao.deletedRootId)
        assertEquals("root-1", directoryChildCacheDao.deletedRootId)
        assertEquals(listOf("root-1"), sourceCacheEvictor.evictedRootIds)
    }

    @Test
    fun `root eviction without range cache should keep range count zero`() = runBlocking {
        val appCacheDir = createTempDirectory("cache-eviction-no-range").toFile()
        val coordinator = CacheEvictionCoordinator(
            appCacheDir = appCacheDir,
            bookDao = fakeBookDao(emptyList()),
            directoryCacheDao = FakeDirectoryCacheDao(),
            directoryChildCacheDao = FakeDirectoryChildCacheDao()
        )

        val summary = coordinator.evictBeforeRootDelete(sampleRoot("root-1"))

        assertEquals(0, summary.coverFilesDeleted)
        assertEquals(0, summary.rangeFilesDeleted)
    }

    @Test
    fun `book cover cleanup should delete only owned book artwork files`() = runBlocking {
        val appCacheDir = createTempDirectory("book-cover-eviction").toFile()
        val coversDir = File(appCacheDir, "covers").apply { mkdirs() }
        val cover = File(coversDir, "book-cover.jpg").apply { writeText("cover") }
        val thumbnail = File(coversDir, "book-thumb.jpg").apply { writeText("thumb") }
        val outside = File(appCacheDir.parentFile, "outside-book-cover.jpg").apply { writeText("outside") }
        val coordinator = CacheEvictionCoordinator(
            appCacheDir = appCacheDir,
            bookDao = fakeBookDao(
                rootPaths = emptyList(),
                bookPath = BookCoverCachePaths(coverPath = cover.absolutePath, thumbnailPath = thumbnail.absolutePath)
            ),
            directoryCacheDao = FakeDirectoryCacheDao(),
            directoryChildCacheDao = FakeDirectoryChildCacheDao()
        )

        coordinator.clearBookCoverCache("book-1")

        assertFalse(cover.exists())
        assertFalse(thumbnail.exists())
        assertTrue(outside.exists())
    }

    @Test
    fun `root edit eviction should clear child directories and source caches`() = runBlocking {
        val appCacheDir = createTempDirectory("cache-edit-eviction").toFile()
        val directoryCacheDao = FakeDirectoryCacheDao()
        val directoryChildCacheDao = FakeDirectoryChildCacheDao()
        val sourceCacheEvictor = RecordingRootSourceCacheEvictor(rangeFilesDeleted = 1)
        val coordinator = CacheEvictionCoordinator(
            appCacheDir = appCacheDir,
            bookDao = fakeBookDao(emptyList()),
            directoryCacheDao = directoryCacheDao,
            directoryChildCacheDao = directoryChildCacheDao,
            rootSourceCacheEvictor = sourceCacheEvictor
        )

        val summary = coordinator.evictRootCaches("root-1")

        assertEquals("root-1", summary.rootId)
        assertEquals("root-1", directoryCacheDao.deletedRootId)
        assertEquals("root-1", directoryChildCacheDao.deletedRootId)
        assertEquals(1, summary.rangeFilesDeleted)
        assertEquals(listOf("root-1"), sourceCacheEvictor.evictedRootIds)
    }

    private fun fakeBookDao(
        rootPaths: List<BookCoverCachePaths>,
        bookPath: BookCoverCachePaths? = null
    ): BookDao =
        Proxy.newProxyInstance(
            BookDao::class.java.classLoader,
            arrayOf(BookDao::class.java)
        ) { _, method, _ ->
            when (method.name) {
                "getCoverCachePathsByRootId" -> rootPaths
                "getCoverCachePathsByBookId" -> bookPath
                else -> unsupported(method.name)
            }
        } as BookDao

    private class FakeDirectoryCacheDao : DirectoryCacheDao {
        var deletedRootId: String? = null

        override suspend fun getBySourcePath(rootId: String, sourcePath: String): DirectoryCacheEntity? =
            null

        override suspend fun insert(cache: DirectoryCacheEntity) = Unit

        override suspend fun deleteBySourcePath(rootId: String, sourcePath: String) = Unit

        override suspend fun deleteByRootId(rootId: String) {
            deletedRootId = rootId
        }
    }

    private class FakeDirectoryChildCacheDao : DirectoryChildCacheDao() {
        var deletedRootId: String? = null

        override suspend fun getChildren(
            rootId: String,
            parentSourcePath: String,
            minCachedAt: Long
        ): List<DirectoryChildCacheEntity> =
            emptyList()

        override suspend fun deleteChildren(rootId: String, parentSourcePath: String) = Unit

        override suspend fun insertChildren(children: List<DirectoryChildCacheEntity>) = Unit

        override suspend fun deleteByRootId(rootId: String) {
            deletedRootId = rootId
        }
    }

    private class RecordingRootSourceCacheEvictor(
        private val rangeFilesDeleted: Int
    ) : RootSourceCacheEvictor {
        val evictedRootIds = mutableListOf<String>()

        override suspend fun evictRoot(rootId: String): Int {
            evictedRootIds += rootId
            return rangeFilesDeleted
        }
    }

    private fun sampleRoot(rootId: String) =
        LibraryRootEntity(
            id = rootId,
            sourceType = AudiobookSchema.LibrarySourceType.WEBDAV,
            sourceUri = "https://example.com/dav",
            displayName = "WebDAV"
        )

    private fun unsupported(methodName: String): Nothing =
        throw UnsupportedOperationException("Unexpected DAO method in CacheEvictionCoordinatorTest: $methodName")
}
