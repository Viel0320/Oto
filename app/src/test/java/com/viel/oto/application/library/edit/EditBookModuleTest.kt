package com.viel.oto.application.library.edit

import com.viel.oto.data.book.BookCatalogGateway
import com.viel.oto.data.book.BookDeletionGateway
import com.viel.oto.data.book.BookMetadataGateway
import com.viel.oto.data.book.BookmarkGateway
import com.viel.oto.data.book.ChapterGateway
import com.viel.oto.data.cover.CoverAssetGateway
import com.viel.oto.data.db.AudiobookSchema
import com.viel.oto.data.entity.BookEntity
import com.viel.oto.data.entity.BookFileEntity
import com.viel.oto.data.entity.BookWithProgress
import com.viel.oto.data.entity.BookmarkEntity
import com.viel.oto.data.entity.ChapterEntity
import com.viel.oto.data.entity.ChapterWithBookFile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks edit-scene read and command delegation.
 * Verifies selected-book reads, text metadata updates, and custom cover saves outside EditBookViewModel.
 */
class EditBookModuleTest {

    @Test
    fun editableBookIsReadFromTheBookQueryGateway() = runBlocking {
        val persistedBook = book(
            title = "Editable",
            author = "Author",
            narrator = "Narrator",
            description = "Description",
            year = "2026",
            series = "Series",
            coverPath = "cover.jpg",
            thumbnailPath = "thumb.jpg",
            lastScannedAt = 42L
        )
        val expected = EditBookDraft(
            id = BOOK_ID,
            title = "Editable",
            author = "Author",
            narrator = "Narrator",
            description = "Description",
            year = "2026",
            series = "Series",
            coverPath = "cover.jpg",
            thumbnailPath = "thumb.jpg",
            coverLastUpdated = 42L
        )
        val queryGateway = FakeBookQueryGateway(booksById = mapOf(BOOK_ID to persistedBook))
        val module = moduleFor(queryGateway = queryGateway)

        val result = module.getEditableBook(BOOK_ID)

        assertEquals(expected, result)
        assertEquals(listOf(BOOK_ID), queryGateway.requestedBookIds)
    }

    @Test
    fun textMetadataUpdateDelegatesAllEditableFields() = runBlocking {
        val queryGateway = FakeBookQueryGateway()
        val module = moduleFor(queryGateway = queryGateway)

        module.updateBookDetails(
            id = BOOK_ID,
            title = "  Title  ",
            author = "Author",
            narrator = "Narrator",
            description = "Description",
            year = "2026",
            series = "Series"
        )

        assertEquals(
            listOf(
                UpdateBookDetailsCall(
                    id = BOOK_ID,
                    title = "Title",
                    author = "Author",
                    narrator = "Narrator",
                    description = "Description",
                    year = "2026",
                    series = "Series"
                )
            ),
            queryGateway.updateCalls
        )
    }

    @Test
    fun blankTitleUpdateIsRejectedBeforePersistence() = runBlocking {
        val queryGateway = FakeBookQueryGateway()
        val module = moduleFor(queryGateway = queryGateway)

        val failure = runCatching {
            module.updateBookDetails(
                id = BOOK_ID,
                title = "   ",
                author = "Author",
                narrator = "Narrator",
                description = "Description",
                year = "2026",
                series = "Series"
            )
        }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
        assertEquals("EDIT_TITLE_REQUIRED", failure?.message)
        assertEquals(emptyList<UpdateBookDetailsCall>(), queryGateway.updateCalls)
    }

    @Test
    fun customCoverSaveDelegatesToTheCoverAssetGateway() = runBlocking {
        val coverGateway = FakeCoverAssetGateway()
        val module = moduleFor(coverGateway = coverGateway)

        module.saveCustomCover(BOOK_ID, "cache/custom-cover.jpg")

        assertEquals(listOf(CoverSaveCall(BOOK_ID, "cache/custom-cover.jpg")), coverGateway.coverSaveCalls)
    }

    private fun moduleFor(
        queryGateway: FakeBookQueryGateway = FakeBookQueryGateway(),
        coverGateway: FakeCoverAssetGateway = FakeCoverAssetGateway()
    ): DefaultEditBookModule {
        return DefaultEditBookModule(
            bookCatalogGateway = queryGateway,
            bookMetadataGateway = queryGateway,
            coverAssetGateway = coverGateway
        )
    }

    private fun book(
        title: String,
        author: String = "",
        narrator: String = "",
        description: String = "",
        year: String = "",
        series: String = "",
        coverPath: String? = null,
        thumbnailPath: String? = null,
        lastScannedAt: Long = 0L
    ): BookEntity {
        return BookEntity(
            id = BOOK_ID,
            rootId = "root-id",
            sourceType = AudiobookSchema.SourceType.SINGLE_AUDIO,
            title = title,
            author = author,
            narrator = narrator,
            description = description,
            year = year,
            series = series,
            coverPath = coverPath,
            thumbnailPath = thumbnailPath,
            lastScannedAt = lastScannedAt
        )
    }

    private class FakeBookQueryGateway(
        private val booksById: Map<String, BookEntity> = emptyMap()
    ) : BookCatalogGateway,
        BookMetadataGateway,
        BookmarkGateway,
        ChapterGateway,
        BookDeletionGateway {
        val requestedBookIds = mutableListOf<String>()
        val updateCalls = mutableListOf<UpdateBookDetailsCall>()

        override val audiobooks: Flow<List<BookWithProgress>> = flowOf(emptyList())

        override suspend fun getBookById(id: String): BookEntity? {
            requestedBookIds += id
            return booksById[id]
        }

        override suspend fun updateBookDetails(
            id: String,
            title: String,
            author: String,
            narrator: String,
            description: String,
            year: String,
            series: String
        ) {
            updateCalls += UpdateBookDetailsCall(
                id = id,
                title = title,
                author = author,
                narrator = narrator,
                description = description,
                year = year,
                series = series
            )
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
        override suspend fun updateBookReadStatus(bookId: String, readStatus: AudiobookSchema.ReadStatus): Unit = unexpected("updateBookReadStatus")
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

    private class FakeCoverAssetGateway : CoverAssetGateway {
        val coverSaveCalls = mutableListOf<CoverSaveCall>()

        override suspend fun saveCustomCover(bookId: String, coverUri: String) {
            coverSaveCalls += CoverSaveCall(bookId, coverUri)
        }
    }

    private data class UpdateBookDetailsCall(
        val id: String,
        val title: String,
        val author: String,
        val narrator: String,
        val description: String,
        val year: String,
        val series: String
    )

    private data class CoverSaveCall(
        val bookId: String,
        val coverUri: String
    )

    private companion object {
        private const val BOOK_ID = "book-id"

        private fun unexpected(methodName: String): Nothing {
            error("Unexpected gateway call in EditBookModuleTest: $methodName")
        }
    }
}
