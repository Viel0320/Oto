package com.viel.oto.application.usecase

import com.viel.oto.abs.net.dto.AbsAuthorizeResponseDto
import com.viel.oto.abs.net.dto.AbsLibraryDto
import com.viel.oto.abs.net.dto.AbsLibraryItemDto
import com.viel.oto.abs.net.dto.AbsLibraryItemsResponseDto
import com.viel.oto.abs.net.dto.AbsLoginResponseDto
import com.viel.oto.abs.net.dto.AbsPlayRequestDto
import com.viel.oto.abs.net.dto.AbsPlaybackSessionDto
import com.viel.oto.abs.net.dto.AbsStatusDto
import com.viel.oto.abs.net.dto.AbsUserProgressDto
import com.viel.oto.abs.net.AbsApiClient
import com.viel.oto.abs.playback.AbsPlaybackSessionSyncer
import com.viel.oto.abs.playback.AbsProgressConflictCoordinator
import com.viel.oto.data.book.BookCatalogGateway
import com.viel.oto.data.book.BookMetadataGateway
import com.viel.oto.data.db.AudiobookSchema
import com.viel.oto.data.entity.BookEntity
import com.viel.oto.data.entity.BookFileEntity
import com.viel.oto.data.entity.BookProgressEntity
import com.viel.oto.data.entity.BookWithProgress
import com.viel.oto.data.progress.ProgressGateway
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Drives a real AbsProgressConflictCoordinator through minimal fakes so the use case mapping and
 * toSnapshot conversion are verified against production decision rules, not a stubbed coordinator.
 */
class ResolveProgressConflictUseCaseTest {

    @Test
    fun `non remote book maps to continue local without building a snapshot`() = runBlocking {
        val catalog = FakeBookCatalogGateway(
            book = book(id = LOCAL_BOOK_ID, sourceType = AudiobookSchema.SourceType.SINGLE_AUDIO)
        )
        val useCase = createUseCase(catalog = catalog)

        val result = useCase.preparePlayback(LOCAL_BOOK_ID)

        assertEquals(
            ResolveProgressConflictUseCase.PlaybackDecisionResult.ContinueLocal,
            result
        )
    }

    @Test
    fun `missing book maps to continue local`() = runBlocking {
        val catalog = FakeBookCatalogGateway(book = null)
        val useCase = createUseCase(catalog = catalog)

        val result = useCase.preparePlayback("unknown")

        assertEquals(
            ResolveProgressConflictUseCase.PlaybackDecisionResult.ContinueLocal,
            result
        )
    }

    @Test
    fun `in sync positions map to continue local`() = runBlocking {
        // Local and remote within the 30s drift threshold, neither finished -> resolver InSync.
        val catalog = FakeBookCatalogGateway(
            book = book(id = REMOTE_BOOK_ID, sourceType = AudiobookSchema.SourceType.ABS_REMOTE)
        )
        val progress = FakeProgressGateway(
            local = localProgress(globalPositionMs = 100_000L, lastPlayedAt = 10L)
        )
        val api = FakeAbsApiClient(
            remote = remoteDto(currentTimeSec = 100.0, isFinished = false, lastUpdate = 20L)
        )
        val useCase = createUseCase(catalog = catalog, progress = progress, api = api)

        val result = useCase.preparePlayback(REMOTE_BOOK_ID)

        assertEquals(
            ResolveProgressConflictUseCase.PlaybackDecisionResult.ContinueLocal,
            result
        )
    }

    @Test
    fun `missing local progress maps to not started in a conflict snapshot`() = runBlocking {
        // Missing local progress is normalized to 0ms, so a far-away remote position asks the user.
        val catalog = FakeBookCatalogGateway(
            book = book(
                id = REMOTE_BOOK_ID,
                title = "Remote Title",
                sourceType = AudiobookSchema.SourceType.ABS_REMOTE,
                readStatus = AudiobookSchema.ReadStatus.NOT_STARTED
            )
        )
        val progress = FakeProgressGateway(local = null)
        val api = FakeAbsApiClient(
            remote = remoteDto(currentTimeSec = 120.0, isFinished = false, lastUpdate = 777L)
        )
        val useCase = createUseCase(catalog = catalog, progress = progress, api = api)

        val result = useCase.preparePlayback(REMOTE_BOOK_ID)

        val askUser = result as ResolveProgressConflictUseCase.PlaybackDecisionResult.AskUser
        val snapshot = askUser.conflict
        assertEquals(REMOTE_BOOK_ID, snapshot.bookId)
        assertEquals("Remote Title", snapshot.bookTitle)
        assertEquals(0L, snapshot.localPositionMs)
        assertEquals(120_000L, snapshot.remotePositionMs)
        assertNull(snapshot.localUpdatedAt)
        assertEquals(777L, snapshot.remoteUpdatedAt)
        assertEquals(false, snapshot.localFinished)
        assertEquals(false, snapshot.remoteFinished)
    }

    @Test
    fun `local finished while remote not finished maps to ask user with finished flags`() = runBlocking {
        // Finished-vs-unfinished disagreement -> resolver Conflict -> AskUser.
        val catalog = FakeBookCatalogGateway(
            book = book(
                id = REMOTE_BOOK_ID,
                title = "Conflict Title",
                sourceType = AudiobookSchema.SourceType.ABS_REMOTE,
                readStatus = AudiobookSchema.ReadStatus.FINISHED
            )
        )
        val progress = FakeProgressGateway(
            local = localProgress(globalPositionMs = 5_000L, lastPlayedAt = 99L)
        )
        val api = FakeAbsApiClient(
            remote = remoteDto(currentTimeSec = 5.0, isFinished = false, lastUpdate = 100L)
        )
        val useCase = createUseCase(catalog = catalog, progress = progress, api = api)

        val result = useCase.preparePlayback(REMOTE_BOOK_ID)

        val askUser = result as ResolveProgressConflictUseCase.PlaybackDecisionResult.AskUser
        val snapshot = askUser.conflict
        assertEquals(REMOTE_BOOK_ID, snapshot.bookId)
        assertEquals("Conflict Title", snapshot.bookTitle)
        assertEquals(5_000L, snapshot.localPositionMs)
        assertEquals(5_000L, snapshot.remotePositionMs)
        assertEquals(99L, snapshot.localUpdatedAt)
        assertEquals(100L, snapshot.remoteUpdatedAt)
        assertTrue(snapshot.localFinished)
        assertEquals(false, snapshot.remoteFinished)
    }

    @Test
    fun `remote finished unknown remains nullable in conflict snapshot`() = runBlocking {
        val catalog = FakeBookCatalogGateway(
            book = book(id = REMOTE_BOOK_ID, sourceType = AudiobookSchema.SourceType.ABS_REMOTE)
        )
        val progress = FakeProgressGateway(
            local = localProgress(globalPositionMs = 0L, lastPlayedAt = 1L)
        )
        val api = FakeAbsApiClient(
            remote = remoteDto(currentTimeSec = 100.0, isFinished = null, lastUpdate = 2L)
        )
        val useCase = createUseCase(catalog = catalog, progress = progress, api = api)

        val result = useCase.preparePlayback(REMOTE_BOOK_ID)

        val askUser = result as ResolveProgressConflictUseCase.PlaybackDecisionResult.AskUser
        assertNull(askUser.conflict.remoteFinished)
    }

    @Test
    fun `divergent positions beyond threshold map to ask user`() = runBlocking {
        val catalog = FakeBookCatalogGateway(
            book = book(id = REMOTE_BOOK_ID, sourceType = AudiobookSchema.SourceType.ABS_REMOTE)
        )
        val progress = FakeProgressGateway(
            local = localProgress(globalPositionMs = 0L, lastPlayedAt = 1L)
        )
        val api = FakeAbsApiClient(
            remote = remoteDto(currentTimeSec = 100.0, isFinished = false, lastUpdate = 2L)
        )
        val useCase = createUseCase(catalog = catalog, progress = progress, api = api)

        val result = useCase.preparePlayback(REMOTE_BOOK_ID)

        assertTrue(result is ResolveProgressConflictUseCase.PlaybackDecisionResult.AskUser)
    }

    @Test
    fun `accept remote progress persists the remote candidate and read status`() = runBlocking {
        val catalog = FakeBookCatalogGateway(
            book = book(id = REMOTE_BOOK_ID, sourceType = AudiobookSchema.SourceType.ABS_REMOTE)
        )
        val progress = FakeProgressGateway(local = null)
        val metadata = FakeBookMetadataGateway()
        val api = FakeAbsApiClient(
            remote = remoteDto(currentTimeSec = 120.0, isFinished = false, lastUpdate = 777L)
        )
        val useCase = createUseCase(catalog = catalog, progress = progress, metadata = metadata, api = api)

        val askUser = useCase.preparePlayback(REMOTE_BOOK_ID)
            as ResolveProgressConflictUseCase.PlaybackDecisionResult.AskUser

        useCase.acceptRemoteProgress(askUser.conflict)

        val saved = progress.savedProgress
        assertEquals(REMOTE_BOOK_ID, saved?.bookId)
        assertEquals(120_000L, saved?.globalPositionMs)
        // remote not finished but positive position -> IN_PROGRESS read status.
        assertEquals(
            REMOTE_BOOK_ID to AudiobookSchema.ReadStatus.IN_PROGRESS,
            metadata.updatedReadStatus
        )
    }

    @Test
    fun `accept local progress delegates without error`() = runBlocking {
        val useCase = createUseCase(catalog = FakeBookCatalogGateway(book = null))

        // Coordinator only records the book id in an internal override set; no JVM-observable
        // effect is exposed through the use case, so this verifies the delegation path runs.
        useCase.acceptLocalProgress(REMOTE_BOOK_ID)
    }

    private fun createUseCase(
        catalog: FakeBookCatalogGateway,
        progress: FakeProgressGateway = FakeProgressGateway(local = null),
        metadata: FakeBookMetadataGateway = FakeBookMetadataGateway(),
        api: FakeAbsApiClient = FakeAbsApiClient(remote = null)
    ): ResolveProgressConflictUseCase {
        val coordinator = AbsProgressConflictCoordinator(
            apiClient = api,
            bookCatalogGateway = catalog,
            bookMetadataGateway = metadata,
            progressGateway = progress,
            credentialProvider = { AbsPlaybackSessionSyncer.CredentialSnapshot(baseUrl = "https://abs", token = "t") }
        )
        return ResolveProgressConflictUseCase(coordinator)
    }

    private companion object {
        // Book ids must embed ":item:" so the coordinator can extract a non-blank remote item id.
        private const val REMOTE_BOOK_ID = "cred:item:remote-1"
        private const val LOCAL_BOOK_ID = "local-1"

        private fun book(
            id: String,
            title: String = "Book",
            sourceType: AudiobookSchema.SourceType,
            readStatus: AudiobookSchema.ReadStatus = AudiobookSchema.ReadStatus.NOT_STARTED
        ): BookEntity = BookEntity(
            id = id,
            rootId = "root",
            sourceType = sourceType,
            title = title,
            readStatus = readStatus
        )

        private fun localProgress(globalPositionMs: Long, lastPlayedAt: Long): BookProgressEntity =
            BookProgressEntity(
                bookId = REMOTE_BOOK_ID,
                globalPositionMs = globalPositionMs,
                lastPlayedAt = lastPlayedAt
            )

        private fun remoteDto(currentTimeSec: Double, isFinished: Boolean?, lastUpdate: Long?): AbsUserProgressDto =
            AbsUserProgressDto(
                currentTime = currentTimeSec,
                isFinished = isFinished,
                lastUpdate = lastUpdate
            )
    }

    private class FakeBookCatalogGateway(
        private val book: BookEntity?
    ) : BookCatalogGateway {
        override val audiobooks: Flow<List<BookWithProgress>> = emptyFlow()
        override suspend fun getBookById(id: String): BookEntity? = book
        override fun observeBookById(id: String): Flow<BookEntity?> = emptyFlow()
        override fun searchAudiobooks(query: String): Flow<List<BookWithProgress>> = emptyFlow()
        override fun filterByYear(year: String): Flow<List<BookWithProgress>> = emptyFlow()
        override fun filterByAuthor(author: String): Flow<List<BookWithProgress>> = emptyFlow()
        override fun filterByAuthorLimited(author: String, excludeId: String, limit: Int): Flow<List<BookWithProgress>> = emptyFlow()
        override fun filterByNarrator(narrator: String): Flow<List<BookWithProgress>> = emptyFlow()
        override fun filterByNarratorLimited(narrator: String, excludeId: String, limit: Int): Flow<List<BookWithProgress>> = emptyFlow()
        override fun getRecentlyAdded(limit: Int): Flow<List<BookWithProgress>> = emptyFlow()
        override fun getRecentlyAddedExclusive(
            currentId: String,
            authors: List<String>,
            narrators: List<String>,
            limit: Int
        ): Flow<List<BookWithProgress>> = emptyFlow()
        override suspend fun getFilesForBookSync(bookId: String): List<BookFileEntity> = emptyList()
        override suspend fun getAllFilesForBookSync(bookId: String): List<BookFileEntity> = emptyList()
    }

    private class FakeProgressGateway(
        private val local: BookProgressEntity?
    ) : ProgressGateway {
        var savedProgress: BookProgressEntity? = null
            private set

        override fun updateProgress(bookId: String, position: Long) = Unit
        override suspend fun saveProgress(progress: BookProgressEntity) {
            savedProgress = progress
        }
        override suspend fun getLastPlayedProgressSync(): BookProgressEntity? = null
        override suspend fun getProgressForBookSync(bookId: String): BookProgressEntity? = local
    }

    private class FakeBookMetadataGateway : BookMetadataGateway {
        var updatedReadStatus: Pair<String, AudiobookSchema.ReadStatus>? = null
            private set

        override suspend fun updateBookReadStatus(bookId: String, readStatus: AudiobookSchema.ReadStatus) {
            updatedReadStatus = bookId to readStatus
        }
        override suspend fun updateBookDetails(
            id: String,
            title: String,
            author: String,
            narrator: String,
            description: String,
            year: String,
            series: String
        ) = Unit
        override fun updateMetadata(
            bookId: String,
            title: String?,
            author: String?,
            narrator: String?,
            description: String?,
            duration: Long
        ) = Unit
    }

    private class FakeAbsApiClient(
        private val remote: AbsUserProgressDto?
    ) : AbsApiClient {
        override suspend fun getProgressOrNull(baseUrl: String, token: String, itemId: String): AbsUserProgressDto? = remote

        override suspend fun status(baseUrl: String): AbsStatusDto = error("unused")
        override suspend fun login(baseUrl: String, username: String, password: String): AbsLoginResponseDto = error("unused")
        override suspend fun authorize(baseUrl: String, token: String): AbsAuthorizeResponseDto = error("unused")
        override suspend fun getLibraries(baseUrl: String, token: String): List<AbsLibraryDto> = error("unused")
        override suspend fun getLibraryItemsMinified(baseUrl: String, token: String, libraryId: String): AbsLibraryItemsResponseDto = error("unused")
        override suspend fun batchGetItems(baseUrl: String, token: String, itemIds: List<String>): List<AbsLibraryItemDto> = error("unused")
        override suspend fun openPlaybackSession(baseUrl: String, token: String, itemId: String, request: AbsPlayRequestDto): AbsPlaybackSessionDto = error("unused")
        override suspend fun syncSession(baseUrl: String, token: String, sessionId: String, currentTimeSec: Double, timeListenedSec: Double, durationSec: Double) = error("unused")
        override suspend fun closeSession(baseUrl: String, token: String, sessionId: String, currentTimeSec: Double, timeListenedSec: Double, durationSec: Double) = error("unused")
    }
}
