package com.viel.oto.data.abs.sync

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * Stores local-to-ABS item mirror rows by root and local book ID.
 *
 * The mirror table is data-owned so deletion recovery, cover recovery, and ABS synchronization can
 * query one persisted mapping without depending on protocol DTOs.
 */
@Dao
interface AbsItemMirrorDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrReplaceAll(items: List<AbsItemMirrorEntity>)

    @Query("SELECT * FROM abs_item_mirror WHERE rootId = :rootId")
    suspend fun getByRootId(rootId: String): List<AbsItemMirrorEntity>

    @Query("SELECT * FROM abs_item_mirror WHERE localBookId = :localBookId")
    suspend fun getByLocalBookId(localBookId: String): AbsItemMirrorEntity?

    @Query("DELETE FROM abs_item_mirror WHERE rootId = :rootId")
    suspend fun deleteByRootId(rootId: String)
}
