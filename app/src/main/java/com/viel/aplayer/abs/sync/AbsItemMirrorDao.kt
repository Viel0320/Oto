package com.viel.aplayer.abs.sync

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

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
