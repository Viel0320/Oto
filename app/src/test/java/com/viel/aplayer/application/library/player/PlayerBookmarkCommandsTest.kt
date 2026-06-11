package com.viel.aplayer.application.library.player

import com.viel.aplayer.data.db.AudiobookSchema
import com.viel.aplayer.data.entity.BookEntity
import com.viel.aplayer.data.entity.BookFileEntity
import com.viel.aplayer.data.entity.BookProgressEntity
import com.viel.aplayer.data.entity.BookWithProgress
import com.viel.aplayer.data.entity.BookmarkEntity
import com.viel.aplayer.data.entity.ChapterEntity
import com.viel.aplayer.data.entity.ChapterWithBookFile
import com.viel.aplayer.data.gateway.BookAvailabilityGateway
import com.viel.aplayer.data.gateway.BookCatalogGateway
import com.viel.aplayer.data.gateway.BookDeletionGateway
import com.viel.aplayer.data.gateway.BookMetadataGateway
import com.viel.aplayer.data.gateway.BookmarkGateway
import com.viel.aplayer.data.gateway.ChapterGateway
import com.viel.aplayer.data.gateway.ProgressGateway
import com.viel.aplayer.data.gateway.SubtitleGateway
import com.viel.aplayer.media.subtitle.SubtitleLine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Player Bookmark Commands Test (Locks player-scene bookmark mutations)
 * Verifies add, update, and delete commands delegate exactly once to the bookmark-capable query gateway.
 */
class PlayerBookmarkCommandsTest {

    @Test
    fun addBookmarkDelegatesToTheBookQueryGateway() = runBlocking {
        val gateway = FakeBookQueryGateway()
        val commands = moduleFor(gateway)

        commands.addBookmark(BOOK_ID, 3_000L, "Scene")

        assertEquals(listOf(AddBookmarkCall(BOOK_ID, 3_000L, "Scene")), gateway.addBookmarkCalls)
    }

    @Test
    fun updateBookmarkDelegatesWithTheEditedTitle() = runBlocking {
        val gateway = FakeBookQueryGateway()
        val commands = moduleFor(gateway)
        val original = bookmark(title = "Old")

        commands.updateBookmark(original, "New")

        assertEquals(listOf(bookmarkEntity(title = "New")), gateway.updatedBookmarks)
    }

    @Test
    fun deleteBookmarkDelegatesToTheBookQueryGateway() = runBlocking {
        val gateway = FakeBookQueryGateway()
        val commands = moduleFor(gateway)
        val selected = bookmark(title = "Delete Me")

        commands.deleteBookmark(selected)

        assertEquals(listOf(bookmarkEntity(title = "Delete Me")), gateway.deletedBookmarks)
    }

    private fun moduleFor(gateway: FakeBookQueryGateway): PlayerBookmarkCommands {
        // Bookmark Command Fixture (Wires only the query gateway behavior needed by bookmark mutations)
        // Other gateway fakes fail fast so these tests protect BookmarkManager's narrow command surface.
        return DefaultPlayerLibraryModule(
            // Player Gateway Split Fixture (Reuse one fake across catalog, chapter, and bookmark slots)
            // Bookmark tests exercise only BookmarkGateway while inert catalog and chapter seams satisfy constructor wiring.
            bookCatalogGateway = gateway,
            chapterGateway = gateway,
            bookmarkGateway = gateway,
            bookAvailabilityGateway = FakeBookAvailabilityGateway(),
            progressGateway = FakeProgressGateway(),
            subtitleGateway = FakeSubtitleGateway()
        )
    }

    private fun bookmark(title: String): PlayerBookmarkItem {
        // Player Bookmark Projection Fixture (Matches the public player command contract)
        // The command adapter receives scene projections and reconstructs Room entities only before delegating to the gateway.
        return PlayerBookmarkItem(
            id = "bookmark-id",
            bookId = BOOK_ID,
            globalPositionMs = 1_000L,
            title = title,
            createdAt = BOOKMARK_CREATED_AT
        )
    }

    private fun bookmarkEntity(title: String): BookmarkEntity {
        // Bookmark Entity Expectation Fixture (Verifies adapter-side persistence rehydration)
        // Gateway assertions stay on BookmarkEntity because the fake gateway models the data-layer boundary.
        return BookmarkEntity(
            id = "bookmark-id",
            bookId = BOOK_ID,
            globalPositionMs = 1_000L,
            title = title,
            createdAt = BOOKMARK_CREATED_AT
        )
    }

    private class FakeBookQueryGateway : BookCatalogGateway,
        BookMetadataGateway,
        BookmarkGateway,
        ChapterGateway,
        BookDeletionGateway {
        val addBookmarkCalls = mutableListOf<AddBookmarkCall>()
        val updatedBookmarks = mutableListOf<BookmarkEntity>()
        val deletedBookmarks = mutableListOf<BookmarkEntity>()

        override val audiobooks: Flow<List<BookWithProgress>> = flowOf(emptyList())

        override suspend fun addBookmark(bookId: String, position: Long, title: String) {
            addBookmarkCalls += AddBookmarkCall(bookId, position, title)
        }

        override suspend fun updateBookmark(bookmark: BookmarkEntity) {
            updatedBookmarks += bookmark
        }

        override suspend fun deleteBookmark(bookmark: BookmarkEntity) {
            deletedBookmarks += bookmark
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
        override suspend fun deleteBook(bookId: String): Unit = unexpected("deleteBook")
        override suspend fun updateBookReadStatus(bookId: String, readStatus: AudiobookSchema.ReadStatus): Unit = unexpected("updateBookReadStatus")
        override suspend fun updateBookDetails(id: String, title: String, author: String, narrator: String, description: String, year: String, series: String): Unit = unexpected("updateBookDetails")
        override suspend fun getFilesForBookSync(bookId: String): List<BookFileEntity> = unexpected("getFilesForBookSync")
        override suspend fun getAllFilesForBookSync(bookId: String): List<BookFileEntity> = unexpected("getAllFilesForBookSync")
        override fun updateMetadata(bookId: String, title: String?, author: String?, narrator: String?, description: String?, duration: Long): Unit = unexpected("updateMetadata")
        override fun getChapters(bookId: String): Flow<List<ChapterWithBookFile>> = unexpected("getChapters")
        override suspend fun getChaptersForBookSync(bookId: String): List<ChapterWithBookFile> = unexpected("getChaptersForBookSync")
        override fun saveChapters(bookId: String, chapters: List<ChapterEntity>): Unit = unexpected("saveChapters")
        override fun getBookmarks(bookId: String): Flow<List<BookmarkEntity>> = unexpected("getBookmarks")
    }

    private class FakeBookAvailabilityGateway : BookAvailabilityGateway {
        override suspend fun refreshDetailAvailabilityStatus(bookId: String): Boolean = unexpected("refreshDetailAvailabilityStatus")
        override suspend fun checkPrimaryAudioFileExistsWithoutStatusRefresh(bookId: String): Boolean = unexpected("checkPrimaryAudioFileExistsWithoutStatusRefresh")
        override suspend fun refreshCurrentPlaybackFileAvailabilityStatus(bookId: String): Boolean = unexpected("refreshCurrentPlaybackFileAvailabilityStatus")
        override suspend fun refreshPlaybackFileUnavailableStatus(bookId: String, queueIndex: Int): Unit = unexpected("refreshPlaybackFileUnavailableStatus")
        override suspend fun findNextAvailablePlaybackFileAndRefreshStatus(bookId: String, afterQueueIndex: Int): Pair<Int, BookFileEntity>? = unexpected("findNextAvailablePlaybackFileAndRefreshStatus")
    }

    private class FakeProgressGateway : ProgressGateway {
        override fun updateProgress(bookId: String, position: Long): Unit = unexpected("updateProgress")
        override suspend fun saveProgress(progress: BookProgressEntity): Unit = unexpected("saveProgress")
        override suspend fun getLastPlayedProgressSync(): BookProgressEntity? = unexpected("getLastPlayedProgressSync")
        override suspend fun getProgressForBookSync(bookId: String): BookProgressEntity? = unexpected("getProgressForBookSync")
    }

    private class FakeSubtitleGateway : SubtitleGateway {
        override suspend fun loadSubtitlesForBookFile(bookFileId: String): List<SubtitleLine> = unexpected("loadSubtitlesForBookFile")
    }

    private data class AddBookmarkCall(
        val bookId: String,
        val position: Long,
        val title: String
    )

    private companion object {
        private const val BOOK_ID = "book-id"
        private const val BOOKMARK_CREATED_AT = 42L

        private fun unexpected(methodName: String): Nothing {
            error("Unexpected gateway call in PlayerBookmarkCommandsTest: $methodName")
        }
    }
}
