package com.viel.aplayer.library.vfs.cache

import com.viel.aplayer.data.db.AudiobookSchema
import com.viel.aplayer.data.entity.LibraryRootEntity
import com.viel.aplayer.library.vfs.VfsNode
import com.viel.aplayer.library.vfs.VfsPath
import com.viel.aplayer.library.vfs.sourceProvider.SourceFileMetadata
import com.viel.aplayer.library.vfs.sourceProvider.SourceNode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VfsRangeCacheKeyTest {

    @Test
    fun `range key should hash root path and etag without leaking raw values`() {
        val node = sampleNode(
            rootId = "root-secret",
            sourcePath = "Books/Author/title.m4b",
            etag = "raw-etag-secret",
            lastModified = 100L,
            fileSize = 200L
        )

        val key = VfsRangeCacheKey.from(node, offset = 10L, length = 4096)

        assertNotNull(key)
        val fileName = key!!.toFileName()
        assertTrue(fileName.endsWith("_10_4096.bin"))
        assertFalse(fileName.contains("root-secret"))
        assertFalse(fileName.contains("Books"))
        assertFalse(fileName.contains("raw-etag-secret"))
        assertTrue(key.hasProviderVersion)
    }

    @Test
    fun `range key should reject invalid offset and length`() {
        val node = sampleNode()

        assertNull(VfsRangeCacheKey.from(node, offset = -1L, length = 4096))
        assertNull(VfsRangeCacheKey.from(node, offset = 0L, length = 0))
    }

    @Test
    fun `version hash should fall back to modified time and file size without etag`() {
        val first = VfsRangeCacheKey.versionHash(etag = null, lastModified = 100L, fileSize = 200L)
        val second = VfsRangeCacheKey.versionHash(etag = null, lastModified = 101L, fileSize = 200L)
        val key = VfsRangeCacheKey.from(sampleNode(etag = null), offset = 0L, length = 1)

        assertTrue(first != second)
        assertFalse(first.contains("100"))
        assertFalse(first.contains("200"))
        assertNotNull(key)
        assertTrue(key!!.hasProviderVersion)
    }

    @Test
    fun `range key should mark truly versionless metadata without etag or modified time`() {
        val key = VfsRangeCacheKey.from(sampleNode(etag = null, lastModified = 0L), offset = 0L, length = 1)

        assertNotNull(key)
        assertFalse(key!!.hasProviderVersion)
    }

    private fun sampleNode(
        rootId: String = "root-1",
        sourcePath: String = "book.m4b",
        etag: String? = "etag-1",
        lastModified: Long = 100L,
        fileSize: Long = 200L
    ): VfsNode {
        val root = LibraryRootEntity(
            id = rootId,
            sourceType = AudiobookSchema.LibrarySourceType.WEBDAV,
            sourceUri = "https://example.com/dav",
            displayName = "WebDAV"
        )
        val metadata = SourceFileMetadata(
            sourcePath = sourcePath,
            identity = etag ?: sourcePath,
            parentSourcePath = "",
            parentIdentity = root.id,
            displayName = sourcePath.substringAfterLast('/'),
            isDirectory = false,
            fileSize = fileSize,
            lastModified = lastModified,
            etag = etag
        )
        return VfsNode(root = root, path = VfsPath(sourcePath), metadata = metadata, sourceNode = SourceNode(root, metadata))
    }
}
