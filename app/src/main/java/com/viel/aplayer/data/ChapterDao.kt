package com.viel.aplayer.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ChapterDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertChapters(chapters: List<ChapterEntity>)

    @Query("SELECT * FROM chapters WHERE bookUri = :bookUri ORDER BY startPosition ASC")
    fun getChaptersForBook(bookUri: String): Flow<List<ChapterEntity>>

    @Query("DELETE FROM chapters WHERE bookUri = :bookUri")
    suspend fun deleteChaptersForBook(bookUri: String)
}
