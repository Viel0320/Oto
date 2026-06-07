package com.viel.aplayer.ui.player.components.bookmarks

import com.viel.aplayer.data.LibraryFacade
import com.viel.aplayer.data.entity.BookmarkEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Bookmark business logic manager (BookmarkManager).
 *
 * Responsible for bookmark addition, deletion, and modification operations.
 */
class BookmarkManager(
    // UI Facade Bookmark Access (Routes player-screen bookmark mutations through the same high-level facade used by ViewModels)
    // This prevents UI helpers from depending directly on granular book-query gateways while preserving the existing bookmark behavior.
    private val libraryFacade: LibraryFacade,
    private val scope: CoroutineScope
) {
    /**
     * Add a bookmark.
     */
    fun addBookmark(bookId: String, position: Long, title: String) {
        scope.launch {
            libraryFacade.addBookmark(bookId, position, title)
        }
    }

    /**
     * Delete a bookmark.
     */
    fun deleteBookmark(bookmark: BookmarkEntity) {
        scope.launch {
            libraryFacade.deleteBookmark(bookmark)
        }
    }

    /**
     * Update a bookmark title.
     */
    fun updateBookmark(bookmark: BookmarkEntity, newTitle: String) {
        scope.launch {
            libraryFacade.updateBookmark(bookmark.copy(title = newTitle))
        }
    }
}
