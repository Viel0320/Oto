package com.viel.oto.data.cover

import com.viel.oto.data.entity.BookFileEntity

/**
 * Media-backed artwork recovery source used by the cover self-heal engine.
 *
 * The data layer owns the decision to update persisted cover paths, while this contract hides VFS traversal,
 * sidecar selection, and embedded artwork parsing behind two book-file based operations.
 */
interface CoverRecoveryArtworkSource {
    /**
     * Attempts to recover embedded artwork from the given audio file.
     */
    suspend fun extractEmbeddedCover(bookId: String, file: BookFileEntity): CoverImageResult

    /**
     * Attempts to recover a directory sidecar image related to the given primary audio file.
     */
    suspend fun extractSidecarCover(primaryFile: BookFileEntity): CoverImageResult
}
