package com.viel.aplayer.ui.player.components.bookmarks

import com.viel.aplayer.data.entity.BookmarkEntity
import com.viel.aplayer.data.gateway.BookQueryGateway
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Bookmark business logic manager (BookmarkManager).
 *
 * Responsible for bookmark addition, deletion, and modification operations.
 */
class BookmarkManager(
    private val repository: BookQueryGateway,
    private val scope: CoroutineScope
) {
    /**
     * Add a bookmark.
     */
    fun addBookmark(bookId: String, position: Long, title: String) {
        scope.launch {
            repository.addBookmark(bookId, position, title)
        }
    }

    /**
     * Delete a bookmark.
     */
    fun deleteBookmark(bookmark: BookmarkEntity) {
        scope.launch {
            repository.deleteBookmark(bookmark)
        }
    }

    /**
     * Update a bookmark title.
     */
    fun updateBookmark(bookmark: BookmarkEntity, newTitle: String) {
        scope.launch {
            repository.updateBookmark(bookmark.copy(title = newTitle))
        }
    }
}