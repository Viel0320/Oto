package com.viel.oto.data.cache

/**
 * Root-scoped source access cache eviction contract.
 *
 * Data cleanup owns the ordering with Room rows and cover files, while library/VFS adapters own the concrete range
 * cache and in-memory source handles that must be invalidated for the same root.
 */
interface RootSourceCacheEvictor {
    /**
     * Evicts source-derived caches for the root and returns the number of persistent range blocks deleted.
     */
    suspend fun evictRoot(rootId: String): Int
}

/**
 * Default no-op adapter for tests and data-only cleanup paths without source access caches.
 */
object NoOpRootSourceCacheEvictor : RootSourceCacheEvictor {
    override suspend fun evictRoot(rootId: String): Int = 0
}
