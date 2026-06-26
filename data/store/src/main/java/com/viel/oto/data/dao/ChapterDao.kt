package com.viel.oto.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.viel.oto.data.entity.ChapterEntity
import com.viel.oto.data.entity.ChapterWithBookFile
import kotlinx.coroutines.flow.Flow

@Dao
interface ChapterDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertChapters(chapters: List<ChapterEntity>)

    /**
     * Emits chapter composition list paired with physical file states.
     *
     * Annotated with @Transaction to enforce atomic multi-table queries for nested @Relation fields,
     * preventing UI discrepancies caused by concurrent DB updates during scans.
     */
    @Transaction
    @Query("SELECT * FROM chapters WHERE bookId = :bookId ORDER BY `index` ASC")
    fun getChaptersForBook(bookId: String): Flow<List<ChapterWithBookFile>>

    /**
     * Fetches chapter composition list paired with physical file states.
     *
     * Annotated with @Transaction to guarantee nested BookFile queries execute atomically in one database action.
     */
    @Transaction
    @Query("SELECT * FROM chapters WHERE bookId = :bookId ORDER BY `index` ASC")
    suspend fun getChaptersForBookList(bookId: String): List<ChapterWithBookFile>

    @Query("DELETE FROM chapters WHERE bookId = :bookId")
    suspend fun deleteChaptersForBook(bookId: String)

    /**
     * Deletes old chapter entities and batches new entries in a single transaction.
     * Guarantees that even if operations get cancelled mid-execution, the audiobook metadata remains consistent,
     * resolving empty-state flickering issues observed on Flow collectors.
     */
    @Transaction
    suspend fun replaceChapters(bookId: String, chapters: List<ChapterEntity>) {
        deleteChaptersForBook(bookId)
        insertChapters(chapters)
    }
}