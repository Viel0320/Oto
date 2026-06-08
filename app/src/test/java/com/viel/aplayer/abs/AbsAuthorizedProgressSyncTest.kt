package com.viel.aplayer.abs

import com.viel.aplayer.abs.mapping.AbsRemoteIdMapper
import com.viel.aplayer.abs.net.AbsApiClient
import com.viel.aplayer.abs.net.dto.AbsAuthorizeResponseDto
import com.viel.aplayer.abs.net.dto.AbsAuthorizedUserDto
import com.viel.aplayer.abs.net.dto.AbsLibraryDto
import com.viel.aplayer.abs.net.dto.AbsLibraryItemDto
import com.viel.aplayer.abs.net.dto.AbsLibraryItemsResponseDto
import com.viel.aplayer.abs.net.dto.AbsPlayRequestDto
import com.viel.aplayer.abs.net.dto.AbsPlaybackSessionDto
import com.viel.aplayer.abs.net.dto.AbsStatusDto
import com.viel.aplayer.abs.net.dto.AbsUserProgressDto
import com.viel.aplayer.abs.sync.AbsAuthorizedProgressSynchronizer
import com.viel.aplayer.data.db.AudiobookSchema
import com.viel.aplayer.data.entity.BookEntity
import com.viel.aplayer.data.entity.BookFileEntity
import com.viel.aplayer.data.entity.BookProgressEntity
import com.viel.aplayer.data.entity.LibraryRootEntity
import com.viel.aplayer.data.gateway.BookCatalogGateway
import com.viel.aplayer.data.gateway.BookDeletionGateway
import com.viel.aplayer.data.gateway.BookMetadataGateway
import com.viel.aplayer.data.gateway.BookmarkGateway
import com.viel.aplayer.data.gateway.ChapterGateway
import com.viel.aplayer.data.gateway.ProgressGateway
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AbsAuthorizedProgressSyncTest {
    private val idMapper = AbsRemoteIdMapper()

    @Test
    fun `authorized progress sync should merge authorize media progress through conflict resolver`() = runBlocking {
        val serverKey = idMapper.serverKey("https://example.com/audiobookshelf", "user-1")
        val remoteBook = absBook(idMapper.bookId(serverKey, "remote-new"), readStatus = AudiobookSchema.ReadStatus.NOT_STARTED)
        val localBook = absBook(idMapper.bookId(serverKey, "local-new"), readStatus = AudiobookSchema.ReadStatus.IN_PROGRESS)
        val bookGateway = FakeBookQueryGateway(remoteBook, localBook)
        val progressGateway = FakeProgressGateway(
            mapOf(
                remoteBook.id to BookProgressEntity(bookId = remoteBook.id, globalPositionMs = 1_000L, lastPlayedAt = 2_000L),
                localBook.id to BookProgressEntity(bookId = localBook.id, globalPositionMs = 90_000L, lastPlayedAt = 5_000L)
            )
        )
        val api = FakeAuthorizeApi(
            userId = "user-1",
            mediaProgress = listOf(
                AbsUserProgressDto(libraryItemId = "remote-new", currentTime = 60.0, isFinished = false, lastUpdate = 4_000L),
                AbsUserProgressDto(libraryItemId = "local-new", currentTime = 10.0, isFinished = false, lastUpdate = 3_000L),
                AbsUserProgressDto(libraryItemId = "unknown-item", currentTime = 120.0, isFinished = false, lastUpdate = 6_000L)
            )
        )
        val synchronizer = AbsAuthorizedProgressSynchronizer(
            apiClient = api,
            credentialProvider = { AbsAuthorizedProgressSynchronizer.CredentialSnapshot("https://example.com/audiobookshelf", "token-1") },
            // Authorized Progress Gateway Fixture (Reuse one fake through the split catalog and metadata seams)
            // The synchronizer reads mirrored book/file data through catalog and updates semantic readStatus through metadata.
            bookCatalogGateway = bookGateway,
            bookMetadataGateway = bookGateway,
            progressGateway = progressGateway
        )

        val summary = synchronizer.sync(listOf(absRoot()))

        // Authorized Progress Remote Merge (Verifies authorize.user.mediaProgress enters the shared conflict resolver)
        // The newer remote snapshot should update Room progress and readStatus, while older remote and unknown items must not overwrite local state.
        assertEquals(3, summary.remoteProgressCount)
        assertEquals(1, summary.appliedCount)
        assertEquals(1, summary.skippedByResolverCount)
        assertEquals(1, summary.skippedMissingBookCount)
        assertEquals(60_000L, progressGateway.progress[remoteBook.id]?.globalPositionMs)
        assertEquals(4_000L, progressGateway.progress[remoteBook.id]?.lastPlayedAt)
        assertEquals(AudiobookSchema.ReadStatus.IN_PROGRESS, bookGateway.books[remoteBook.id]?.readStatus)
        assertEquals(90_000L, progressGateway.progress[localBook.id]?.globalPositionMs)
        assertNull(progressGateway.progress[idMapper.bookId(serverKey, "unknown-item")])
    }

    private fun absRoot() = LibraryRootEntity(
        id = "root-1",
        sourceType = AudiobookSchema.LibrarySourceType.ABS,
        sourceUri = "https://example.com/audiobookshelf",
        basePath = "lib-1",
        credentialId = "cred-1",
        displayName = "ABS"
    )

    private fun absBook(id: String, readStatus: String) = BookEntity(
        id = id,
        rootId = "root-1",
        sourceType = AudiobookSchema.SourceType.ABS_REMOTE,
        sourceRoot = "https://example.com/audiobookshelf",
        title = id.substringAfterLast(':'),
        totalDurationMs = 120_000L,
        readStatus = readStatus
    )

    private class FakeAuthorizeApi(
        private val userId: String,
        private val mediaProgress: List<AbsUserProgressDto>
    ) : AbsApiClient {
        override suspend fun status(baseUrl: String): AbsStatusDto = AbsStatusDto(serverVersion = "2.35.1", isInit = true)
        override suspend fun login(baseUrl: String, username: String, password: String) = throw UnsupportedOperationException()
        override suspend fun authorize(baseUrl: String, token: String): AbsAuthorizeResponseDto =
            AbsAuthorizeResponseDto(user = AbsAuthorizedUserDto(id = userId, username = "demo", token = token, mediaProgress = mediaProgress))
        override suspend fun getLibraries(baseUrl: String, token: String): List<AbsLibraryDto> = emptyList()
        override suspend fun getLibraryItemsMinified(baseUrl: String, token: String, libraryId: String): AbsLibraryItemsResponseDto =
            AbsLibraryItemsResponseDto()
        override suspend fun batchGetItems(baseUrl: String, token: String, itemIds: List<String>): List<AbsLibraryItemDto> = emptyList()
        override suspend fun getProgressOrNull(baseUrl: String, token: String, itemId: String): AbsUserProgressDto? = null
        override suspend fun openPlaybackSession(baseUrl: String, token: String, itemId: String, request: AbsPlayRequestDto): AbsPlaybackSessionDto =
            throw UnsupportedOperationException()
        override suspend fun syncSession(baseUrl: String, token: String, sessionId: String, currentTimeSec: Double, timeListenedSec: Double, durationSec: Double) =
            Unit
        override suspend fun closeSession(baseUrl: String, token: String, sessionId: String, currentTimeSec: Double, timeListenedSec: Double, durationSec: Double) =
            Unit
    }

    private class FakeBookQueryGateway(
        vararg initialBooks: BookEntity
    ) : BookCatalogGateway,
        BookMetadataGateway,
        BookmarkGateway,
        ChapterGateway,
        BookDeletionGateway {
        val books: MutableMap<String, BookEntity> = initialBooks.associateBy { it.id }.toMutableMap()
        override val audiobooks: Flow<List<com.viel.aplayer.data.entity.BookWithProgress>> = flowOf(emptyList())
        override suspend fun getBookById(id: String): BookEntity? = books[id]
        override fun observeBookById(id: String): Flow<BookEntity?> = flowOf(books[id])
        override fun searchAudiobooks(query: String): Flow<List<com.viel.aplayer.data.entity.BookWithProgress>> = flowOf(emptyList())
        override fun filterByYear(year: String): Flow<List<com.viel.aplayer.data.entity.BookWithProgress>> = flowOf(emptyList())
        override fun filterByAuthor(author: String): Flow<List<com.viel.aplayer.data.entity.BookWithProgress>> = flowOf(emptyList())
        override fun filterByAuthorLimited(author: String, excludeId: String, limit: Int): Flow<List<com.viel.aplayer.data.entity.BookWithProgress>> = flowOf(emptyList())
        override fun filterByNarrator(narrator: String): Flow<List<com.viel.aplayer.data.entity.BookWithProgress>> = flowOf(emptyList())
        override fun filterByNarratorLimited(narrator: String, excludeId: String, limit: Int): Flow<List<com.viel.aplayer.data.entity.BookWithProgress>> = flowOf(emptyList())
        override fun getRecentlyAdded(limit: Int): Flow<List<com.viel.aplayer.data.entity.BookWithProgress>> = flowOf(emptyList())
        override fun getRecentlyAddedExclusive(currentId: String, authors: List<String>, narrators: List<String>, limit: Int): Flow<List<com.viel.aplayer.data.entity.BookWithProgress>> = flowOf(emptyList())
        override suspend fun deleteBook(bookId: String) = Unit
        override suspend fun updateBookReadStatus(bookId: String, readStatus: String) {
            books[bookId]?.let { book -> books[bookId] = book.copy(readStatus = readStatus) }
        }
        override suspend fun updateBookDetails(id: String, title: String, author: String, narrator: String, description: String, year: String, series: String) = Unit
        override suspend fun getFilesForBookSync(bookId: String): List<BookFileEntity> =
            listOf(BookFileEntity(id = "$bookId:file:0", bookId = bookId, rootId = "root-1", index = 0, sourcePath = "/track.mp3", sourceIdentity = "$bookId-track", displayName = "track.mp3", durationMs = 120_000L, fileSize = 1L, lastModified = 0L))
        override suspend fun getAllFilesForBookSync(bookId: String): List<BookFileEntity> = getFilesForBookSync(bookId)
        override fun updateMetadata(bookId: String, title: String?, author: String?, narrator: String?, description: String?, duration: Long) = Unit
        override fun getChapters(bookId: String): Flow<List<com.viel.aplayer.data.entity.ChapterWithBookFile>> = flowOf(emptyList())
        override suspend fun getChaptersForBookSync(bookId: String): List<com.viel.aplayer.data.entity.ChapterWithBookFile> = emptyList()
        override fun saveChapters(bookId: String, chapters: List<com.viel.aplayer.data.entity.ChapterEntity>) = Unit
        override fun getBookmarks(bookId: String): Flow<List<com.viel.aplayer.data.entity.BookmarkEntity>> = flowOf(emptyList())
        override suspend fun addBookmark(bookId: String, position: Long, title: String) = Unit
        override suspend fun updateBookmark(bookmark: com.viel.aplayer.data.entity.BookmarkEntity) = Unit
        override suspend fun deleteBookmark(bookmark: com.viel.aplayer.data.entity.BookmarkEntity) = Unit
    }

    private class FakeProgressGateway(
        initialProgress: Map<String, BookProgressEntity>
    ) : ProgressGateway {
        val progress: MutableMap<String, BookProgressEntity> = initialProgress.toMutableMap()
        override fun updateProgress(bookId: String, position: Long) = Unit
        override suspend fun saveProgress(progress: BookProgressEntity) {
            this.progress[progress.bookId] = progress
        }
        override suspend fun getLastPlayedProgressSync(): BookProgressEntity? = progress.values.maxByOrNull { it.lastPlayedAt }
        override suspend fun getProgressForBookSync(bookId: String): BookProgressEntity? = progress[bookId]
    }
}
