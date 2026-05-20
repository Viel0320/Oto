package com.viel.aplayer.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import com.viel.aplayer.data.entity.ChapterEntity

@Dao
interface ChapterDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertChapters(chapters: List<ChapterEntity>)

    @Query("SELECT * FROM chapters WHERE bookId = :bookId ORDER BY `index` ASC")
    fun getChaptersForBook(bookId: String): Flow<List<ChapterEntity>>

    @Query("SELECT * FROM chapters WHERE bookId = :bookId ORDER BY `index` ASC")
    suspend fun getChaptersForBookList(bookId: String): List<ChapterEntity>

    @Query("DELETE FROM chapters WHERE bookId = :bookId")
    suspend fun deleteChaptersForBook(bookId: String)
}