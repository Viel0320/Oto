package com.viel.oto.abs.playback

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface AbsPendingProgressSyncDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrReplace(sync: AbsPendingProgressSyncEntity)

    @Query("SELECT * FROM abs_pending_progress_sync WHERE bookId = :bookId")
    suspend fun getByBookId(bookId: String): AbsPendingProgressSyncEntity?

    @Query("DELETE FROM abs_pending_progress_sync WHERE bookId = :bookId")
    suspend fun deleteByBookId(bookId: String)

    @Query("DELETE FROM abs_pending_progress_sync WHERE bookId IN (:bookIds)")
    suspend fun deleteByBookIds(bookIds: List<String>)
}
