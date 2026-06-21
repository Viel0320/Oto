package com.viel.aplayer.application.usecase

import com.viel.aplayer.application.download.ManualDownloadCleanupGateway
import com.viel.aplayer.application.playback.PlaybackStopper
import com.viel.aplayer.data.availability.BookAvailabilityGateway
import com.viel.aplayer.data.book.BookDeletionGateway
import com.viel.aplayer.data.cleanup.RemotePlaybackCleanupGateway
import com.viel.aplayer.data.entity.BookFileEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks cleanup-before-soft-delete sequencing.
 * Verifies single-book deletion stops active playback, gathers feedback state, clears cover and manual-download resources,
 * and only then marks the book as deleted.
 */
class BookManagementUseCaseTest {

    @Test
    fun deletingCurrentlyPlayingBookStopsBeforeCleanupAndSoftDelete() = runBlocking {
        val events = mutableListOf<String>()
        val useCase = useCaseFor(currentBookId = BOOK_ID, fileExists = true, events = events)

        val fileExists = useCase.deleteBook(BOOK_ID)

        assertTrue(fileExists)
        assertEquals(
            listOf(
                "stopPlayback",
                "checkPrimaryAudioFile:$BOOK_ID",
                "deleteManualDownload:$BOOK_ID",
                "deleteBook:$BOOK_ID",
                "deletePlaybackStateForBook:$BOOK_ID"
            ),
            events
        )
    }

    @Test
    fun deletingNonCurrentBookDoesNotStopPlayback() = runBlocking {
        val events = mutableListOf<String>()
        val useCase = useCaseFor(currentBookId = "other-book", fileExists = false, events = events)

        val fileExists = useCase.deleteBook(BOOK_ID)

        assertFalse(fileExists)
        assertEquals(
            listOf(
                "checkPrimaryAudioFile:$BOOK_ID",
                "deleteManualDownload:$BOOK_ID",
                "deleteBook:$BOOK_ID",
                "deletePlaybackStateForBook:$BOOK_ID"
            ),
            events
        )
    }

    @Test
    fun deletingBookClearsDownloadBeforeSoftDelete() = runBlocking {
        val events = mutableListOf<String>()
        val useCase = useCaseFor(
            currentBookId = null,
            fileExists = true,
            events = events
        )

        useCase.deleteBook(BOOK_ID)

        assertEquals(
            listOf(
                "checkPrimaryAudioFile:$BOOK_ID",
                "deleteManualDownload:$BOOK_ID",
                "deleteBook:$BOOK_ID",
                "deletePlaybackStateForBook:$BOOK_ID"
            ),
            events
        )
    }

    /**
     * Wire only seams needed by the destructive book workflow.
     * The fake collaborators record call order so tests protect resource cleanup from sliding behind the soft-delete command.
     * Note: libraryResourceCleanupGateway has been removed because cover cache cleanup was refactored out to self-healing.
     */
    private fun useCaseFor(
        currentBookId: String?,
        fileExists: Boolean,
        events: MutableList<String>
    ): BookManagementUseCase {
        return BookManagementUseCase(
            playbackStopper = RecordingPlaybackStopper(currentBookId, events),
            bookAvailabilityGateway = RecordingBookAvailabilityGateway(fileExists, events),
            bookDeletionGateway = RecordingBookDeletionGateway(events),
            remotePlaybackCleanupGateway = RecordingRemotePlaybackCleanupGateway(events),
            manualDownloadCleanupGateway = RecordingManualDownloadCleanupGateway(events)
        )
    }

    private class RecordingPlaybackStopper(
        override val currentPlayingBookId: String?,
        private val events: MutableList<String>
    ) : PlaybackStopper {
        override suspend fun stopPlayback() {
            events += "stopPlayback"
        }
    }

    private class RecordingBookAvailabilityGateway(
        private val fileExists: Boolean,
        private val events: MutableList<String>
    ) : BookAvailabilityGateway {
        override suspend fun checkPrimaryAudioFileExistsWithoutStatusRefresh(bookId: String): Boolean {
            events += "checkPrimaryAudioFile:$bookId"
            return fileExists
        }

        override suspend fun refreshDetailAvailabilityStatus(bookId: String): Boolean = unexpected("refreshDetailAvailabilityStatus")
        override suspend fun refreshCurrentPlaybackFileAvailabilityStatus(bookId: String): Boolean = unexpected("refreshCurrentPlaybackFileAvailabilityStatus")
        override suspend fun refreshPlaybackFileUnavailableStatus(bookId: String, queueIndex: Int): Unit = unexpected("refreshPlaybackFileUnavailableStatus")
        override suspend fun findNextAvailablePlaybackFileAndRefreshStatus(bookId: String, afterQueueIndex: Int): Pair<Int, BookFileEntity>? = unexpected("findNextAvailablePlaybackFileAndRefreshStatus")
    }

    private class RecordingBookDeletionGateway(
        private val events: MutableList<String>
    ) : BookDeletionGateway {
        override suspend fun deleteBook(bookId: String) {
            events += "deleteBook:$bookId"
        }
    }

    private class RecordingRemotePlaybackCleanupGateway(
        private val events: MutableList<String>
    ) : RemotePlaybackCleanupGateway {
        override suspend fun deletePlaybackStateForBook(bookId: String) {
            events += "deletePlaybackStateForBook:$bookId"
        }
    }

    private class RecordingManualDownloadCleanupGateway(
        private val events: MutableList<String>
    ) : ManualDownloadCleanupGateway {
        override suspend fun deleteDownload(bookId: String) {
            events += "deleteManualDownload:$bookId"
        }
    }

    private companion object {
        private const val BOOK_ID = "book-id"

        private fun unexpected(methodName: String): Nothing {
            error("Unexpected gateway call in BookManagementUseCaseTest: $methodName")
        }
    }
}
