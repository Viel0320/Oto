package com.viel.aplayer.application.usecase

import com.viel.aplayer.application.playback.PlaybackStopper
import com.viel.aplayer.data.db.AudiobookSchema
import com.viel.aplayer.data.entity.BookEntity
import com.viel.aplayer.data.entity.BookFileEntity
import com.viel.aplayer.data.entity.BookWithProgress
import com.viel.aplayer.data.entity.BookmarkEntity
import com.viel.aplayer.data.entity.ChapterEntity
import com.viel.aplayer.data.entity.ChapterWithBookFile
import com.viel.aplayer.data.gateway.BookAvailabilityGateway
import com.viel.aplayer.data.gateway.BookCatalogGateway
import com.viel.aplayer.data.gateway.BookDeletionGateway
import com.viel.aplayer.data.gateway.BookMetadataGateway
import com.viel.aplayer.data.gateway.RemotePlaybackCleanupGateway
import com.viel.aplayer.data.gateway.BookmarkGateway
import com.viel.aplayer.data.gateway.ChapterGateway
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Delete Book Use Case Test (Locks playback-stop coordination at the deletion seam)
 * Verifies book deletion stops only active playback and keeps file probing before database deletion.
 */
class DeleteBookUseCaseTest {

    @Test
    fun deletingCurrentlyPlayingBookStopsBeforeFileProbeAndDelete() = runBlocking {
        val events = mutableListOf<String>()
        val useCase = useCaseFor(currentBookId = BOOK_ID, fileExists = true, events = events)

        val fileExists = useCase.invoke(BOOK_ID)

        assertTrue(fileExists)
        assertEquals(
            listOf(
                "stopPlayback",
                "checkPrimaryAudioFile:$BOOK_ID",
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

        val fileExists = useCase.invoke(BOOK_ID)

        assertFalse(fileExists)
        assertEquals(
            listOf(
                "checkPrimaryAudioFile:$BOOK_ID",
                "deleteBook:$BOOK_ID",
                "deletePlaybackStateForBook:$BOOK_ID"
            ),
            events
        )
    }

    private fun useCaseFor(
        currentBookId: String?,
        fileExists: Boolean,
        events: MutableList<String>
    ): DeleteBookUseCase {
        // Delete Book Fixture (Wire only the three seams used by destructive book deletion)
        // Fakes record call order so the test protects the stop-before-delete safety policy.
        return DeleteBookUseCase(
            playbackStopper = RecordingPlaybackStopper(currentBookId, events),
            bookAvailabilityGateway = RecordingBookAvailabilityGateway(fileExists, events),
            // Deletion Gateway Fixture (Pass the fake through the narrowed destructive command slot)
            // The use case no longer receives catalog, chapter, bookmark, or metadata methods just to soft-delete a book.
            bookDeletionGateway = RecordingBookQueryGateway(events),
            // Remote Playback Cleanup Fixture (Expose only book-scoped remote playback state pruning)
            // Recording this call protects the delete-after-soft-delete ordering for stale ABS session and pending progress rows.
            remotePlaybackCleanupGateway = RecordingRemotePlaybackCleanupGateway(events)
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

    private class RecordingBookQueryGateway(
        private val events: MutableList<String>
    ) : BookCatalogGateway,
        BookMetadataGateway,
        BookmarkGateway,
        ChapterGateway,
        BookDeletionGateway {
        override val audiobooks: Flow<List<BookWithProgress>> = flowOf(emptyList())

        override suspend fun deleteBook(bookId: String) {
            events += "deleteBook:$bookId"
        }

        override suspend fun getBookById(id: String): BookEntity? = unexpected("getBookById")
        override fun observeBookById(id: String): Flow<BookEntity?> = unexpected("observeBookById")
        override fun searchAudiobooks(query: String): Flow<List<BookWithProgress>> = unexpected("searchAudiobooks")
        override fun filterByYear(year: String): Flow<List<BookWithProgress>> = unexpected("filterByYear")
        override fun filterByAuthor(author: String): Flow<List<BookWithProgress>> = unexpected("filterByAuthor")
        override fun filterByAuthorLimited(author: String, excludeId: String, limit: Int): Flow<List<BookWithProgress>> = unexpected("filterByAuthorLimited")
        override fun filterByNarrator(narrator: String): Flow<List<BookWithProgress>> = unexpected("filterByNarrator")
        override fun filterByNarratorLimited(narrator: String, excludeId: String, limit: Int): Flow<List<BookWithProgress>> = unexpected("filterByNarratorLimited")
        override fun getRecentlyAdded(limit: Int): Flow<List<BookWithProgress>> = unexpected("getRecentlyAdded")
        override fun getRecentlyAddedExclusive(currentId: String, authors: List<String>, narrators: List<String>, limit: Int): Flow<List<BookWithProgress>> = unexpected("getRecentlyAddedExclusive")
        override suspend fun updateBookReadStatus(bookId: String, readStatus: AudiobookSchema.ReadStatus): Unit = unexpected("updateBookReadStatus")
        override suspend fun updateBookDetails(id: String, title: String, author: String, narrator: String, description: String, year: String, series: String): Unit = unexpected("updateBookDetails")
        override suspend fun getFilesForBookSync(bookId: String): List<BookFileEntity> = unexpected("getFilesForBookSync")
        override suspend fun getAllFilesForBookSync(bookId: String): List<BookFileEntity> = unexpected("getAllFilesForBookSync")
        override fun updateMetadata(bookId: String, title: String?, author: String?, narrator: String?, description: String?, duration: Long): Unit = unexpected("updateMetadata")
        override fun getChapters(bookId: String): Flow<List<ChapterWithBookFile>> = unexpected("getChapters")
        override suspend fun getChaptersForBookSync(bookId: String): List<ChapterWithBookFile> = unexpected("getChaptersForBookSync")
        override fun saveChapters(bookId: String, chapters: List<ChapterEntity>): Unit = unexpected("saveChapters")
        override fun getBookmarks(bookId: String): Flow<List<BookmarkEntity>> = unexpected("getBookmarks")
        override suspend fun addBookmark(bookId: String, position: Long, title: String): Unit = unexpected("addBookmark")
        override suspend fun updateBookmark(bookmark: BookmarkEntity): Unit = unexpected("updateBookmark")
        override suspend fun deleteBookmark(bookmark: BookmarkEntity): Unit = unexpected("deleteBookmark")
    }

    private class RecordingRemotePlaybackCleanupGateway(
        private val events: MutableList<String>
    ) : RemotePlaybackCleanupGateway {
        override suspend fun deletePlaybackStateForBook(bookId: String) {
            events += "deletePlaybackStateForBook:$bookId"
        }
    }

    private companion object {
        private const val BOOK_ID = "book-id"

        private fun unexpected(methodName: String): Nothing {
            error("Unexpected gateway call in DeleteBookUseCaseTest: $methodName")
        }
    }
}
