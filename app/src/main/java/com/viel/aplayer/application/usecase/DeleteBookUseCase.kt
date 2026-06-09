// Package Relocation: Define the usecase within the standardized domain namespace to align architectural boundaries.
package com.viel.aplayer.application.usecase

import com.viel.aplayer.application.playback.PlaybackStopper
import com.viel.aplayer.data.gateway.BookAvailabilityGateway
import com.viel.aplayer.data.gateway.BookDeletionGateway
import com.viel.aplayer.data.gateway.RemotePlaybackCleanupGateway
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Cross-Domain Coordination Use Case (DeleteBookUseCase)
 * 
 * Safely removes a book from the media library, coordinating playback control 
 * and database pruning states.
 */
class DeleteBookUseCase(
    private val playbackStopper: PlaybackStopper,
    private val bookAvailabilityGateway: BookAvailabilityGateway,
    private val bookDeletionGateway: BookDeletionGateway,
    private val remotePlaybackCleanupGateway: RemotePlaybackCleanupGateway
) {

    /**
     * Execute book deletion (Coordinating playback state and database cleaning)
     * 
     * @param bookId The ID of the audiobook to delete.
     * @return True if the book's primary audio file exists before deletion, false otherwise.
     */
    suspend fun invoke(bookId: String): Boolean = withContext(Dispatchers.IO) {
        // Playback Stopper Guard (Stop active stream through the playback lifecycle seam)
        // The deletion use case only asks whether its target is active, leaving media runtime ownership inside MediaGraph.
        playbackStopper.stopIfPlaying(bookId)

        // Pure File Existence Probe (Checks physical track persistence without refreshing availability state)
        // DeleteBookUseCase only needs to know whether a physical file may remain, so it uses the explicitly non-mutating facade method.
        val fileExists = bookAvailabilityGateway.checkPrimaryAudioFileExistsWithoutStatusRefresh(bookId)

        // Purge records (To erase DB entities and cascade remove metadata caches)
        // Invokes database deletion routing to purge Room records and delete matching cover images.
        // Book Deletion Command (Use the destructive command seam only after guards complete)
        // Deletion does not need catalog filters, chapters, bookmarks, or metadata writes, so the use case depends on BookDeletionGateway.
        bookDeletionGateway.deleteBook(bookId)

        // Remote Playback Residue Cleanup (Prune remote session state after the local soft delete)
        // ABS sessions and pending progress are not cascaded by the book status update, so the book deletion workflow removes them explicitly.
        remotePlaybackCleanupGateway.deletePlaybackStateForBook(bookId)

        fileExists
    }
}
