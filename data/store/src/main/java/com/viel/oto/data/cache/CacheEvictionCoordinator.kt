package com.viel.oto.data.cache

import android.content.Context
import com.viel.oto.data.cleanup.LibraryResourceCleanupGateway
import com.viel.oto.data.dao.BookDao
import com.viel.oto.data.dao.DirectoryCacheDao
import com.viel.oto.data.dao.DirectoryChildCacheDao
import com.viel.oto.data.entity.LibraryRootEntity
import java.io.File

/**
 * Coordinates root-scoped cache cleanup before database root deletion.
 * Deletes only cache artifacts owned by the data layer and intentionally avoids scan orchestration, ABS synchronization,
 * playback state, and provider availability checks.
 */
class CacheEvictionCoordinator internal constructor(
    private val appCacheDir: File,
    private val bookDao: BookDao,
    private val directoryCacheDao: DirectoryCacheDao,
    private val directoryChildCacheDao: DirectoryChildCacheDao,
    private val rootSourceCacheEvictor: RootSourceCacheEvictor
) : LibraryResourceCleanupGateway {
    constructor(
        context: Context,
        bookDao: BookDao,
        directoryCacheDao: DirectoryCacheDao,
        directoryChildCacheDao: DirectoryChildCacheDao,
        rootSourceCacheEvictor: RootSourceCacheEvictor
    ) : this(
        appCacheDir = context.applicationContext.cacheDir,
        bookDao = bookDao,
        directoryCacheDao = directoryCacheDao,
        directoryChildCacheDao = directoryChildCacheDao,
        rootSourceCacheEvictor = rootSourceCacheEvictor
    )

    /**
     * Collects and clears root-scoped cache records before Room cascade deletion.
     * Reads cover paths while book rows still exist, then clears directory caches so subsequent root deletion does not leave
     * orphaned file artifacts or directory snapshot rows.
     */
    suspend fun evictBeforeRootDelete(root: LibraryRootEntity): CacheEvictionSummary {
        return evictRootCaches(root.id)
    }

    /**
     * Delete one book's artwork files before soft deletion.
     * Book deletion keeps the database row as DELETED, so this gateway removes owned cover files while the active row still
     * exposes the cache paths needed to identify them.
     */
    override suspend fun clearBookCoverCache(bookId: String) {
        deleteBookCoverFiles(bookId)
    }

    /**
     * Bridge application cleanup requests to root cache eviction.
     * Root-management use cases call this before cascade deletion so cover paths, directory snapshots, and range blocks are
     * still attributable to the root being removed or switched.
     */
    override suspend fun clearRootDerivedCaches(rootId: String) {
        evictRootCaches(rootId)
    }

    /**
     * Clears root-scoped derived artifacts without deleting the root row.
     * Root edits reuse the same directory, artwork, and range-cache cleanup as root deletion so rescans cannot reuse stale provider coordinates.
     */
    suspend fun evictRootCaches(rootId: String): CacheEvictionSummary {
        val coverFilesDeleted = deleteRootCoverFiles(rootId)
        directoryCacheDao.deleteByRootId(rootId)
        directoryChildCacheDao.deleteByRootId(rootId)
        val rangeFilesDeleted = rootSourceCacheEvictor.evictRoot(rootId)
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

    private suspend fun deleteBookCoverFiles(bookId: String): Int {
        val coversDir = File(appCacheDir, COVER_CACHE_DIR_NAME).canonicalFile
        val paths = bookDao.getCoverCachePathsByBookId(bookId)
            ?.let { cachePaths -> listOfNotNull(cachePaths.coverPath, cachePaths.thumbnailPath) }
            .orEmpty()
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
 * Reports root-scoped cleanup outcomes without exposing paths.
 * Provides narrow counts and boolean table cleanup markers for diagnostics while avoiding full local file paths in callers.
 */
data class CacheEvictionSummary(
    val rootId: String,
    val coverFilesDeleted: Int,
    val directoryRowsDeleted: Boolean,
    val directoryChildRowsDeleted: Boolean,
    val rangeFilesDeleted: Int
)
