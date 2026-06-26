package com.viel.oto.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.viel.oto.data.entity.DirectoryCacheEntity

/**
 * Data Access Object managing directory_cache records for incremental scanning.
 * Handles sub-second intercepts, single-directory state overlays, and flushing operations.
 */
@Dao
interface DirectoryCacheDao {

    /**
     * Resolves directory caches by matching rootId and relative VFS sourcePath.
     * Replaces direct SAF tree URI resolution checks.
     */
    @Query("SELECT * FROM directory_cache WHERE rootId = :rootId AND sourcePath = :sourcePath")
    suspend fun getBySourcePath(rootId: String, sourcePath: String): DirectoryCacheEntity?

    /**
     * Upserts directory modified timestamps into database caches.
     * Invoked when folders are updated during scan phases.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(cache: DirectoryCacheEntity)

    /**
     * Deletes directory cache indices matching rootId and relative VFS paths.
     */
    @Query("DELETE FROM directory_cache WHERE rootId = :rootId AND sourcePath = :sourcePath")
    suspend fun deleteBySourcePath(rootId: String, sourcePath: String)

    /**
     * Force deletes cached directory records belonging to a specific rootId.
     * Note: While SQLite automatically triggers cascades under foreignKey constraints,
     * this serves as a fallback API for manual domain evictions.
     */
    @Query("DELETE FROM directory_cache WHERE rootId = :rootId")
    suspend fun deleteByRootId(rootId: String)
}
