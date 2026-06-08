package com.viel.aplayer.application.usecase

import android.net.Uri
import com.viel.aplayer.application.playback.PlaybackStopper
import com.viel.aplayer.data.db.AudiobookSchema
import com.viel.aplayer.data.entity.BookEntity
import com.viel.aplayer.data.entity.BookFileEntity
import com.viel.aplayer.data.entity.BookWithProgress
import com.viel.aplayer.data.entity.BookmarkEntity
import com.viel.aplayer.data.entity.ChapterEntity
import com.viel.aplayer.data.entity.ChapterWithBookFile
import com.viel.aplayer.data.entity.LibraryRootEntity
import com.viel.aplayer.data.gateway.BookCatalogGateway
import com.viel.aplayer.data.gateway.BookDeletionGateway
import com.viel.aplayer.data.gateway.BookMetadataGateway
import com.viel.aplayer.data.gateway.BookmarkGateway
import com.viel.aplayer.data.gateway.ChapterGateway
import com.viel.aplayer.data.gateway.LibraryRootGateway
import com.viel.aplayer.library.availability.LibraryRootAvailabilityUpdate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Delete Library Root Use Case Test (Locks root deletion playback-safety policy)
 * Verifies active playback stops when it belongs to the deleted root and data cleanup still runs after playback lookup failures.
 */
class DeleteLibraryRootUseCaseTest {

    @Test
    fun currentPlayingBookUnderDeletedRootStopsBeforeRootDataDeletion() = runBlocking {
        val events = mutableListOf<String>()
        val root = root(ROOT_ID)
        val useCase = useCaseFor(
            currentBookId = BOOK_ID,
            bookLookup = BookLookup.Result(book(rootId = ROOT_ID)),
            events = events,
            roots = listOf(root)
        )

        val playbackStopped = useCase.invoke(ROOT_ID)

        assertTrue(playbackStopped)
        assertEquals(
            listOf("getAllRootsOnce", "getBookById:$BOOK_ID", "stopPlayback", "deleteLibraryRootDataOnly:$ROOT_ID"),
            events
        )
    }

    @Test
    fun currentBookLookupFailureStillDeletesRootData() = runBlocking {
        val events = mutableListOf<String>()
        val root = root(ROOT_ID)
        val useCase = useCaseFor(
            currentBookId = BOOK_ID,
            bookLookup = BookLookup.Failure(IllegalStateException("lookup failed")),
            events = events,
            roots = listOf(root)
        )

        val playbackStopped = useCase.invoke(ROOT_ID)

        assertFalse(playbackStopped)
        assertEquals(
            listOf("getAllRootsOnce", "getBookById:$BOOK_ID", "deleteLibraryRootDataOnly:$ROOT_ID"),
            events
        )
    }

    @Test
    fun rootIdDeletionRehydratesRootBeforeDeletingData() = runBlocking {
        val events = mutableListOf<String>()
        val useCase = useCaseFor(
            currentBookId = null,
            bookLookup = BookLookup.Result(null),
            events = events,
            roots = listOf(root(ROOT_ID))
        )

        val playbackStopped = useCase.invoke(ROOT_ID)

        assertFalse(playbackStopped)
        assertEquals(
            listOf("getAllRootsOnce", "deleteLibraryRootDataOnly:$ROOT_ID"),
            events
        )
    }

    private fun useCaseFor(
        currentBookId: String?,
        bookLookup: BookLookup,
        events: MutableList<String>,
        roots: List<LibraryRootEntity> = emptyList()
    ): DeleteLibraryRootUseCase {
        // Delete Root Fixture (Wire playback, book lookup, and root deletion seams only)
        // Recording fakes make the root-deletion safety order explicit without using Room or media runtime classes.
        return DeleteLibraryRootUseCase(
            playbackStopper = RecordingPlaybackStopper(currentBookId, events),
            // Catalog Gateway Fixture (Root deletion only needs active-book lookup before deleting the root)
            // Passing the fake through the catalog slot verifies the use case no longer receives metadata or bookmark commands.
            bookCatalogGateway = RecordingBookQueryGateway(bookLookup, events),
            libraryRootGateway = RecordingLibraryRootGateway(events, roots)
        )
    }

    private fun root(id: String): LibraryRootEntity {
        // Root Fixture (Create the minimum persisted root shape needed by deletion policy)
        // Source fields use stable schema constants so the test stays aligned with production entities.
        return LibraryRootEntity(
            id = id,
            sourceType = AudiobookSchema.LibrarySourceType.SAF,
            sourceUri = "content://root",
            displayName = "Library Root"
        )
    }

    private fun book(rootId: String): BookEntity {
        // Book Fixture (Create only the active book ownership fields relevant to root deletion)
        // The use case checks rootId only, so title and sourceType are deterministic filler values.
        return BookEntity(
            id = BOOK_ID,
            rootId = rootId,
            sourceType = AudiobookSchema.LibrarySourceType.SAF,
            title = "Book"
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

    private class RecordingBookQueryGateway(
        private val lookup: BookLookup,
        private val events: MutableList<String>
    ) : BookCatalogGateway,
        BookMetadataGateway,
        BookmarkGateway,
        ChapterGateway,
        BookDeletionGateway {
        override val audiobooks: Flow<List<BookWithProgress>> = flowOf(emptyList())

        override suspend fun getBookById(id: String): BookEntity? {
            events += "getBookById:$id"
            return when (lookup) {
                is BookLookup.Result -> lookup.book
                is BookLookup.Failure -> throw lookup.throwable
            }
        }

        override fun observeBookById(id: String): Flow<BookEntity?> = unexpected("observeBookById")
        override fun searchAudiobooks(query: String): Flow<List<BookWithProgress>> = unexpected("searchAudiobooks")
        override fun filterByYear(year: String): Flow<List<BookWithProgress>> = unexpected("filterByYear")
        override fun filterByAuthor(author: String): Flow<List<BookWithProgress>> = unexpected("filterByAuthor")
        override fun filterByAuthorLimited(author: String, excludeId: String, limit: Int): Flow<List<BookWithProgress>> = unexpected("filterByAuthorLimited")
        override fun filterByNarrator(narrator: String): Flow<List<BookWithProgress>> = unexpected("filterByNarrator")
        override fun filterByNarratorLimited(narrator: String, excludeId: String, limit: Int): Flow<List<BookWithProgress>> = unexpected("filterByNarratorLimited")
        override fun getRecentlyAdded(limit: Int): Flow<List<BookWithProgress>> = unexpected("getRecentlyAdded")
        override fun getRecentlyAddedExclusive(currentId: String, authors: List<String>, narrators: List<String>, limit: Int): Flow<List<BookWithProgress>> = unexpected("getRecentlyAddedExclusive")
        override suspend fun deleteBook(bookId: String): Unit = unexpected("deleteBook")
        override suspend fun updateBookReadStatus(bookId: String, readStatus: String): Unit = unexpected("updateBookReadStatus")
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

    private class RecordingLibraryRootGateway(
        private val events: MutableList<String>,
        private val roots: List<LibraryRootEntity>
    ) : LibraryRootGateway {
        override suspend fun deleteLibraryRootDataOnly(root: LibraryRootEntity) {
            events += "deleteLibraryRootDataOnly:${root.id}"
        }

        override fun observeLibraryRoots(): Flow<List<LibraryRootEntity>> = unexpected("observeLibraryRoots")
        override fun getCachedLibraryRoots(): List<LibraryRootEntity> = unexpected("getCachedLibraryRoots")
        override suspend fun getAllRootsOnce(): List<LibraryRootEntity> {
            // RootId Deletion Rehydration Fake (Expose persisted roots only for the rootId overload)
            // Recording this call verifies presentation callers no longer need to pass a LibraryRootEntity into the use case.
            events += "getAllRootsOnce"
            return roots
        }
        override suspend fun setLibraryRoot(uri: Uri): LibraryRootEntity = unexpected("setLibraryRoot")
        override suspend fun addWebDavLibraryRoot(url: String, username: String, password: String, displayName: String, basePath: String): LibraryRootEntity = unexpected("addWebDavLibraryRoot")
        override suspend fun addAbsLibraryRoot(credentialId: String, libraryId: String, displayName: String): LibraryRootEntity = unexpected("addAbsLibraryRoot")
        override fun addLibraryRootAndScheduleSync(uri: Uri, trigger: String): Unit = unexpected("addLibraryRootAndScheduleSync")
        override fun addWebDavLibraryRootAndScheduleSync(url: String, username: String, password: String, displayName: String, basePath: String, trigger: String): Unit = unexpected("addWebDavLibraryRootAndScheduleSync")
        override suspend fun refreshLibraryRootStatuses(): Unit = unexpected("refreshLibraryRootStatuses")
        override suspend fun refreshLibraryRootStatus(rootId: String): LibraryRootAvailabilityUpdate? = unexpected("refreshLibraryRootStatus")
        override suspend fun updateSafLibraryRoot(id: String, newUri: Uri): LibraryRootEntity = unexpected("updateSafLibraryRoot")
        override suspend fun updateWebDavLibraryRoot(id: String, url: String, username: String, password: String, displayName: String, basePath: String): LibraryRootEntity = unexpected("updateWebDavLibraryRoot")
        override suspend fun updateAbsLibraryRoot(id: String, credentialId: String, libraryId: String, displayName: String): LibraryRootEntity = unexpected("updateAbsLibraryRoot")
    }

    private sealed class BookLookup {
        data class Result(val book: BookEntity?) : BookLookup()
        data class Failure(val throwable: Throwable) : BookLookup()
    }

    private companion object {
        private const val BOOK_ID = "book-id"
        private const val ROOT_ID = "root-id"

        private fun unexpected(methodName: String): Nothing {
            error("Unexpected gateway call in DeleteLibraryRootUseCaseTest: $methodName")
        }
    }
}
