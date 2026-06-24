package com.viel.oto.ui.player.components.bookmarks

import com.viel.oto.application.library.player.PlayerBookmarkCommands
import com.viel.oto.application.library.player.PlayerBookmarkItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Routes player bookmark mutations through the player-scene command surface.
 */
class BookmarkManager(
    private val bookmarkCommands: PlayerBookmarkCommands,
    private val scope: CoroutineScope
) {
    fun addBookmark(bookId: String, position: Long, title: String) {
        scope.launch {
            bookmarkCommands.addBookmark(bookId, position, title)
        }
    }

    fun deleteBookmark(bookmark: PlayerBookmarkItem) {
        scope.launch {
            bookmarkCommands.deleteBookmark(bookmark)
        }
    }

    fun updateBookmark(bookmark: PlayerBookmarkItem, newTitle: String) {
        scope.launch {
            bookmarkCommands.updateBookmark(bookmark, newTitle)
        }
    }
}
