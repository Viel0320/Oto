package com.viel.oto.data.abs.playback

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * Stores pending ABS progress writes that could not be acknowledged by the server.
 *
 * Keeping this DAO in data makes retry and cleanup code share one Room-backed queue while ABS
 * playback code continues to decide when an item should be retried.
 */
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
