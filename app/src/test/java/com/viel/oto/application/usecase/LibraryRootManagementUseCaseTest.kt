package com.viel.oto.application.usecase

import android.net.Uri
import com.viel.oto.application.download.ManualDownloadCleanupGateway
import com.viel.oto.application.playback.PlaybackStopper
import com.viel.oto.data.book.BookCatalogGateway
import com.viel.oto.data.book.BookRootInventoryGateway
import com.viel.oto.data.cleanup.LibraryResourceCleanupGateway
import com.viel.oto.data.db.AudiobookSchema
import com.viel.oto.data.entity.BookEntity
import com.viel.oto.data.entity.BookFileEntity
import com.viel.oto.data.entity.BookWithProgress
import com.viel.oto.data.entity.LibraryRootEntity
import com.viel.oto.data.root.LibraryRootGateway
import com.viel.oto.library.availability.LibraryRootAvailabilityUpdate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks root cleanup-before-cascade sequencing.
 * Verifies root deletion and ABS library switching clear root caches and book-level manual downloads before data-layer
 * cascade deletion can remove the rows needed to identify those resources.
 */
class LibraryRootManagementUseCaseTest {

    @Test
    fun deletingRootStopsOwnedPlaybackAndCleansResourcesBeforeCascadeDeletion() = runBlocking {
        val events = mutableListOf<String>()
        val useCase = useCaseFor(
            currentBookId = BOOK_ID,
            bookLookup = BookLookup.Result(book(rootId = ROOT_ID)),
            rootBookIds = listOf(BOOK_ID, SECOND_BOOK_ID),
            events = events,
            roots = listOf(root(ROOT_ID))
        )

        val playbackStopped = useCase.deleteLibraryRoot(ROOT_ID)

        assertTrue(playbackStopped)
        assertEquals(
            listOf(
                "getAllRootsOnce",
                "getBookById:$BOOK_ID",
                "stopPlayback",
                "clearRootDerivedCaches:$ROOT_ID",
                "getBookIdsByRootId:$ROOT_ID",
                "deleteManualDownload:$BOOK_ID",
                "deleteManualDownload:$SECOND_BOOK_ID",
                "deleteLibraryRootDataOnly:$ROOT_ID"
            ),
            events
        )
    }

    @Test
    fun playbackLookupFailureStillCleansResourcesAndDeletesRootData() = runBlocking {
        val events = mutableListOf<String>()
        val useCase = useCaseFor(
            currentBookId = BOOK_ID,
            bookLookup = BookLookup.Failure(IllegalStateException("lookup failed")),
            rootBookIds = listOf(BOOK_ID),
            events = events,
            roots = listOf(root(ROOT_ID))
        )

        val playbackStopped = useCase.deleteLibraryRoot(ROOT_ID)

        assertFalse(playbackStopped)
        assertEquals(
            listOf(
                "getAllRootsOnce",
                "getBookById:$BOOK_ID",
                "clearRootDerivedCaches:$ROOT_ID",
                "getBookIdsByRootId:$ROOT_ID",
                "deleteManualDownload:$BOOK_ID",
                "deleteLibraryRootDataOnly:$ROOT_ID"
            ),
            events
        )
    }

    @Test
    fun missingRootIdDoesNotRunCleanupOrDeletion() = runBlocking {
        val events = mutableListOf<String>()
        val useCase = useCaseFor(
            currentBookId = null,
            bookLookup = BookLookup.Result(null),
            rootBookIds = emptyList(),
            events = events,
            roots = emptyList()
        )

        val playbackStopped = useCase.deleteLibraryRoot(ROOT_ID)

        assertFalse(playbackStopped)
        assertEquals(listOf("getAllRootsOnce"), events)
    }

    @Test
    fun switchingAbsLibraryCleansOldRootResourcesBeforeGatewayUpdate() = runBlocking {
        val events = mutableListOf<String>()
        val useCase = useCaseFor(
            currentBookId = null,
            bookLookup = BookLookup.Result(null),
            rootBookIds = listOf(BOOK_ID),
            events = events,
            roots = listOf(absRoot(ROOT_ID, libraryId = "old-library"))
        )

        val updated = useCase.updateAbsLibraryRoot(
            id = ROOT_ID,
            credentialId = "new-credential",
            libraryId = "new-library",
            displayName = "New Library"
        )

        assertEquals("New Library", updated.displayName)
        assertEquals(
            listOf(
                "getAllRootsOnce",
                "clearRootDerivedCaches:$ROOT_ID",
                "getBookIdsByRootId:$ROOT_ID",
                "deleteManualDownload:$BOOK_ID",
                "updateAbsLibraryRoot:$ROOT_ID:new-library"
            ),
            events
        )
    }

    @Test
    fun updatingSameAbsLibraryDoesNotClearOldResources() = runBlocking {
        val events = mutableListOf<String>()
        val useCase = useCaseFor(
            currentBookId = null,
            bookLookup = BookLookup.Result(null),
            rootBookIds = listOf(BOOK_ID),
            events = events,
            roots = listOf(absRoot(ROOT_ID, libraryId = "same-library"))
        )

        useCase.updateAbsLibraryRoot(
            id = ROOT_ID,
            credentialId = "same-credential",
            libraryId = "same-library",
            displayName = "Renamed Library"
        )

        assertEquals(
            listOf(
                "getAllRootsOnce",
                "updateAbsLibraryRoot:$ROOT_ID:same-library"
            ),
            events
        )
    }

    /**
     * Wire only root deletion and ABS switch collaborators.
     * Fakes record call order so tests protect cleanup from moving behind cascade deletion or ABS root replacement.
     */
    private fun useCaseFor(
        currentBookId: String?,
        bookLookup: BookLookup,
        rootBookIds: List<String>,
        events: MutableList<String>,
        roots: List<LibraryRootEntity>
    ): LibraryRootManagementUseCase {
        return LibraryRootManagementUseCase(
            playbackStopper = RecordingPlaybackStopper(currentBookId, events),
            bookCatalogGateway = RecordingBookCatalogGateway(bookLookup, events),
            bookRootInventoryGateway = RecordingBookRootInventoryGateway(rootBookIds, events),
            libraryRootGateway = RecordingLibraryRootGateway(events, roots),
            manualDownloadCleanupGateway = RecordingManualDownloadCleanupGateway(events),
            libraryResourceCleanupGateway = RecordingLibraryResourceCleanupGateway(events)
        )
    }

    /**
     * Create the minimum persisted root shape needed by deletion policy.
     * Source fields use stable schema constants so tests stay aligned with production entities.
     */
    private fun root(id: String): LibraryRootEntity {
        return LibraryRootEntity(
            id = id,
            sourceType = AudiobookSchema.LibrarySourceType.SAF,
            sourceUri = "content://root",
            displayName = "Library Root"
        )
    }

    /**
     * Represent AudiobookShelf roots using basePath as the persisted library id.
     * The production root store stores the selected ABS library id in basePath, so switch tests compare that field.
     */
    private fun absRoot(id: String, libraryId: String): LibraryRootEntity {
        return LibraryRootEntity(
            id = id,
            sourceType = AudiobookSchema.LibrarySourceType.ABS,
            sourceUri = "https://abs.example",
            basePath = libraryId,
            credentialId = "credential",
            displayName = "ABS Library"
        )
    }

    /**
     * Create only the active book ownership fields relevant to root deletion.
     * LibraryRootManagementUseCase checks rootId only, so title and sourceType are deterministic filler values.
     */
    private fun book(rootId: String): BookEntity {
        return BookEntity(
            id = BOOK_ID,
            rootId = rootId,
            sourceType = AudiobookSchema.SourceType.SINGLE_AUDIO,
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

    private class RecordingBookCatalogGateway(
        private val lookup: BookLookup,
        private val events: MutableList<String>
    ) : BookCatalogGateway {
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
        override suspend fun getFilesForBookSync(bookId: String): List<BookFileEntity> = unexpected("getFilesForBookSync")
        override suspend fun getAllFilesForBookSync(bookId: String): List<BookFileEntity> = unexpected("getAllFilesForBookSync")
    }

    private class RecordingBookRootInventoryGateway(
        private val bookIds: List<String>,
        private val events: MutableList<String>
    ) : BookRootInventoryGateway {
        override suspend fun getBookIdsByRootId(rootId: String): List<String> {
            events += "getBookIdsByRootId:$rootId"
            return bookIds
        }
    }

    private class RecordingLibraryRootGateway(
        private val events: MutableList<String>,
        private val roots: List<LibraryRootEntity>
    ) : LibraryRootGateway {
        override suspend fun deleteLibraryRootDataOnly(root: LibraryRootEntity) {
            events += "deleteLibraryRootDataOnly:${root.id}"
        }

        override suspend fun updateAbsLibraryRoot(
            id: String,
            credentialId: String,
            libraryId: String,
            displayName: String
        ): LibraryRootEntity {
            events += "updateAbsLibraryRoot:$id:$libraryId"
            return LibraryRootEntity(
                id = id,
                sourceType = AudiobookSchema.LibrarySourceType.ABS,
                sourceUri = "https://abs.example",
                basePath = libraryId,
                credentialId = credentialId,
                displayName = displayName
            )
        }

        override fun observeLibraryRoots(): Flow<List<LibraryRootEntity>> = unexpected("observeLibraryRoots")
        override fun getCachedLibraryRoots(): List<LibraryRootEntity> = unexpected("getCachedLibraryRoots")
        override suspend fun getAllRootsOnce(): List<LibraryRootEntity> {
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
    }

    private class RecordingManualDownloadCleanupGateway(
        private val events: MutableList<String>
    ) : ManualDownloadCleanupGateway {
        override suspend fun deleteDownload(bookId: String) {
            events += "deleteManualDownload:$bookId"
        }
    }

    private class RecordingLibraryResourceCleanupGateway(
        private val events: MutableList<String>
    ) : LibraryResourceCleanupGateway {
        override suspend fun clearBookCoverCache(bookId: String): Unit = unexpected("clearBookCoverCache")

        override suspend fun clearRootDerivedCaches(rootId: String) {
            events += "clearRootDerivedCaches:$rootId"
        }
    }

    private sealed class BookLookup {
        data class Result(val book: BookEntity?) : BookLookup()
        data class Failure(val throwable: Throwable) : BookLookup()
    }

    private companion object {
        private const val BOOK_ID = "book-id"
        private const val SECOND_BOOK_ID = "second-book-id"
        private const val ROOT_ID = "root-id"

        private fun unexpected(methodName: String): Nothing {
            error("Unexpected gateway call in LibraryRootManagementUseCaseTest: $methodName")
        }
    }
}
