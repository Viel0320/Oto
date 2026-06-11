package com.viel.aplayer.application.library.detail

import android.net.Uri
import com.viel.aplayer.data.db.AudiobookSchema
import com.viel.aplayer.data.entity.BookEntity
import com.viel.aplayer.data.entity.BookFileEntity
import com.viel.aplayer.data.entity.BookWithProgress
import com.viel.aplayer.data.entity.BookmarkEntity
import com.viel.aplayer.data.entity.ChapterEntity
import com.viel.aplayer.data.entity.ChapterWithBookFile
import com.viel.aplayer.data.entity.LibraryRootEntity
import com.viel.aplayer.data.gateway.BookAvailabilityGateway
import com.viel.aplayer.data.gateway.BookCatalogGateway
import com.viel.aplayer.data.gateway.BookDeletionGateway
import com.viel.aplayer.data.gateway.BookMetadataGateway
import com.viel.aplayer.data.gateway.BookmarkGateway
import com.viel.aplayer.data.gateway.ChapterGateway
import com.viel.aplayer.data.gateway.LibraryRootGateway
import com.viel.aplayer.library.availability.LibraryRootAvailabilityUpdate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Detail Book Module Test (Locks detail-scene gateway delegation)
 * Verifies live snapshot updates, availability refresh delegation, and root cache behavior outside DetailViewModel.
 */
class DetailBookModuleTest {

    @Test
    fun liveSnapshotUpdatesOnlyTheSelectedBook() = runBlocking {
        val queryGateway = FakeBookQueryGateway(
            observedBooks = mapOf(BOOK_ID to bookEntity(id = BOOK_ID, title = "Updated Title"))
        )
        val module = moduleFor(queryGateway = queryGateway)

        val result = module.observeLiveSnapshot(snapshot(id = BOOK_ID, title = "Original Title")).first()

        assertEquals(listOf(BOOK_ID), queryGateway.observedBookIds)
        assertEquals("Updated Title", result.item.title)
    }

    @Test
    fun liveSnapshotIgnoresUnexpectedBookRows() = runBlocking {
        val queryGateway = FakeBookQueryGateway(
            observedBooks = mapOf(BOOK_ID to bookEntity(id = "other-book", title = "Wrong Row"))
        )
        val module = moduleFor(queryGateway = queryGateway)

        val result = module.observeLiveSnapshot(snapshot(id = BOOK_ID, title = "Original Title")).first()

        assertEquals("Original Title", result.item.title)
    }

    @Test
    fun availabilityRefreshDelegatesOnlyTheRequestedBook() = runBlocking {
        val availabilityGateway = FakeBookAvailabilityGateway(availabilityByBookId = mapOf(BOOK_ID to false))
        val module = moduleFor(availabilityGateway = availabilityGateway)

        val result = module.refreshAvailability(BOOK_ID)

        assertFalse(result)
        assertEquals(listOf(BOOK_ID), availabilityGateway.refreshedBookIds)
    }

    @Test
    fun sourceLocationUsesCachedRootWithoutQueryingAllRoots() = runBlocking {
        val queryGateway = FakeBookQueryGateway(
            filesByBookId = mapOf(
                BOOK_ID to listOf(
                    bookFile(
                        bookId = BOOK_ID,
                        sourcePath = "Audiobooks/Book/track.mp3"
                    )
                )
            )
        )
        val rootGateway = FakeLibraryRootGateway(
            cachedRoots = listOf(root(displayName = "Audiobooks")),
            persistentRoots = listOf(root(displayName = "Persistent Audiobooks"))
        )
        val module = moduleFor(
            queryGateway = queryGateway,
            rootGateway = rootGateway
        )

        val result = module.resolveSourceLocation(snapshot(id = BOOK_ID))

        assertEquals("SAF://Audiobooks/Book/track.mp3", result)
        assertEquals(0, rootGateway.allRootsQueryCount)
    }

    private fun moduleFor(
        queryGateway: FakeBookQueryGateway = FakeBookQueryGateway(),
        availabilityGateway: FakeBookAvailabilityGateway = FakeBookAvailabilityGateway(),
        rootGateway: FakeLibraryRootGateway = FakeLibraryRootGateway()
    ): DefaultDetailBookModule {
        // Module Fixture (Wires fake granular gateways exactly like LibraryGraph does)
        // Each fake records only the detail-scene calls under test and fails fast for unrelated gateway methods.
        return DefaultDetailBookModule(
            // Detail Catalog Fixture (Pass only catalog-capable fake data to the detail module)
            // Detail reads selected book and file inventory without receiving metadata or bookmark command dependencies.
            bookCatalogGateway = queryGateway,
            bookAvailabilityGateway = availabilityGateway,
            libraryRootGateway = rootGateway
        )
    }

    private fun snapshot(
        id: String = BOOK_ID,
        title: String = "Selected Book"
    ): DetailSnapshot {
        // Detail Snapshot Fixture (Builds the Room-free selected item at the scene boundary)
        // The module should treat this as the selected book identity and never infer selection from gateway internals.
        return DetailSnapshot(
            item = DetailBookItem(
                id = id,
                rootId = ROOT_ID,
                sourceType = AudiobookSchema.SourceType.SINGLE_AUDIO,
                title = title
            )
        )
    }

    private fun bookEntity(
        id: String = BOOK_ID,
        title: String = "Selected Book"
    ): BookEntity {
        return BookEntity(
            id = id,
            rootId = ROOT_ID,
            sourceType = AudiobookSchema.SourceType.SINGLE_AUDIO,
            title = title
        )
    }

    private fun bookFile(
        bookId: String,
        sourcePath: String
    ): BookFileEntity {
        return BookFileEntity(
            id = "file-$bookId",
            bookId = bookId,
            rootId = ROOT_ID,
            index = 0,
            sourcePath = sourcePath,
            sourceIdentity = sourcePath,
            displayName = sourcePath.substringAfterLast('/'),
            durationMs = 1_000L,
            fileSize = 100L,
            lastModified = 1L
        )
    }

    private fun root(displayName: String): LibraryRootEntity {
        return LibraryRootEntity(
            id = ROOT_ID,
            sourceType = AudiobookSchema.LibrarySourceType.SAF,
            sourceUri = "content://root",
            displayName = displayName
        )
    }

    private class FakeBookQueryGateway(
        private val observedBooks: Map<String, BookEntity?> = emptyMap(),
        private val filesByBookId: Map<String, List<BookFileEntity>> = emptyMap()
    ) : BookCatalogGateway,
        BookMetadataGateway,
        BookmarkGateway,
        ChapterGateway,
        BookDeletionGateway {
        val observedBookIds = mutableListOf<String>()

        override val audiobooks: Flow<List<BookWithProgress>> = flowOf(emptyList())

        override suspend fun getBookById(id: String): BookEntity? = unexpected("getBookById")

        override fun observeBookById(id: String): Flow<BookEntity?> {
            observedBookIds += id
            return flowOf(observedBooks[id])
        }

        override fun searchAudiobooks(query: String): Flow<List<BookWithProgress>> = unexpected("searchAudiobooks")
        override fun filterByYear(year: String): Flow<List<BookWithProgress>> = unexpected("filterByYear")
        override fun filterByAuthor(author: String): Flow<List<BookWithProgress>> = unexpected("filterByAuthor")
        override fun filterByAuthorLimited(author: String, excludeId: String, limit: Int): Flow<List<BookWithProgress>> = unexpected("filterByAuthorLimited")
        override fun filterByNarrator(narrator: String): Flow<List<BookWithProgress>> = unexpected("filterByNarrator")
        override fun filterByNarratorLimited(narrator: String, excludeId: String, limit: Int): Flow<List<BookWithProgress>> = unexpected("filterByNarratorLimited")
        override fun getRecentlyAdded(limit: Int): Flow<List<BookWithProgress>> = unexpected("getRecentlyAdded")
        override fun getRecentlyAddedExclusive(currentId: String, authors: List<String>, narrators: List<String>, limit: Int): Flow<List<BookWithProgress>> = unexpected("getRecentlyAddedExclusive")
        override suspend fun deleteBook(bookId: String): Unit = unexpected("deleteBook")
        override suspend fun updateBookReadStatus(bookId: String, readStatus: AudiobookSchema.ReadStatus): Unit = unexpected("updateBookReadStatus")
        override suspend fun updateBookDetails(id: String, title: String, author: String, narrator: String, description: String, year: String, series: String): Unit = unexpected("updateBookDetails")
        override suspend fun getFilesForBookSync(bookId: String): List<BookFileEntity> = unexpected("getFilesForBookSync")
        override suspend fun getAllFilesForBookSync(bookId: String): List<BookFileEntity> = filesByBookId[bookId].orEmpty()
        override fun updateMetadata(bookId: String, title: String?, author: String?, narrator: String?, description: String?, duration: Long): Unit = unexpected("updateMetadata")
        override fun getChapters(bookId: String): Flow<List<ChapterWithBookFile>> = unexpected("getChapters")
        override suspend fun getChaptersForBookSync(bookId: String): List<ChapterWithBookFile> = unexpected("getChaptersForBookSync")
        override fun saveChapters(bookId: String, chapters: List<ChapterEntity>): Unit = unexpected("saveChapters")
        override fun getBookmarks(bookId: String): Flow<List<BookmarkEntity>> = unexpected("getBookmarks")
        override suspend fun addBookmark(bookId: String, position: Long, title: String): Unit = unexpected("addBookmark")
        override suspend fun updateBookmark(bookmark: BookmarkEntity): Unit = unexpected("updateBookmark")
        override suspend fun deleteBookmark(bookmark: BookmarkEntity): Unit = unexpected("deleteBookmark")
    }

    private class FakeBookAvailabilityGateway(
        private val availabilityByBookId: Map<String, Boolean> = emptyMap()
    ) : BookAvailabilityGateway {
        val refreshedBookIds = mutableListOf<String>()

        override suspend fun refreshDetailAvailabilityStatus(bookId: String): Boolean {
            refreshedBookIds += bookId
            return availabilityByBookId[bookId] ?: true
        }

        override suspend fun checkPrimaryAudioFileExistsWithoutStatusRefresh(bookId: String): Boolean = unexpected("checkPrimaryAudioFileExistsWithoutStatusRefresh")
        override suspend fun refreshCurrentPlaybackFileAvailabilityStatus(bookId: String): Boolean = unexpected("refreshCurrentPlaybackFileAvailabilityStatus")
        override suspend fun refreshPlaybackFileUnavailableStatus(bookId: String, queueIndex: Int): Unit = unexpected("refreshPlaybackFileUnavailableStatus")
        override suspend fun findNextAvailablePlaybackFileAndRefreshStatus(bookId: String, afterQueueIndex: Int): Pair<Int, BookFileEntity>? = unexpected("findNextAvailablePlaybackFileAndRefreshStatus")
    }

    private class FakeLibraryRootGateway(
        private val cachedRoots: List<LibraryRootEntity> = emptyList(),
        private val persistentRoots: List<LibraryRootEntity> = emptyList()
    ) : LibraryRootGateway {
        var allRootsQueryCount = 0

        override fun observeLibraryRoots(): Flow<List<LibraryRootEntity>> = unexpected("observeLibraryRoots")
        override fun getCachedLibraryRoots(): List<LibraryRootEntity> = cachedRoots

        override suspend fun getAllRootsOnce(): List<LibraryRootEntity> {
            allRootsQueryCount += 1
            return persistentRoots
        }

        override suspend fun setLibraryRoot(uri: Uri): LibraryRootEntity = unexpected("setLibraryRoot")
        override suspend fun addWebDavLibraryRoot(url: String, username: String, password: String, displayName: String, basePath: String): LibraryRootEntity = unexpected("addWebDavLibraryRoot")
        override suspend fun addAbsLibraryRoot(credentialId: String, libraryId: String, displayName: String): LibraryRootEntity = unexpected("addAbsLibraryRoot")
        override fun addLibraryRootAndScheduleSync(uri: Uri, trigger: String): Unit = unexpected("addLibraryRootAndScheduleSync")
        override fun addWebDavLibraryRootAndScheduleSync(url: String, username: String, password: String, displayName: String, basePath: String, trigger: String): Unit = unexpected("addWebDavLibraryRootAndScheduleSync")
        override suspend fun refreshLibraryRootStatuses(): Unit = unexpected("refreshLibraryRootStatuses")
        override suspend fun refreshLibraryRootStatus(rootId: String): LibraryRootAvailabilityUpdate? = unexpected("refreshLibraryRootStatus")
        override suspend fun deleteLibraryRootDataOnly(root: LibraryRootEntity): Unit = unexpected("deleteLibraryRootDataOnly")
        override suspend fun updateSafLibraryRoot(id: String, newUri: Uri): LibraryRootEntity = unexpected("updateSafLibraryRoot")
        override suspend fun updateWebDavLibraryRoot(id: String, url: String, username: String, password: String, displayName: String, basePath: String): LibraryRootEntity = unexpected("updateWebDavLibraryRoot")
        override suspend fun updateAbsLibraryRoot(id: String, credentialId: String, libraryId: String, displayName: String): LibraryRootEntity = unexpected("updateAbsLibraryRoot")
    }

    private companion object {
        private const val BOOK_ID = "book-id"
        private const val ROOT_ID = "root-id"

        private fun unexpected(methodName: String): Nothing {
            error("Unexpected gateway call in DetailBookModuleTest: $methodName")
        }
    }
}
