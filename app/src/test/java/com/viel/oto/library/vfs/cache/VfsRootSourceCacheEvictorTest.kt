package com.viel.oto.library.vfs.cache

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

/**
 * Verifies the VFS adapter behind data-layer root cache cleanup.
 *
 * CacheEvictionCoordinator tests use a fake data contract; this test pins the library/VFS implementation that maps
 * plain root IDs into the hashed range-cache namespace.
 */
class VfsRootSourceCacheEvictorTest {
    @Test
    fun `evictRoot should delete only matching root range blocks`() = runBlocking {
        val appCacheDir = createTempDirectory("vfs-source-cache-evictor").toFile()
        val rangeCache = VfsRangeCache(cacheDir = File(appCacheDir, "vfs_range_cache"))
        val ownedRangeKey = sampleRangeKey(VfsRangeCacheKey.hashIdentifier("root-1"), "book-1")
        val otherRangeKey = sampleRangeKey(VfsRangeCacheKey.hashIdentifier("root-2"), "book-2")
        rangeCache.write(ownedRangeKey, byteArrayOf(1))
        rangeCache.write(otherRangeKey, byteArrayOf(2))

        val evictor = VfsRootSourceCacheEvictor(rangeCache = rangeCache)

        val deleted = evictor.evictRoot("root-1")

        assertEquals(1, deleted)
        assertEquals(null, rangeCache.read(ownedRangeKey))
        assertEquals(listOf(2), rangeCache.read(otherRangeKey)?.map { byte -> byte.toInt() })
    }

    private fun sampleRangeKey(rootHash: String, source: String) =
        VfsRangeCacheKey(
            rootIdHash = rootHash,
            sourcePathHash = VfsRangeCacheKey.hashIdentifier(source),
            version = VfsRangeCacheKey.hashIdentifier("etag-$source"),
            hasProviderVersion = true,
            offset = 0L,
            length = 1
        )
}
