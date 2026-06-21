package com.viel.aplayer.application.library.detail

/**
 * Scene-level detail mutation surface.
 * Keeps status-refreshing detail commands behind a narrow interface instead of routing ViewModel actions through a broad library facade.
 */
interface DetailBookCommands {
    /**
     * Reprobe and persist playability for the selected book.
     * Returns the current playable decision while hiding file status writes from the detail presentation layer.
     */
    suspend fun refreshAvailability(bookId: String): Boolean
}
