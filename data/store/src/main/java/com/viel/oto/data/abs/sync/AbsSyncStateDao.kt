package com.viel.oto.data.abs.sync

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Persists per-root ABS catalog synchronization checkpoints.
 *
 * The DAO exposes only root-scoped state operations so settings and sync schedulers can observe
 * local progress without reading ABS network objects.
 */
@Dao
interface AbsSyncStateDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrReplace(state: AbsSyncStateEntity)

    @Query("SELECT * FROM abs_sync_state WHERE rootId = :rootId")
    suspend fun getByRootId(rootId: String): AbsSyncStateEntity?

    @Query("SELECT * FROM abs_sync_state")
    fun observeAll(): Flow<List<AbsSyncStateEntity>>

    @Query("DELETE FROM abs_sync_state WHERE rootId = :rootId")
    suspend fun deleteByRootId(rootId: String)
}
