package com.viel.aplayer.application.library.player

/**
 * Player-scene bookmark mutation surface.
 * Keeps bookmark creation, editing, and deletion behind a compact interface consumed by BookmarkManager.
 */
interface PlayerBookmarkCommands {
    /**
     * Persist a user bookmark at the current global position.
     * Accepts raw UI input so the command adapter can remain the single route into bookmark persistence.
     */
    suspend fun addBookmark(bookId: String, position: Long, title: String)

    /**
     * Persist an edited bookmark label.
     * Receives the selected bookmark and new title while the module owns the copy-and-save mutation.
     */
    suspend fun updateBookmark(bookmark: PlayerBookmarkItem, newTitle: String)

    /**
     * Remove a selected bookmark.
     * Delegates deletion through the player scene command boundary instead of the broad library transition facade.
     */
    suspend fun deleteBookmark(bookmark: PlayerBookmarkItem)
}
