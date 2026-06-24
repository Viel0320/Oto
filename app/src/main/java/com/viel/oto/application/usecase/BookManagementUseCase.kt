package com.viel.oto.application.usecase

import com.viel.oto.application.download.ManualDownloadCleanupGateway
import com.viel.oto.application.playback.PlaybackStopper
import com.viel.oto.data.availability.BookAvailabilityGateway
import com.viel.oto.data.book.BookDeletionGateway
import com.viel.oto.data.cleanup.RemotePlaybackCleanupGateway
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Coordinates book-scoped destructive workflows.
 * Keeps single-book deletion cleanup in one application boundary so cover cache removal, manual download cleanup,
 * playback shutdown, and soft deletion cannot drift across separate UI or data-layer callers.
 */
class BookManagementUseCase(
    private val playbackStopper: PlaybackStopper,
    private val bookAvailabilityGateway: BookAvailabilityGateway,
    private val bookDeletionGateway: BookDeletionGateway,
    private val remotePlaybackCleanupGateway: RemotePlaybackCleanupGateway,
    private val manualDownloadCleanupGateway: ManualDownloadCleanupGateway
) {
    /**
     * Clear book-owned resources before marking the row as deleted.
     * Stops active playback first, probes whether the source file still exists for user feedback, removes derived cover
     * and manual-download cache state, then performs the logical soft delete.
     */
    suspend fun deleteBook(bookId: String): Boolean = withContext(Dispatchers.IO) {
        playbackStopper.stopIfPlaying(bookId)
        val fileExists = bookAvailabilityGateway.checkPrimaryAudioFileExistsWithoutStatusRefresh(bookId)
        manualDownloadCleanupGateway.deleteDownload(bookId)
        bookDeletionGateway.deleteBook(bookId)
        remotePlaybackCleanupGateway.deletePlaybackStateForBook(bookId)
        fileExists
    }
}
