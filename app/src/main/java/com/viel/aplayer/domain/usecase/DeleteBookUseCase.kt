// Package Relocation: Define the usecase within the standardized domain namespace to align architectural boundaries.
package com.viel.aplayer.domain.usecase

import com.viel.aplayer.data.gateway.BookAvailabilityGateway
import com.viel.aplayer.data.gateway.BookQueryGateway
import com.viel.aplayer.media.PlaybackManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Cross-Domain Coordination Use Case (DeleteBookUseCase)
 * 
 * Safely removes a book from the media library, coordinating playback control 
 * and database pruning states.
 */
class DeleteBookUseCase(
    private val playbackManager: PlaybackManager,
    private val bookAvailabilityGateway: BookAvailabilityGateway,
    private val bookQueryGateway: BookQueryGateway
) {

    /**
     * Execute book deletion (Coordinating playback state and database cleaning)
     * 
     * @param bookId The ID of the audiobook to delete.
     * @return True if the book's primary audio file exists before deletion, false otherwise.
     */
    suspend fun invoke(bookId: String): Boolean = withContext(Dispatchers.IO) {
        // Playback Termination Intercept (Stop active stream service if viewed item is deleted)
        // Halts active playback immediately if the user attempts to delete the book currently playing.
        val currentPlayingId = playbackManager.currentPlayingBookId
        if (currentPlayingId == bookId) {
            playbackManager.stopPlayback()
        }

        // Pure File Existence Probe (Checks physical track persistence without refreshing availability state)
        // DeleteBookUseCase only needs to know whether a physical file may remain, so it uses the explicitly non-mutating facade method.
        val fileExists = bookAvailabilityGateway.checkPrimaryAudioFileExistsWithoutStatusRefresh(bookId)

        // Purge records (To erase DB entities and cascade remove metadata caches)
        // Invokes database deletion routing to purge Room records and delete matching cover images.
        bookQueryGateway.deleteBook(bookId)

        fileExists
    }
}
