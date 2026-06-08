package com.viel.aplayer.library.vfs.cache

import android.os.SystemClock
import com.viel.aplayer.data.dao.DirectoryChildCacheDao
import com.viel.aplayer.library.vfs.VfsNode
import com.viel.aplayer.library.vfs.sourceProvider.LibrarySourceKind
import com.viel.aplayer.library.vfs.sourceProvider.SourceFileMetadata
import com.viel.aplayer.logger.CacheDiagnosticsLogger

/**
 * Room Directory Listing Cache (Persists WebDAV direct child snapshots for scanner reuse)
 * Limits cache reads and writes to WebDAV roots so SAF permissions and ABS catalog mirrors continue to use their live provider paths.
 */
class RoomDirectoryListingCache(
    private val directoryChildCacheDao: DirectoryChildCacheDao,
    private val mapper: DirectoryCacheMapper = DirectoryCacheMapper,
    private val currentTimeMillis: () -> Long = { System.currentTimeMillis() },
    private val maxCacheAgeMillis: Long = DEFAULT_MAX_CACHE_AGE_MILLIS,
    private val elapsedRealtimeMillis: () -> Long = { SystemClock.elapsedRealtime() }
) : DirectoryListingCache {

    /**
     * Read Cached Children (Returns WebDAV child metadata snapshots when rows exist)
     * A null return keeps the VFS miss path explicit, causing callers to query the provider and refresh the Room snapshot.
     */
    override suspend fun getChildren(directory: VfsNode): List<SourceFileMetadata>? {
        if (!directory.isWebDavDirectory()) return null
        // Directory Cache Timing Source (Uses injectable elapsed time for JVM-safe diagnostics)
        // The cache behavior depends only on DAO results, so timing collection is kept replaceable for unit tests.
        val startedAtElapsedMs = elapsedRealtimeMillis()
        val rows = directoryChildCacheDao.getChildren(
            rootId = directory.root.id,
            parentSourcePath = directory.metadata.sourcePath,
            minCachedAt = directory.minimumFreshCachedAt()
        )
        val sourceHash = directory.cacheSourceHash()
        if (rows.isEmpty()) {
            CacheDiagnosticsLogger.logCacheEvent(
                cacheType = "directory",
                operation = "getChildren",
                hit = false,
                costMs = elapsedRealtimeMillis() - startedAtElapsedMs,
                sourceHash = sourceHash,
                sizeBytes = 0L
            )
            return null
        }
        CacheDiagnosticsLogger.logCacheEvent(
            cacheType = "directory",
            operation = "getChildren",
            hit = true,
            costMs = elapsedRealtimeMillis() - startedAtElapsedMs,
            sourceHash = sourceHash,
            sizeBytes = rows.size.toLong()
        )
        return rows.map(mapper::toMetadata)
    }

    /**
     * Replace Cached Children (Stores the latest WebDAV provider result for one parent directory)
     * Writes only direct children under the current directory sourcePath and records a shared cachedAt timestamp for that snapshot.
     */
    override suspend fun replaceChildren(directory: VfsNode, children: List<SourceFileMetadata>) {
        if (!directory.isWebDavDirectory()) return
        // Directory Cache Write Timing Source (Separates diagnostics timing from Android runtime APIs)
        // This keeps Room snapshot persistence testable on the JVM while preserving elapsed-time metrics on device.
        val startedAtElapsedMs = elapsedRealtimeMillis()
        val cachedAt = currentTimeMillis()
        val rows = children.map { child ->
            mapper.toEntity(
                rootId = directory.root.id,
                parentSourcePath = directory.metadata.sourcePath,
                metadata = child,
                cachedAt = cachedAt
            )
        }
        directoryChildCacheDao.replaceChildren(
            rootId = directory.root.id,
            parentSourcePath = directory.metadata.sourcePath,
            children = rows
        )
        CacheDiagnosticsLogger.logCacheEvent(
            cacheType = "directory",
            operation = "replaceChildren",
            hit = null,
            costMs = elapsedRealtimeMillis() - startedAtElapsedMs,
            sourceHash = directory.cacheSourceHash(),
            sizeBytes = children.size.toLong()
        )
    }

    /**
     * Evict Root Children (Deletes WebDAV listing snapshots associated with one root)
     * Provides an explicit cleanup hook while Room foreign-key cascades remain the final deletion guard.
     */
    override suspend fun evictRoot(rootId: String) {
        directoryChildCacheDao.deleteByRootId(rootId)
    }

    private fun VfsNode.isWebDavDirectory(): Boolean =
        metadata.isDirectory && LibrarySourceKind.from(root.sourceType) == LibrarySourceKind.WEBDAV

    private fun VfsNode.cacheSourceHash(): String? =
        CacheDiagnosticsLogger.hashIdentifier("${root.id}:${metadata.sourcePath}")

    private fun VfsNode.minimumFreshCachedAt(): Long {
        // Directory Listing Freshness Window (Turns expired rows into cache misses before VFS replay)
        // The lower bound lives in the WebDAV cache adapter so callers keep the simple hit-or-refresh contract.
        return currentTimeMillis().minus(maxCacheAgeMillis).coerceAtLeast(0L)
    }

    private companion object {
        /**
         * Default Directory Listing TTL (Balances repeated WebDAV scan speed with remote tree freshness)
         * One minute avoids reusing stale directory children across later scans while still reducing immediate retry traffic.
         */
        private const val DEFAULT_MAX_CACHE_AGE_MILLIS = 60_000L
    }
}
