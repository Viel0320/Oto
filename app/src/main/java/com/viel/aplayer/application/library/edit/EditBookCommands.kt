package com.viel.aplayer.application.library.edit

/**
 * Edit Book Commands (Edit-scene metadata mutation surface)
 * Groups text metadata updates and custom cover saves behind the edit scene boundary.
 */
interface EditBookCommands {
    /**
     * Update Text Metadata (Persist edited descriptive fields)
     * Receives trimmed values from EditBookViewModel and delegates the exact persistence call through the module.
     */
    suspend fun updateBookDetails(
        id: String,
        title: String,
        author: String,
        narrator: String,
        description: String,
        year: String,
        series: String
    )

    /**
     * Save Custom Cover (Persist a user-selected cover image)
     * Keeps cover asset writes separate from text metadata so EditBookViewModel does not need a broad library dependency.
     */
    suspend fun saveCustomCover(bookId: String, tempCoverPath: String)
}
