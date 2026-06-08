package com.viel.aplayer.library.vfs.cache

import com.viel.aplayer.data.dao.DirectoryChildCacheDao
import com.viel.aplayer.data.db.AudiobookSchema
import com.viel.aplayer.data.entity.DirectoryChildCacheEntity
import com.viel.aplayer.data.entity.LibraryRootEntity
import com.viel.aplayer.library.vfs.VfsNode
import com.viel.aplayer.library.vfs.VfsPath
import com.viel.aplayer.library.vfs.sourceProvider.SourceFileMetadata
import com.viel.aplayer.library.vfs.sourceProvider.SourceNode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RoomDirectoryListingCacheTest {

    @Test
    fun `webdav directory should read and write cached children`() = runBlocking {
        val dao = FakeDirectoryChildCacheDao()
        val cache = RoomDirectoryListingCache(
            directoryChildCacheDao = dao,
            currentTimeMillis = { 5000L },
            elapsedRealtimeMillis = { 1000L }
        )
        val directory = sampleDirectory(sourceType = AudiobookSchema.LibrarySourceType.WEBDAV)
        val child = sampleChild(sourcePath = "folder/book.m4b", displayName = "book.m4b")

        cache.replaceChildren(directory, listOf(child))
        val restored = cache.getChildren(directory)

        // WebDAV Cache Persistence (Confirms scanner listing snapshots round trip through the cache)
        // A WebDAV directory must persist direct child metadata under rootId plus parentSourcePath and then return that metadata on later reads.
        assertEquals(listOf(child), restored)
        assertEquals("root-1", dao.replacedRootId)
        assertEquals("folder", dao.replacedParentSourcePath)
        assertEquals(5000L, dao.rows.single().cachedAt)
    }

    @Test
    fun `webdav cache miss should return null for empty child rows`() = runBlocking {
        val cache = RoomDirectoryListingCache(
            directoryChildCacheDao = FakeDirectoryChildCacheDao(),
            elapsedRealtimeMillis = { 1000L }
        )
        val directory = sampleDirectory(sourceType = AudiobookSchema.LibrarySourceType.WEBDAV)

        val restored = cache.getChildren(directory)

        // Cache Miss Signal (Preserves provider fallback when no child snapshot exists)
        // Null signals the VFS to call the provider and refresh Room instead of treating an absent snapshot as a completed empty directory.
        assertNull(restored)
    }

    @Test
    fun `webdav cache read should pass freshness lower bound to dao`() = runBlocking {
        val dao = FakeDirectoryChildCacheDao()
        val cache = RoomDirectoryListingCache(
            directoryChildCacheDao = dao,
            currentTimeMillis = { 10_000L },
            maxCacheAgeMillis = 2_500L,
            elapsedRealtimeMillis = { 1000L }
        )

        cache.getChildren(sampleDirectory(sourceType = AudiobookSchema.LibrarySourceType.WEBDAV))

        // Freshness Query Boundary (Keeps TTL enforcement inside the Room-backed cache adapter)
        // The DAO must receive a cachedAt lower bound so expired rows return as a cache miss instead of stale child metadata.
        assertEquals(7_500L, dao.lastMinCachedAt)
    }

    @Test
    fun `saf and abs directories should not read or write directory child cache`() = runBlocking {
        val safDao = FakeDirectoryChildCacheDao()
        val safCache = RoomDirectoryListingCache(safDao)
        val absDao = FakeDirectoryChildCacheDao()
        val absCache = RoomDirectoryListingCache(absDao)
        val child = sampleChild(sourcePath = "folder/book.m4b", displayName = "book.m4b")

        safCache.replaceChildren(sampleDirectory(sourceType = AudiobookSchema.LibrarySourceType.SAF), listOf(child))
        absCache.replaceChildren(sampleDirectory(sourceType = AudiobookSchema.LibrarySourceType.ABS), listOf(child))

        // Provider Boundary Guard (Keeps non-WebDAV sources on live provider listings)
        // SAF and ABS roots must not populate directory_child_cache because their availability and catalog semantics differ from WebDAV scans.
        assertEquals(0, safDao.replaceCallCount)
        assertEquals(0, absDao.replaceCallCount)
        assertNull(safCache.getChildren(sampleDirectory(sourceType = AudiobookSchema.LibrarySourceType.SAF)))
        assertNull(absCache.getChildren(sampleDirectory(sourceType = AudiobookSchema.LibrarySourceType.ABS)))
    }

    @Test
    fun `root eviction should delegate to dao delete by root id`() = runBlocking {
        val dao = FakeDirectoryChildCacheDao()
        val cache = RoomDirectoryListingCache(dao)

        cache.evictRoot("root-1")

        // Explicit Root Eviction (Verifies cleanup can be requested before root deletion)
        // The cache layer forwards root-level eviction to the DAO while foreign-key cascade remains the final database guard.
        assertEquals("root-1", dao.deletedRootId)
    }

    private fun sampleDirectory(sourceType: String): VfsNode {
        val root = LibraryRootEntity(
            id = "root-1",
            sourceType = sourceType,
            sourceUri = "https://example.com/dav",
            displayName = "Library"
        )
        val metadata = SourceFileMetadata(
            sourcePath = "folder",
            identity = "folder-etag",
            parentSourcePath = "",
            parentIdentity = "root-1",
            displayName = "folder",
            isDirectory = true,
            fileSize = 0L,
            lastModified = 100L,
            etag = "folder-etag"
        )
        return VfsNode(
            root = root,
            path = VfsPath(metadata.sourcePath),
            metadata = metadata,
            sourceNode = SourceNode(root = root, metadata = metadata)
        )
    }

    private fun sampleChild(sourcePath: String, displayName: String): SourceFileMetadata =
        SourceFileMetadata(
            sourcePath = sourcePath,
            identity = "child-etag",
            parentSourcePath = "folder",
            parentIdentity = "folder-etag",
            displayName = displayName,
            isDirectory = false,
            fileSize = 2048L,
            lastModified = 200L,
            etag = "child-etag",
            mimeType = "audio/mp4"
        )

    private class FakeDirectoryChildCacheDao : DirectoryChildCacheDao() {
        val rows = mutableListOf<DirectoryChildCacheEntity>()
        var replacedRootId: String? = null
        var replacedParentSourcePath: String? = null
        var replaceCallCount: Int = 0
        var deletedRootId: String? = null
        var lastMinCachedAt: Long? = null

        override suspend fun getChildren(
            rootId: String,
            parentSourcePath: String,
            minCachedAt: Long
        ): List<DirectoryChildCacheEntity> {
            lastMinCachedAt = minCachedAt
            return rows.filter { row ->
                row.rootId == rootId &&
                    row.parentSourcePath == parentSourcePath &&
                    row.cachedAt >= minCachedAt
            }
                .sortedBy { row -> row.displayName }
        }

        override suspend fun deleteChildren(rootId: String, parentSourcePath: String) {
            rows.removeAll { row -> row.rootId == rootId && row.parentSourcePath == parentSourcePath }
        }

        override suspend fun insertChildren(children: List<DirectoryChildCacheEntity>) {
            rows += children
        }

        override suspend fun replaceChildren(
            rootId: String,
            parentSourcePath: String,
            children: List<DirectoryChildCacheEntity>
        ) {
            replaceCallCount += 1
            replacedRootId = rootId
            replacedParentSourcePath = parentSourcePath
            super.replaceChildren(rootId, parentSourcePath, children)
        }

        override suspend fun deleteByRootId(rootId: String) {
            deletedRootId = rootId
            rows.removeAll { row -> row.rootId == rootId }
        }
    }
}
