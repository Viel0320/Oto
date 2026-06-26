package com.viel.oto.data.book

import com.viel.oto.data.entity.BookmarkEntity
import kotlinx.coroutines.flow.Flow

/**
 * Application-facing user bookmark seam.
 *
 * Keeps bookmark reads and writes together while preventing player and notification bookmark actions from
 * inheriting catalog search, chapter replacement, or metadata editing capabilities.
 */
interface BookmarkGateway {
    /**
     * Reactive bookmark flow.
     *
     * Reactively monitors bookmark entries created by the user for the target audiobook.
     */
    fun getBookmarks(bookId: String): Flow<List<BookmarkEntity>>

    /**
     * User marking action.
     *
     * Appends a new bookmark at the specified global position offset.
     */
    suspend fun addBookmark(bookId: String, position: Long, title: String)

    /**
     * User edit action.
     *
     * Overwrites details such as notes or titles of a specific bookmark record.
     */
    suspend fun updateBookmark(bookmark: BookmarkEntity)

    /**
     * User removal.
     *
     * Permanently deletes a specific bookmark record from the database.
     */
    suspend fun deleteBookmark(bookmark: BookmarkEntity)
}
