package com.viel.aplayer.data.cache

import android.content.Context
import com.viel.aplayer.data.dao.BookDao
import com.viel.aplayer.data.dao.DirectoryCacheDao
import com.viel.aplayer.data.dao.DirectoryChildCacheDao
import com.viel.aplayer.data.entity.LibraryRootEntity
import com.viel.aplayer.library.vfs.cache.VfsRangeCache
import com.viel.aplayer.library.vfs.VfsFileInterface
import com.viel.aplayer.library.vfs.cache.VfsRangeCacheKey
import java.io.File

/**
 * Cache Eviction Coordinator (Coordinates root-scoped cache cleanup before database root deletion)
 * Deletes only cache artifacts owned by the data layer and intentionally avoids scan orchestration, ABS synchronization,
 * playback state, and provider availability checks.
 */
class CacheEvictionCoordinator internal constructor(
    private val appCacheDir: File,
    private val bookDao: BookDao,
    private val directoryCacheDao: DirectoryCacheDao,
    private val directoryChildCacheDao: DirectoryChildCacheDao,
    private val vfsRangeCache: VfsRangeCache? = null,
    private val vfsFileInterface: VfsFileInterface? = null
) {
    constructor(
        context: Context,
        bookDao: BookDao,
        directoryCacheDao: DirectoryCacheDao,
        directoryChildCacheDao: DirectoryChildCacheDao,
        vfsRangeCache: VfsRangeCache? = null,
        vfsFileInterface: VfsFileInterface? = null
    ) : this(
        appCacheDir = context.applicationContext.cacheDir,
        bookDao = bookDao,
        directoryCacheDao = directoryCacheDao,
        directoryChildCacheDao = directoryChildCacheDao,
        vfsRangeCache = vfsRangeCache,
        vfsFileInterface = vfsFileInterface
    )

    /**
     * Evict Before Root Delete (Collects and clears root-scoped cache records before Room cascade deletion)
     * Reads cover paths while book rows still exist, then clears directory caches so subsequent root deletion does not leave
     * orphaned file artifacts or directory snapshot rows.
     */
    suspend fun evictBeforeRootDelete(root: LibraryRootEntity): CacheEvictionSummary {
        return evictRootCaches(root.id)
    }

    /**
     * Evict Root Caches (Clears root-scoped derived artifacts without deleting the root row)
     * Root edits reuse the same directory, artwork, and range-cache cleanup as root deletion so rescans cannot reuse stale provider coordinates.
     */
    suspend fun evictRootCaches(rootId: String): CacheEvictionSummary {
        val coverFilesDeleted = deleteRootCoverFiles(rootId)
        directoryCacheDao.deleteByRootId(rootId)
        directoryChildCacheDao.deleteByRootId(rootId)
        // Range Cache Root Eviction (Clears metadata-sized byte blocks for the edited or deleted library root)
        // Uses the same hashed root id as VfsRangeCacheKey so raw root identifiers never appear in cache file names.
        val rangeFilesDeleted = vfsRangeCache?.evictRoot(VfsRangeCacheKey.hashIdentifier(rootId)) ?: 0
        /**
         * Evict VfsFileInterface Root Cache: Clears the in-memory cached LibraryRootEntity for the rootId.
         * Ensures VFS operations pick up updated connection properties without requiring a process restart.
         */
        vfsFileInterface?.evictRoot(rootId)
        return CacheEvictionSummary(
            rootId = rootId,
            coverFilesDeleted = coverFilesDeleted,
            directoryRowsDeleted = true,
            directoryChildRowsDeleted = true,
            rangeFilesDeleted = rangeFilesDeleted
        )
    }

    private suspend fun deleteRootCoverFiles(rootId: String): Int {
        val coversDir = File(appCacheDir, COVER_CACHE_DIR_NAME).canonicalFile
        val paths = bookDao.getCoverCachePathsByRootId(rootId)
            .flatMap { cachePaths -> listOfNotNull(cachePaths.coverPath, cachePaths.thumbnailPath) }
            .distinct()
        return paths.count { path -> deleteIfOwnedCoverFile(path, coversDir) }
    }

    private fun deleteIfOwnedCoverFile(path: String, coversDir: File): Boolean {
        val file = runCatching { File(path).canonicalFile }.getOrNull() ?: return false
        if (!file.exists() || !file.isFile) return false
        if (!file.isDescendantOf(coversDir)) return false
        return runCatching { file.delete() }.getOrDefault(false)
    }

    private fun File.isDescendantOf(parent: File): Boolean {
        var cursor = parentFile
        while (cursor != null) {
            if (cursor == parent) return true
            cursor = cursor.parentFile
        }
        return false
    }

    private companion object {
        private const val COVER_CACHE_DIR_NAME = "covers"
    }
}

/**
 * Cache Eviction Summary (Reports root-scoped cleanup outcomes without exposing paths)
 * Provides narrow counts and boolean table cleanup markers for diagnostics while avoiding full local file paths in callers.
 */
data class CacheEvictionSummary(
    val rootId: String,
    val coverFilesDeleted: Int,
    val directoryRowsDeleted: Boolean,
    val directoryChildRowsDeleted: Boolean,
    val rangeFilesDeleted: Int
)
