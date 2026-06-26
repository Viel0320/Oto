package com.viel.oto.library.vfs.cache

import com.viel.oto.library.vfs.sourceProvider.SourceFileMetadata
import org.junit.Assert.assertEquals
import org.junit.Test

class DirectoryCacheMapperTest {

    @Test
    fun `metadata should round trip through directory child cache entity`() {
        val metadata = SourceFileMetadata(
            sourcePath = "folder/book.m4b",
            identity = "etag-book",
            parentSourcePath = "folder",
            parentIdentity = "etag-folder",
            displayName = "book.m4b",
            isDirectory = false,
            fileSize = 4096L,
            lastModified = 123456L,
            etag = "etag-book",
            mimeType = "audio/mp4"
        )

        val entity = DirectoryCacheMapper.toEntity(
            rootId = "root-1",
            parentSourcePath = "folder",
            metadata = metadata,
            cachedAt = 9000L
        )
        val restored = DirectoryCacheMapper.toMetadata(entity)

        assertEquals("root-1|folder|folder/book.m4b", entity.cacheKey)
        assertEquals("root-1", entity.rootId)
        assertEquals("folder", entity.parentSourcePath)
        assertEquals(9000L, entity.cachedAt)

        assertEquals(metadata, restored)
    }

    @Test
    fun `root directory child cache key should preserve blank parent path`() {
        val key = DirectoryCacheMapper.cacheKey(
            rootId = "root-1",
            parentSourcePath = "",
            sourcePath = "book.m4b"
        )

        assertEquals("root-1||book.m4b", key)
    }
}
