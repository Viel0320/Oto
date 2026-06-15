package com.viel.aplayer.application.usecase

import com.viel.aplayer.application.download.ManualDownloadCleanupGateway
import com.viel.aplayer.application.playback.PlaybackStopper
import com.viel.aplayer.data.gateway.BookAvailabilityGateway
import com.viel.aplayer.data.gateway.BookDeletionGateway
import com.viel.aplayer.data.gateway.LibraryResourceCleanupGateway
import com.viel.aplayer.data.gateway.RemotePlaybackCleanupGateway
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Book Management Use Case (Coordinates book-scoped destructive workflows)
 * Keeps single-book deletion cleanup in one application boundary so cover cache removal, manual download cleanup,
 * playback shutdown, and soft deletion cannot drift across separate UI or data-layer callers.
 */
class BookManagementUseCase(
    private val playbackStopper: PlaybackStopper,
    private val bookAvailabilityGateway: BookAvailabilityGateway,
    private val bookDeletionGateway: BookDeletionGateway,
    private val remotePlaybackCleanupGateway: RemotePlaybackCleanupGateway,
    private val manualDownloadCleanupGateway: ManualDownloadCleanupGateway,
    private val libraryResourceCleanupGateway: LibraryResourceCleanupGateway
) {
    /**
     * Delete Book (Clear book-owned resources before marking the row as deleted)
     * Stops active playback first, probes whether the source file still exists for user feedback, removes derived cover
     * and manual-download cache state, then performs the logical soft delete.
     */
    suspend fun deleteBook(bookId: String): Boolean = withContext(Dispatchers.IO) {
        playbackStopper.stopIfPlaying(bookId)
        val fileExists = bookAvailabilityGateway.checkPrimaryAudioFileExistsWithoutStatusRefresh(bookId)
        libraryResourceCleanupGateway.clearBookCoverCache(bookId)
        manualDownloadCleanupGateway.deleteDownload(bookId)
        bookDeletionGateway.deleteBook(bookId)
        remotePlaybackCleanupGateway.deletePlaybackStateForBook(bookId)
        fileExists
    }
}
