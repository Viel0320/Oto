package com.viel.oto.library.vfs.cache

import com.viel.oto.data.cache.RootSourceCacheEvictor
import com.viel.oto.library.vfs.VfsFileInterface

/**
 * VFS-backed root source cache eviction adapter.
 *
 * Converts the data-layer root cleanup request into the hashed range-cache namespace and clears the VFS root snapshot
 * so edited source coordinates, credentials, or availability state are reloaded on the next file access.
 */
class VfsRootSourceCacheEvictor(
    private val rangeCache: VfsRangeCache? = null,
    private val fileInterface: VfsFileInterface? = null
) : RootSourceCacheEvictor {
    override suspend fun evictRoot(rootId: String): Int {
        val rangeFilesDeleted = rangeCache?.evictRoot(VfsRangeCacheKey.hashIdentifier(rootId)) ?: 0
        fileInterface?.evictRoot(rootId)
        return rangeFilesDeleted
    }
}
