package com.viel.aplayer.application.library.player

import com.viel.aplayer.data.db.AudiobookSchema
import com.viel.aplayer.data.entity.BookEntity
import com.viel.aplayer.data.entity.BookFileEntity
import com.viel.aplayer.data.entity.BookProgressEntity
import com.viel.aplayer.data.entity.BookWithProgress
import com.viel.aplayer.data.entity.BookmarkEntity
import com.viel.aplayer.data.entity.ChapterEntity
import com.viel.aplayer.data.entity.ChapterWithBookFile
import com.viel.aplayer.data.availability.BookAvailabilityGateway
import com.viel.aplayer.data.book.BookCatalogGateway
import com.viel.aplayer.data.book.BookDeletionGateway
import com.viel.aplayer.data.book.BookMetadataGateway
import com.viel.aplayer.data.book.BookmarkGateway
import com.viel.aplayer.data.book.ChapterGateway
import com.viel.aplayer.data.progress.ProgressGateway
import com.viel.aplayer.data.subtitle.SubtitleGateway
import com.viel.aplayer.media.subtitle.SubtitleLine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Player Library Module Test (Locks player-scene read delegation)
 * Verifies metadata fan-in, cold-start progress previews, active-track availability, and cover polling without touching PlayerViewModel.
 */
class PlayerLibraryModuleTest {

    @Test
    fun metadataFlowCombinesBookChaptersBookmarksAndSubtitles() = runBlocking {
        val chapter = chapterWithFile(title = "Opening")
        val bookmark = bookmark(title = "Favorite")
        val subtitle = SubtitleLine(startTime = 0L, endTime = 1_000L, text = "Hello")
        val queryGateway = FakeBookQueryGateway(
            observedBooks = mapOf(BOOK_ID to book(title = "Selected Book")),
            chaptersByBookId = mapOf(BOOK_ID to listOf(chapter)),
            bookmarksByBookId = mapOf(BOOK_ID to listOf(bookmark))
        )
        val module = moduleFor(queryGateway = queryGateway)

        val result = module.observeMetadata(BOOK_ID, flowOf(listOf(subtitle))).first()

        assertEquals(BOOK_ID, result.id)
        assertEquals("Selected Book", result.title)
        assertEquals(listOf(playerChapterItem(title = "Opening")), result.chapters)
        assertEquals(listOf(playerBookmarkItem(bookmark)), result.bookmarks)
        assertEquals(listOf(subtitle), result.subtitles)
        assertEquals(listOf(BOOK_ID), queryGateway.observedBookIds)
        assertEquals(listOf(BOOK_ID), queryGateway.chapterBookIds)
        assertEquals(listOf(BOOK_ID), queryGateway.bookmarkBookIds)
    }

    @Test
    fun coldStartPreviewUsesLastProgressAndBookDurationOnly() = runBlocking {
        val queryGateway = FakeBookQueryGateway(
            booksById = mapOf(BOOK_ID to book(totalDurationMs = 9_000L))
        )
        val progressGateway = FakeProgressGateway(
            lastProgress = BookProgressEntity(
                bookId = BOOK_ID,
                globalPositionMs = 4_500L
            )
        )
        val module = moduleFor(
            queryGateway = queryGateway,
            progressGateway = progressGateway
        )

        val progress = module.getLastPlayedSnapshot()
        val preview = module.getBookPreview(BOOK_ID)

        assertEquals(PlayerRestoredProgressSnapshot(BOOK_ID, 4_500L), progress)
        assertEquals(PlayerBookPreview(BOOK_ID, 9_000L), preview)
        assertEquals(listOf(BOOK_ID), queryGateway.requestedBookIds)
    }

    @Test
    fun coverPollingQueriesOnlyBookPreviewInformation() = runBlocking {
        val queryGateway = FakeBookQueryGateway(
            booksById = mapOf(
                BOOK_ID to book(
                    coverPath = "cover/original.jpg",
                    thumbnailPath = "cover/thumb.jpg"
                )
            )
        )
        val module = moduleFor(queryGateway = queryGateway)

        val result = module.findDisplayCoverPath(BOOK_ID)

        assertEquals("cover/thumb.jpg", result)
        assertEquals(listOf(BOOK_ID), queryGateway.requestedBookIds)
        assertTrue(queryGateway.observedBookIds.isEmpty())
        assertTrue(queryGateway.chapterBookIds.isEmpty())
        assertTrue(queryGateway.bookmarkBookIds.isEmpty())
    }

    @Test
    fun currentPlaybackAvailabilityDelegatesToTheAvailabilityGateway() = runBlocking {
        val availabilityGateway = FakeBookAvailabilityGateway(currentAvailabilityByBookId = mapOf(BOOK_ID to false))
        val module = moduleFor(availabilityGateway = availabilityGateway)

        val result = module.refreshCurrentPlaybackAvailability(BOOK_ID)

        assertFalse(result)
        assertEquals(listOf(BOOK_ID), availabilityGateway.currentRefreshBookIds)
    }

    private fun moduleFor(
        queryGateway: FakeBookQueryGateway = FakeBookQueryGateway(),
        availabilityGateway: FakeBookAvailabilityGateway = FakeBookAvailabilityGateway(),
        progressGateway: FakeProgressGateway = FakeProgressGateway(),
        subtitleGateway: FakeSubtitleGateway = FakeSubtitleGateway()
    ): DefaultPlayerLibraryModule {
        // Player Module Fixture (Supplies fake granular gateways exactly like LibraryGraph wiring)
        // Each fake records only player-scene calls so the test fails fast if the module reaches outside this boundary.
        return DefaultPlayerLibraryModule(
            // Player Gateway Split Fixture (Reuse one recording fake across read, chapter, and bookmark seams)
            // The module under test now receives the same capabilities through separate constructor slots.
            bookCatalogGateway = queryGateway,
            chapterGateway = queryGateway,
            bookmarkGateway = queryGateway,
            bookAvailabilityGateway = availabilityGateway,
            progressGateway = progressGateway,
            subtitleGateway = subtitleGateway
        )
    }

    private fun book(
        title: String = "Book",
        totalDurationMs: Long = 1_000L,
        coverPath: String? = null,
        thumbnailPath: String? = null
    ): BookEntity {
        return BookEntity(
            id = BOOK_ID,
            rootId = ROOT_ID,
            sourceType = AudiobookSchema.SourceType.SINGLE_AUDIO,
            title = title,
            author = "Author",
            narrator = "Narrator",
            totalDurationMs = totalDurationMs,
            coverPath = coverPath,
            thumbnailPath = thumbnailPath,
            lastScannedAt = 123L
        )
    }

    private fun chapterWithFile(title: String): ChapterWithBookFile {
        return ChapterWithBookFile(
            chapter = ChapterEntity(
                id = "chapter-id",
                bookId = BOOK_ID,
                bookFileId = FILE_ID,
                index = 0,
                title = title,
                startPositionMs = 0L,
                durationMs = 1_000L,
                fileOffsetMs = 0L,
                // Update PlayerLibraryModuleTest: Change source type to type-safe AudiobookSchema.ChapterSource.MANUAL enum.
                source = AudiobookSchema.ChapterSource.MANUAL
            ),
            bookFile = bookFile()
        )
    }

    private fun playerChapterItem(title: String): PlayerChapterItem {
        // Player Chapter Projection Expectation (Verifies metadata mapping without exposing Room relations)
        // The expected value mirrors the scene-facing chapter row emitted by DefaultPlayerLibraryModule.
        return PlayerChapterItem(
            id = "chapter-id",
            bookId = BOOK_ID,
            bookFileId = FILE_ID,
            index = 0,
            title = title,
            startPositionMs = 0L,
            durationMs = 1_000L,
            fileOffsetMs = 0L,
            // Update PlayerLibraryModuleTest: Change source type to type-safe AudiobookSchema.ChapterSource.MANUAL enum.
            source = AudiobookSchema.ChapterSource.MANUAL,
            isFileMissing = false
        )
    }

    private fun bookFile(): BookFileEntity {
        return BookFileEntity(
            id = FILE_ID,
            bookId = BOOK_ID,
            rootId = ROOT_ID,
            index = 0,
            sourcePath = "track.mp3",
            sourceIdentity = "track.mp3",
            displayName = "track.mp3",
            durationMs = 1_000L,
            fileSize = 100L,
            lastModified = 1L
        )
    }

    private fun bookmark(title: String): BookmarkEntity {
        return BookmarkEntity(
            id = "bookmark-id",
            bookId = BOOK_ID,
            globalPositionMs = 500L,
            title = title,
            createdAt = BOOKMARK_CREATED_AT
        )
    }

    private fun playerBookmarkItem(bookmark: BookmarkEntity): PlayerBookmarkItem {
        // Player Bookmark Projection Expectation (Verifies bookmark entity mapping at the adapter boundary)
        // The expected value keeps all stable anchor fields so command and read projections remain lossless.
        return PlayerBookmarkItem(
            id = bookmark.id,
            bookId = bookmark.bookId,
            globalPositionMs = bookmark.globalPositionMs,
            bookFileId = bookmark.bookFileId,
            fileOffsetMs = bookmark.fileOffsetMs,
            fileFingerprint = bookmark.fileFingerprint,
            anchorStatus = bookmark.anchorStatus,
            title = bookmark.title,
            createdAt = bookmark.createdAt
        )
    }

    private class FakeBookQueryGateway(
        private val observedBooks: Map<String, BookEntity?> = emptyMap(),
        private val booksById: Map<String, BookEntity> = emptyMap(),
        private val chaptersByBookId: Map<String, List<ChapterWithBookFile>> = emptyMap(),
        private val bookmarksByBookId: Map<String, List<BookmarkEntity>> = emptyMap()
    ) : BookCatalogGateway,
        BookMetadataGateway,
        BookmarkGateway,
        ChapterGateway,
        BookDeletionGateway {
        val observedBookIds = mutableListOf<String>()
        val requestedBookIds = mutableListOf<String>()
        val chapterBookIds = mutableListOf<String>()
        val bookmarkBookIds = mutableListOf<String>()

        override val audiobooks: Flow<List<BookWithProgress>> = flowOf(emptyList())

        override suspend fun getBookById(id: String): BookEntity? {
            requestedBookIds += id
            return booksById[id]
        }

        override fun observeBookById(id: String): Flow<BookEntity?> {
            observedBookIds += id
            return flowOf(observedBooks[id])
        }

        override fun getChapters(bookId: String): Flow<List<ChapterWithBookFile>> {
            chapterBookIds += bookId
            return flowOf(chaptersByBookId[bookId].orEmpty())
        }

        override fun getBookmarks(bookId: String): Flow<List<BookmarkEntity>> {
            bookmarkBookIds += bookId
            return flowOf(bookmarksByBookId[bookId].orEmpty())
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
        override suspend fun getAllFilesForBookSync(bookId: String): List<BookFileEntity> = unexpected("getAllFilesForBookSync")
        override fun updateMetadata(bookId: String, title: String?, author: String?, narrator: String?, description: String?, duration: Long): Unit = unexpected("updateMetadata")
        override suspend fun getChaptersForBookSync(bookId: String): List<ChapterWithBookFile> = unexpected("getChaptersForBookSync")
        override fun saveChapters(bookId: String, chapters: List<ChapterEntity>): Unit = unexpected("saveChapters")
        override suspend fun addBookmark(bookId: String, position: Long, title: String): Unit = unexpected("addBookmark")
        override suspend fun updateBookmark(bookmark: BookmarkEntity): Unit = unexpected("updateBookmark")
        override suspend fun deleteBookmark(bookmark: BookmarkEntity): Unit = unexpected("deleteBookmark")
    }

    private class FakeBookAvailabilityGateway(
        private val currentAvailabilityByBookId: Map<String, Boolean> = emptyMap()
    ) : BookAvailabilityGateway {
        val currentRefreshBookIds = mutableListOf<String>()

        override suspend fun refreshCurrentPlaybackFileAvailabilityStatus(bookId: String): Boolean {
            currentRefreshBookIds += bookId
            return currentAvailabilityByBookId[bookId] ?: true
        }

        override suspend fun refreshDetailAvailabilityStatus(bookId: String): Boolean = unexpected("refreshDetailAvailabilityStatus")
        override suspend fun checkPrimaryAudioFileExistsWithoutStatusRefresh(bookId: String): Boolean = unexpected("checkPrimaryAudioFileExistsWithoutStatusRefresh")
        override suspend fun refreshPlaybackFileUnavailableStatus(bookId: String, queueIndex: Int): Unit = unexpected("refreshPlaybackFileUnavailableStatus")
        override suspend fun findNextAvailablePlaybackFileAndRefreshStatus(bookId: String, afterQueueIndex: Int): Pair<Int, BookFileEntity>? = unexpected("findNextAvailablePlaybackFileAndRefreshStatus")
    }

    private class FakeProgressGateway(
        private val lastProgress: BookProgressEntity? = null
    ) : ProgressGateway {
        override suspend fun getLastPlayedProgressSync(): BookProgressEntity? = lastProgress

        override fun updateProgress(bookId: String, position: Long): Unit = unexpected("updateProgress")
        override suspend fun saveProgress(progress: BookProgressEntity): Unit = unexpected("saveProgress")
        override suspend fun getProgressForBookSync(bookId: String): BookProgressEntity? = unexpected("getProgressForBookSync")
    }

    private class FakeSubtitleGateway : SubtitleGateway {
        override suspend fun loadSubtitlesForBookFile(bookFileId: String): List<SubtitleLine> = unexpected("loadSubtitlesForBookFile")
    }

    private companion object {
        private const val BOOK_ID = "book-id"
        private const val FILE_ID = "file-id"
        private const val ROOT_ID = "root-id"
        private const val BOOKMARK_CREATED_AT = 42L

        private fun unexpected(methodName: String): Nothing {
            error("Unexpected gateway call in PlayerLibraryModuleTest: $methodName")
        }
    }
}
