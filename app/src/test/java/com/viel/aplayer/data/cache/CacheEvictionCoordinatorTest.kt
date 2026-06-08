package com.viel.aplayer.data.cache

import com.viel.aplayer.data.dao.BookCoverCachePaths
import com.viel.aplayer.data.dao.BookDao
import com.viel.aplayer.data.dao.DirectoryCacheDao
import com.viel.aplayer.data.dao.DirectoryChildCacheDao
import com.viel.aplayer.data.db.AudiobookSchema
import com.viel.aplayer.data.entity.DirectoryCacheEntity
import com.viel.aplayer.data.entity.DirectoryChildCacheEntity
import com.viel.aplayer.data.entity.LibraryRootEntity
import com.viel.aplayer.library.vfs.cache.VfsRangeCache
import com.viel.aplayer.library.vfs.cache.VfsRangeCacheKey
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
    fun `root eviction should delete owned cover files directory rows and range blocks`() = runBlocking {
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
        val rangeCache = VfsRangeCache(cacheDir = File(appCacheDir, "vfs_range_cache"))
        val rootHash = VfsRangeCacheKey.hashIdentifier("root-1")
        val otherRootHash = VfsRangeCacheKey.hashIdentifier("root-2")
        val ownedRangeKey = sampleRangeKey(rootHash, "book-1")
        val otherRangeKey = sampleRangeKey(otherRootHash, "book-2")
        rangeCache.write(ownedRangeKey, byteArrayOf(1))
        rangeCache.write(otherRangeKey, byteArrayOf(2))
        val coordinator = CacheEvictionCoordinator(
            appCacheDir = appCacheDir,
            bookDao = bookDao,
            directoryCacheDao = directoryCacheDao,
            directoryChildCacheDao = directoryChildCacheDao,
            vfsRangeCache = rangeCache
        )

        val summary = coordinator.evictBeforeRootDelete(sampleRoot("root-1"))

        // Root Cache Eviction Contract (Deletes only cache artifacts owned by the deleted root)
        // Cover files must stay inside cacheDir/covers, directory tables must be explicitly cleared, and range blocks must be scoped by hashed root id.
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
        assertEquals(null, rangeCache.read(ownedRangeKey))
        assertEquals(listOf(2), rangeCache.read(otherRangeKey)?.map { byte -> byte.toInt() })
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

        // Optional Range Cache Guard (Keeps P3 cleanup valid before P5 cache injection)
        // A missing VfsRangeCache must not block cover or directory cleanup and should report zero range deletions.
        assertEquals(0, summary.coverFilesDeleted)
        assertEquals(0, summary.rangeFilesDeleted)
    }

    @Test
    fun `root edit eviction should clear child directories and range blocks`() = runBlocking {
        val appCacheDir = createTempDirectory("cache-edit-eviction").toFile()
        val directoryCacheDao = FakeDirectoryCacheDao()
        val directoryChildCacheDao = FakeDirectoryChildCacheDao()
        val rangeCache = VfsRangeCache(cacheDir = File(appCacheDir, "vfs_range_cache"))
        val ownedRangeKey = sampleRangeKey(VfsRangeCacheKey.hashIdentifier("root-1"), "book-1")
        rangeCache.write(ownedRangeKey, byteArrayOf(7))
        val coordinator = CacheEvictionCoordinator(
            appCacheDir = appCacheDir,
            bookDao = fakeBookDao(emptyList()),
            directoryCacheDao = directoryCacheDao,
            directoryChildCacheDao = directoryChildCacheDao,
            vfsRangeCache = rangeCache
        )

        val summary = coordinator.evictRootCaches("root-1")

        // Root Edit Cache Contract (Uses the same root-scoped eviction path as deletion without requiring a LibraryRootEntity)
        // Editing a provider URL or SAF URI must clear child listings and range-cache blocks so the next scan reads the new coordinates.
        assertEquals("root-1", summary.rootId)
        assertEquals("root-1", directoryCacheDao.deletedRootId)
        assertEquals("root-1", directoryChildCacheDao.deletedRootId)
        assertEquals(1, summary.rangeFilesDeleted)
        assertEquals(null, rangeCache.read(ownedRangeKey))
    }

    private fun fakeBookDao(paths: List<BookCoverCachePaths>): BookDao =
        Proxy.newProxyInstance(
            BookDao::class.java.classLoader,
            arrayOf(BookDao::class.java)
        ) { _, method, _ ->
            when (method.name) {
                "getCoverCachePathsByRootId" -> paths
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

    private fun sampleRangeKey(rootHash: String, source: String) =
        VfsRangeCacheKey(
            rootIdHash = rootHash,
            sourcePathHash = VfsRangeCacheKey.hashIdentifier(source),
            version = VfsRangeCacheKey.hashIdentifier("etag-$source"),
            offset = 0L,
            length = 1
        )

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
