package com.viel.aplayer.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.viel.aplayer.data.entity.DirectoryChildCacheEntity

/**
 * Owns direct child snapshot persistence for directory listing reuse.
 * Restricts all reads and deletes to rootId plus parentSourcePath boundaries so directory listing caches do not query books,
 * book files, or provider-specific runtime objects.
 */
@Dao
abstract class DirectoryChildCacheDao {
    /**
     * Reads a single parent directory snapshot inside the accepted freshness window.
     * Filters cachedAt at the DAO boundary so expired WebDAV listings become ordinary cache misses before the VFS can replay them.
     */
    @Query(
        """
        SELECT * FROM directory_child_cache
        WHERE rootId = :rootId
          AND parentSourcePath = :parentSourcePath
          AND cachedAt >= :minCachedAt
        ORDER BY displayName ASC
        """
    )
    abstract suspend fun getChildren(
        rootId: String,
        parentSourcePath: String,
        minCachedAt: Long
    ): List<DirectoryChildCacheEntity>

    /**
     * Clears one parent directory snapshot before replacement.
     * Uses the same rootId plus parentSourcePath scope as reads, avoiding broad or fuzzy path matching.
     */
    @Query("DELETE FROM directory_child_cache WHERE rootId = :rootId AND parentSourcePath = :parentSourcePath")
    abstract suspend fun deleteChildren(rootId: String, parentSourcePath: String)

    /**
     * Persists normalized child metadata rows.
     * Replaces rows with identical cache keys so repeated scans update changed WebDAV metadata without duplicate rows.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertChildren(children: List<DirectoryChildCacheEntity>)

    /**
     * Atomically swaps one directory child snapshot.
     * Deletes stale rows first and then inserts the current direct children to keep Room state aligned with the provider result.
     */
    @Transaction
    open suspend fun replaceChildren(
        rootId: String,
        parentSourcePath: String,
        children: List<DirectoryChildCacheEntity>
    ) {
        deleteChildren(rootId, parentSourcePath)
        if (children.isNotEmpty()) {
            insertChildren(children)
        }
    }

    /**
     * Removes all directory child snapshots for one library root.
     * Supports explicit root eviction while the LibraryRootEntity foreign key remains the final cascade safety net.
     */
    @Query("DELETE FROM directory_child_cache WHERE rootId = :rootId")
    abstract suspend fun deleteByRootId(rootId: String)
}
