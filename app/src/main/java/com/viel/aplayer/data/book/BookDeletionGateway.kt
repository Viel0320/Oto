package com.viel.aplayer.data.book

/**
 * Book Deletion Gateway (Application-facing soft deletion seam)
 *
 * Isolates logical audiobook deletion from catalog reads and metadata edits so destructive workflows request
 * only the command they need after playback and availability guards have run.
 */
interface BookDeletionGateway {
    /**
     * Logical Audiobook Deletion (Soft delete command)
     *
     * Soft deletes the audiobook record from the database, retaining identifiers for future scan comparisons.
     */
    suspend fun deleteBook(bookId: String)
}
