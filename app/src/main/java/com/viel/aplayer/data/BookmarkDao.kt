package com.viel.aplayer.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface BookmarkDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(bookmark: BookmarkEntity): Long

    @Query("SELECT * FROM bookmarks WHERE bookUri = :bookUri ORDER BY position ASC")
    fun getBookmarksForBook(bookUri: String): Flow<List<BookmarkEntity>>

    @Delete
    suspend fun delete(bookmark: BookmarkEntity)

    @Query("DELETE FROM bookmarks WHERE bookUri = :bookUri")
    suspend fun deleteBookmarksForBook(bookUri: String)
}
