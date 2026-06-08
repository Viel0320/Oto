package com.viel.aplayer.data.gateway

import com.viel.aplayer.data.entity.BookmarkEntity
import kotlinx.coroutines.flow.Flow

/**
 * Bookmark Gateway (Application-facing user bookmark seam)
 *
 * Keeps bookmark reads and writes together while preventing player and notification bookmark actions from
 * inheriting catalog search, chapter replacement, or metadata editing capabilities.
 */
interface BookmarkGateway {
    /**
     * Observe User Bookmarks (Reactive bookmark flow)
     *
     * Reactively monitors bookmark entries created by the user for the target audiobook.
     */
    fun getBookmarks(bookId: String): Flow<List<BookmarkEntity>>

    /**
     * Add Bookmark Entry (User marking action)
     *
     * Appends a new bookmark at the specified global position offset.
     */
    suspend fun addBookmark(bookId: String, position: Long, title: String)

    /**
     * Update Bookmark Details (User edit action)
     *
     * Overwrites details such as notes or titles of a specific bookmark record.
     */
    suspend fun updateBookmark(bookmark: BookmarkEntity)

    /**
     * Delete Bookmark Record (User removal)
     *
     * Permanently deletes a specific bookmark record from the database.
     */
    suspend fun deleteBookmark(bookmark: BookmarkEntity)
}
