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

        // Hashed Range Key Contract (Protects range cache filenames from provider coordinates)
        // The generated filename must contain only hashed identity segments and numeric bounds, never raw root ids, paths, or etags.
        assertNotNull(key)
        val fileName = key!!.toFileName()
        assertTrue(fileName.endsWith("_10_4096.bin"))
        assertFalse(fileName.contains("root-secret"))
        assertFalse(fileName.contains("Books"))
        assertFalse(fileName.contains("raw-etag-secret"))
    }

    @Test
    fun `range key should reject invalid offset and length`() {
        val node = sampleNode()

        // Invalid Range Guard (Prevents malformed cache files for impossible reads)
        // Negative offsets and non-positive lengths must bypass range caching and delegate directly to the provider.
        assertNull(VfsRangeCacheKey.from(node, offset = -1L, length = 4096))
        assertNull(VfsRangeCacheKey.from(node, offset = 0L, length = 0))
    }

    @Test
    fun `version hash should fall back to modified time and file size without etag`() {
        val first = VfsRangeCacheKey.versionHash(etag = null, lastModified = 100L, fileSize = 200L)
        val second = VfsRangeCacheKey.versionHash(etag = null, lastModified = 101L, fileSize = 200L)

        // Fallback Version Rule (Keeps non-etag sources cacheable through stable metadata)
        // Changing lastModified or fileSize must produce a different hashed version for providers that do not expose etags.
        assertTrue(first != second)
        assertFalse(first.contains("100"))
        assertFalse(first.contains("200"))
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
