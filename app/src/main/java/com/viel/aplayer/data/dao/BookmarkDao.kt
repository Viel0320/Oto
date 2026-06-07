package com.viel.aplayer.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.viel.aplayer.data.entity.BookmarkEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface BookmarkDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(bookmark: BookmarkEntity): Long

    // Bookmark Batch Restore (Ownership replacement migration)
    // Rewrites migrated bookmark rows in one DAO call after their book and file anchors have been remapped.
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(bookmarks: List<BookmarkEntity>)

    @Query("SELECT * FROM bookmarks WHERE bookId = :bookId ORDER BY globalPositionMs ASC")
    fun getBookmarksForBook(bookId: String): Flow<List<BookmarkEntity>>

    // Bookmark Snapshot Query (Transactional migration input)
    // Reads bookmarks without collecting a Flow so BookImporter can migrate ownership state inside a single Room transaction.
    @Query("SELECT * FROM bookmarks WHERE bookId = :bookId ORDER BY globalPositionMs ASC")
    suspend fun getBookmarksForBookSync(bookId: String): List<BookmarkEntity>

    @Delete
    suspend fun delete(bookmark: BookmarkEntity)

    @Query("DELETE FROM bookmarks WHERE bookId = :bookId")
    suspend fun deleteBookmarksForBook(bookId: String)
}
