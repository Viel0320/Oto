package com.viel.aplayer.library.vfs

import android.os.ParcelFileDescriptor
import com.viel.aplayer.data.dao.DirectoryChildCacheDao
import com.viel.aplayer.data.db.AudiobookSchema
import com.viel.aplayer.data.entity.DirectoryChildCacheEntity
import com.viel.aplayer.data.entity.LibraryRootEntity
import com.viel.aplayer.library.vfs.cache.DirectoryCacheMapper
import com.viel.aplayer.library.vfs.cache.DirectoryListingCache
import com.viel.aplayer.library.vfs.cache.RoomDirectoryListingCache
import com.viel.aplayer.library.vfs.sourceProvider.LibrarySourceKind
import com.viel.aplayer.library.vfs.sourceProvider.LibrarySourceProvider
import com.viel.aplayer.library.vfs.sourceProvider.SourceCapabilities
import com.viel.aplayer.library.vfs.sourceProvider.SourceFileMetadata
import com.viel.aplayer.library.vfs.sourceProvider.SourceNode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream

class VirtualFileSystemDirectoryListingCacheTest {

    @Test
    fun `listChildren should read cached children without calling provider`() = runBlocking {
        val provider = FakeProvider()
        val cache = FakeDirectoryListingCache(
            cachedChildren = listOf(sampleMetadata(sourcePath = "folder/cached.m4b", displayName = "cached.m4b"))
        )
        val vfs = VirtualFileSystem(
            providerResolver = { provider },
            directoryListingCache = cache
        )
        val directory = sampleNode(sampleRoot(), sampleMetadata(sourcePath = "folder", displayName = "folder", isDirectory = true))

        val children = vfs.listChildren(directory)

        // Cached Directory Replay (Verifies VFS can serve scanner listings from child snapshots)
        // A non-null cache result must bypass provider listChildren while rebuilding VfsNode values from cached metadata.
        assertEquals(listOf("cached.m4b"), children.map { node -> node.metadata.displayName })
        assertEquals(0, provider.listChildrenCalls)
        assertEquals(1, cache.getChildrenCalls)
        assertEquals(0, cache.replaceChildrenCalls)
    }

    @Test
    fun `listChildren should replace cache after provider fallback`() = runBlocking {
        val provider = FakeProvider(
            providerChildren = listOf(sampleMetadata(sourcePath = "folder/live.m4b", displayName = "live.m4b"))
        )
        val cache = FakeDirectoryListingCache(cachedChildren = null)
        val vfs = VirtualFileSystem(
            providerResolver = { provider },
            directoryListingCache = cache
        )
        val directory = sampleNode(sampleRoot(), sampleMetadata(sourcePath = "folder", displayName = "folder", isDirectory = true))

        val children = vfs.listChildren(directory)

        // Provider Fallback Refresh (Stores successful provider listings back into the directory cache)
        // Null cache results preserve live provider traversal and then refresh only the direct children snapshot for the scanner.
        assertEquals(listOf("live.m4b"), children.map { node -> node.metadata.displayName })
        assertEquals(1, provider.listChildrenCalls)
        assertEquals(1, cache.replaceChildrenCalls)
        assertEquals(listOf("live.m4b"), cache.replacedChildren.map { metadata -> metadata.displayName })
    }

    @Test
    fun `stale directory cache should refresh provider listing and replace cached children`() = runBlocking {
        val nowMillis = 1_000_000_000L
        val directory = sampleNode(sampleRoot(), sampleMetadata(sourcePath = "folder", displayName = "folder", isDirectory = true))
        val staleMetadata = sampleMetadata(sourcePath = "folder/stale.m4b", displayName = "stale.m4b")
        val liveMetadata = sampleMetadata(sourcePath = "folder/live.m4b", displayName = "live.m4b")
        val dao = FakeDirectoryChildCacheDao(
            initialRows = listOf(
                DirectoryCacheMapper.toEntity(
                    rootId = directory.root.id,
                    parentSourcePath = directory.metadata.sourcePath,
                    metadata = staleMetadata,
                    cachedAt = 1L
                )
            )
        )
        val cache = RoomDirectoryListingCache(
            directoryChildCacheDao = dao,
            currentTimeMillis = { nowMillis },
            elapsedRealtimeMillis = { 0L }
        )
        val provider = FakeProvider(providerChildren = listOf(liveMetadata))
        val vfs = VirtualFileSystem(
            providerResolver = { provider },
            directoryListingCache = cache
        )

        val children = vfs.listChildren(directory)

        // Stale Directory Cache Refresh (Pins VFS fallback behavior when cachedAt falls outside the freshness window)
        // Expired Room child rows must be treated as a cache miss so the provider listing replaces stale children with a fresh snapshot.
        assertEquals(listOf("live.m4b"), children.map { node -> node.metadata.displayName })
        assertEquals(1, provider.listChildrenCalls)
        assertEquals(listOf("live.m4b"), dao.rows.map { row -> row.displayName })
        assertEquals(nowMillis, dao.rows.single().cachedAt)
    }

    @Test
    fun `directory cache should not affect readRange`() = runBlocking {
        val provider = FakeProvider(rangeBytes = byteArrayOf(1, 2, 3))
        val cache = FakeDirectoryListingCache(
            cachedChildren = listOf(sampleMetadata(sourcePath = "folder/cached.m4b", displayName = "cached.m4b"))
        )
        val vfs = VirtualFileSystem(
            providerResolver = { provider },
            directoryListingCache = cache
        )
        val file = sampleNode(sampleRoot(), sampleMetadata(sourcePath = "folder/book.m4b", displayName = "book.m4b"))

        val bytes = vfs.readRange(file, offset = 0L, length = 3)

        // Range Boundary Guard (Keeps directory child snapshots out of bounded byte reads)
        // readRange must continue to call the source provider directly and never consume directory listing cache rows.
        assertEquals(listOf(1, 2, 3), bytes?.map { byte -> byte.toInt() })
        assertEquals(1, provider.readRangeCalls)
        assertEquals(0, cache.getChildrenCalls)
    }

    private class FakeDirectoryListingCache(
        private val cachedChildren: List<SourceFileMetadata>?
    ) : DirectoryListingCache {
        var getChildrenCalls = 0
        var replaceChildrenCalls = 0
        var replacedChildren: List<SourceFileMetadata> = emptyList()

        override suspend fun getChildren(directory: VfsNode): List<SourceFileMetadata>? {
            getChildrenCalls += 1
            return cachedChildren
        }

        override suspend fun replaceChildren(directory: VfsNode, children: List<SourceFileMetadata>) {
            replaceChildrenCalls += 1
            replacedChildren = children
        }

        override suspend fun evictRoot(rootId: String) = Unit
    }

    private class FakeDirectoryChildCacheDao(
        initialRows: List<DirectoryChildCacheEntity>
    ) : DirectoryChildCacheDao() {
        val rows = initialRows.toMutableList()

        override suspend fun getChildren(
            rootId: String,
            parentSourcePath: String,
            minCachedAt: Long
        ): List<DirectoryChildCacheEntity> =
            rows.filter { row ->
                row.rootId == rootId &&
                    row.parentSourcePath == parentSourcePath &&
                    row.cachedAt >= minCachedAt
            }
                .sortedBy { row -> row.displayName }

        override suspend fun deleteChildren(rootId: String, parentSourcePath: String) {
            rows.removeAll { row -> row.rootId == rootId && row.parentSourcePath == parentSourcePath }
        }

        override suspend fun insertChildren(children: List<DirectoryChildCacheEntity>) {
            rows += children
        }

        override suspend fun deleteByRootId(rootId: String) {
            rows.removeAll { row -> row.rootId == rootId }
        }
    }

    private class FakeProvider(
        providerChildren: List<SourceFileMetadata> = emptyList(),
        private val rangeBytes: ByteArray? = null
    ) : LibrarySourceProvider {
        override val kind: LibrarySourceKind = LibrarySourceKind.WEBDAV
        override val capabilities: SourceCapabilities = SourceCapabilities(supportsRangeRead = true)
        private val children = providerChildren.map { metadata -> sampleNode(sampleRoot(), metadata).sourceNode }
        var listChildrenCalls = 0
        var readRangeCalls = 0

        override suspend fun rootDirectory(root: LibraryRootEntity): SourceNode? =
            sampleNode(root, sampleMetadata(sourcePath = "", displayName = root.displayName, isDirectory = true)).sourceNode

        override suspend fun resolve(root: LibraryRootEntity, sourcePath: String): SourceNode? =
            sampleNode(root, sampleMetadata(sourcePath = sourcePath, displayName = sourcePath.substringAfterLast('/'))).sourceNode

        override suspend fun listChildren(directory: SourceNode): List<SourceNode> {
            listChildrenCalls += 1
            return children
        }

        override suspend fun openInputStream(file: SourceNode): InputStream? =
            ByteArrayInputStream(ByteArray(0))

        override suspend fun openInputStream(file: SourceNode, offset: Long): InputStream? =
            ByteArrayInputStream(ByteArray(0))

        override suspend fun readRange(file: SourceNode, offset: Long, length: Int): ByteArray? {
            readRangeCalls += 1
            return rangeBytes
        }

        override suspend fun openFileDescriptor(file: SourceNode): ParcelFileDescriptor? = null
        override suspend fun exists(node: SourceNode): Boolean = true
    }
}

private fun sampleRoot(): LibraryRootEntity =
    LibraryRootEntity(
        id = "root-1",
        sourceType = AudiobookSchema.LibrarySourceType.WEBDAV,
        sourceUri = "https://example.com/dav",
        displayName = "WebDAV"
    )

private fun sampleMetadata(
    sourcePath: String,
    displayName: String,
    isDirectory: Boolean = false
): SourceFileMetadata =
    SourceFileMetadata(
        sourcePath = sourcePath,
        identity = sourcePath.ifBlank { "root" },
        parentSourcePath = sourcePath.substringBeforeLast('/', missingDelimiterValue = ""),
        parentIdentity = "root-1",
        displayName = displayName,
        isDirectory = isDirectory,
        fileSize = if (isDirectory) 0L else 1024L,
        lastModified = 100L,
        etag = "etag-$sourcePath"
    )

private fun sampleNode(root: LibraryRootEntity, metadata: SourceFileMetadata): VfsNode =
    VfsNode(
        root = root,
        path = VfsPath(metadata.sourcePath),
        metadata = metadata,
        sourceNode = SourceNode(root = root, metadata = metadata)
    )
