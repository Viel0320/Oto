package com.viel.oto.abs.sync

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

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
