package com.viel.oto.library.vfs.cache

import com.viel.oto.data.db.AudiobookSchema
import com.viel.oto.data.entity.LibraryRootEntity
import com.viel.oto.library.vfs.VfsNode
import com.viel.oto.library.vfs.VfsPath
import com.viel.oto.library.vfs.sourceProvider.SourceFileMetadata
import com.viel.oto.library.vfs.sourceProvider.SourceNode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.io.path.createTempDirectory

class CachedRangeReaderTest {

    @Test
    fun `second supported range read should hit cache without delegate`() = runBlocking {
        val cache = VfsRangeCache(cacheDir = createTempDirectory("cached-range-reader").toFile())
        var delegateCalls = 0
        val reader = CachedRangeReader(
            rangeCache = cache,
            supportsRangeRead = { true },
            readRange = { _, _, _ ->
                delegateCalls += 1
                byteArrayOf(7, 8, 9)
            },
            elapsedRealtimeMillis = { 1000L }
        )
        val node = sampleNode()

        val first = reader.read(node, offset = 0L, length = 3)
        val second = reader.read(node, offset = 0L, length = 3)

        assertArrayEquals(byteArrayOf(7, 8, 9), first)
        assertArrayEquals(byteArrayOf(7, 8, 9), second)
        assertEquals(1, delegateCalls)
    }

    @Test
    fun `unsupported provider should delegate without writing cache`() = runBlocking {
        val cache = VfsRangeCache(cacheDir = createTempDirectory("cached-range-reader-unsupported").toFile())
        var delegateCalls = 0
        val reader = CachedRangeReader(
            rangeCache = cache,
            supportsRangeRead = { false },
            readRange = { _, _, _ ->
                delegateCalls += 1
                byteArrayOf(delegateCalls.toByte())
            },
            elapsedRealtimeMillis = { 1000L }
        )
        val node = sampleNode()

        reader.read(node, offset = 0L, length = 1)
        reader.read(node, offset = 0L, length = 1)

        assertEquals(2, delegateCalls)
    }

    private fun sampleNode(): VfsNode {
        val root = LibraryRootEntity(
            id = "root-1",
            sourceType = AudiobookSchema.LibrarySourceType.WEBDAV,
            sourceUri = "https://example.com/dav",
            displayName = "WebDAV"
        )
        val metadata = SourceFileMetadata(
            sourcePath = "book.m4b",
            identity = "etag-1",
            parentSourcePath = "",
            parentIdentity = root.id,
            displayName = "book.m4b",
            isDirectory = false,
            fileSize = 1024L,
            lastModified = 100L,
            etag = "etag-1"
        )
        return VfsNode(root = root, path = VfsPath(metadata.sourcePath), metadata = metadata, sourceNode = SourceNode(root, metadata))
    }
}
