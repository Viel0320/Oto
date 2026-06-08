package com.viel.aplayer.ui.player.components.bookmarks

import com.viel.aplayer.application.library.player.PlayerBookmarkCommands
import com.viel.aplayer.application.library.player.PlayerBookmarkItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Bookmark business logic manager (BookmarkManager).
 *
 * Responsible for bookmark addition, deletion, and modification operations.
 */
class BookmarkManager(
    // Player Bookmark Command Access (Routes player-screen bookmark mutations through the scene command surface)
    // This prevents UI helpers from depending on the broad library transition facade while preserving the existing bookmark behavior.
    private val bookmarkCommands: PlayerBookmarkCommands,
    private val scope: CoroutineScope
) {
    /**
     * Add a bookmark.
     */
    fun addBookmark(bookId: String, position: Long, title: String) {
        scope.launch {
            bookmarkCommands.addBookmark(bookId, position, title)
        }
    }

    /**
     * Delete a bookmark.
     */
    fun deleteBookmark(bookmark: PlayerBookmarkItem) {
        scope.launch {
            bookmarkCommands.deleteBookmark(bookmark)
        }
    }

    /**
     * Update a bookmark title.
     */
    fun updateBookmark(bookmark: PlayerBookmarkItem, newTitle: String) {
        scope.launch {
            bookmarkCommands.updateBookmark(bookmark, newTitle)
        }
    }
}
