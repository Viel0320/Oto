package com.viel.oto.library.vfs.cache

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.io.path.createTempDirectory

class VfsRangeCacheTest {

    @Test
    fun `read should return null before write and restore bytes after write`() = runBlocking {
        val cache = VfsRangeCache(cacheDir = createTempDirectory("range-cache").toFile())
        val key = sampleKey()

        assertNull(cache.read(key))
        cache.write(key, byteArrayOf(1, 2, 3))
        assertArrayEquals(byteArrayOf(1, 2, 3), cache.read(key))
    }

    @Test
    fun `write should ignore blocks larger than single block limit`() = runBlocking {
        val cache = VfsRangeCache(
            cacheDir = createTempDirectory("range-cache-limit").toFile(),
            maxBlockBytes = 4
        )
        val key = sampleKey()

        cache.write(key, byteArrayOf(1, 2, 3, 4, 5))

        assertNull(cache.read(key))
    }

    @Test
    fun `trim should delete oldest files over total size limit`() = runBlocking {
        val cache = VfsRangeCache(
            cacheDir = createTempDirectory("range-cache-trim").toFile(),
            maxBlockBytes = 10,
            maxTotalBytes = 6L
        )

        cache.write(sampleKey(source = "one"), byteArrayOf(1, 1, 1, 1))
        cache.write(sampleKey(source = "two"), byteArrayOf(2, 2, 2, 2))
        cache.trimToSize()

        val remaining = listOfNotNull(cache.read(sampleKey(source = "one")), cache.read(sampleKey(source = "two")))
        assertEquals(1, remaining.size)
    }

    @Test
    fun `read should expire blocks outside range ttl`() = runBlocking {
        var now = 1_000L
        val cache = VfsRangeCache(
            cacheDir = createTempDirectory("range-cache-expire").toFile(),
            currentTimeMillis = { now }
        )
        val key = sampleKey()

        cache.write(key, byteArrayOf(4, 5, 6))
        now += com.viel.oto.data.cache.OnlineSourceCachePolicy.ONLINE_METADATA_RANGE_TTL_MS + 1L

        assertNull(cache.read(key))
    }

    @Test
    fun `evict root should delete only matching root files`() = runBlocking {
        val cache = VfsRangeCache(cacheDir = createTempDirectory("range-cache-evict").toFile())
        val rootOne = VfsRangeCacheKey.hashIdentifier("root-1")
        val rootTwo = VfsRangeCacheKey.hashIdentifier("root-2")
        val first = sampleKey(root = rootOne, source = "book-1")
        val second = sampleKey(root = rootTwo, source = "book-2")
        cache.write(first, byteArrayOf(1))
        cache.write(second, byteArrayOf(2))

        val deleted = cache.evictRoot(rootOne)

        assertEquals(1, deleted)
        assertNull(cache.read(first))
        assertArrayEquals(byteArrayOf(2), cache.read(second))
    }

    private fun sampleKey(
        root: String = VfsRangeCacheKey.hashIdentifier("root-1"),
        source: String = VfsRangeCacheKey.hashIdentifier("book.m4b")
    ) = VfsRangeCacheKey(
        rootIdHash = root,
        sourcePathHash = source,
        version = VfsRangeCacheKey.hashIdentifier("etag-1"),
        hasProviderVersion = true,
        offset = 0L,
        length = 4
    )
}
