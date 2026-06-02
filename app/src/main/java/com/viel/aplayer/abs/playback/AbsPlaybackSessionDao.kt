package com.viel.aplayer.abs.playback

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface AbsPlaybackSessionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrReplace(session: AbsPlaybackSessionEntity)

    @Query("SELECT * FROM abs_playback_session WHERE bookId = :bookId")
    suspend fun getByBookId(bookId: String): AbsPlaybackSessionEntity?

    @Query("DELETE FROM abs_playback_session WHERE bookId = :bookId")
    suspend fun deleteByBookId(bookId: String)

    @Query("DELETE FROM abs_playback_session WHERE bookId IN (:bookIds)")
    suspend fun deleteByBookIds(bookIds: List<String>)
}
