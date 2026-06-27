package com.viel.oto.abs.playback

import com.viel.oto.abs.net.AbsApiClient
import com.viel.oto.abs.net.dto.AbsLibraryItemDto
import com.viel.oto.abs.net.dto.AbsLibraryItemsResponseDto
import com.viel.oto.abs.net.dto.AbsPlayRequestDto
import com.viel.oto.abs.net.dto.AbsPlaybackSessionDto
import com.viel.oto.abs.net.dto.AbsStatusDto
import com.viel.oto.abs.net.dto.AbsUserProgressDto
import com.viel.oto.data.book.BookCatalogGateway
import com.viel.oto.data.book.BookMetadataGateway
import com.viel.oto.data.db.AudiobookSchema
import com.viel.oto.data.entity.BookEntity
import com.viel.oto.data.entity.BookFileEntity
import com.viel.oto.data.entity.BookProgressEntity
import com.viel.oto.data.entity.BookWithProgress
import com.viel.oto.data.progress.ProgressGateway
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies remote/local progress arbitration: Decision-to-PlaybackDecision mapping, accept paths,
 * local-override short circuits, and the remote item id parsing rule.
 */
class AbsProgressConflictCoordinatorTest {

    private val credential = AbsPlaybackSessionSyncer.CredentialSnapshot(baseUrl = "https://abs.example.com", token = "token-1")

    // --- preparePlayback Decision -> PlaybackDecision mapping ---

    @Test
    fun `non abs book continues local without probing remote`() = runBlocking {
        val book = absBook(id = "local-1", sourceType = AudiobookSchema.SourceType.SINGLE_AUDIO)
        val api = FakeApiClient(progress = AbsUserProgressDto(currentTime = 500.0, lastUpdate = 10L))
        val coordinator = coordinator(api = api, books = listOf(book))

        val decision = coordinator.preparePlayback("local-1")

        assertTrue(decision is AbsProgressConflictCoordinator.PlaybackDecision.ContinueLocal)
        assertEquals(0, api.getProgressCalls)
    }

    @Test
    fun `unknown book continues local`() = runBlocking {
        val coordinator = coordinator(api = FakeApiClient(progress = null), books = emptyList())
        val decision = coordinator.preparePlayback("missing")
        assertTrue(decision is AbsProgressConflictCoordinator.PlaybackDecision.ContinueLocal)
    }

    @Test
    fun `book id without item delimiter continues local`() = runBlocking {
        // remoteItemId() returns blank when ":item:" is absent, so buildConflictSnapshot bails out.
        val book = absBook(id = "abs:server:no-item", sourceType = AudiobookSchema.SourceType.ABS_REMOTE)
        val api = FakeApiClient(progress = AbsUserProgressDto(currentTime = 500.0, lastUpdate = 10L))
        val coordinator = coordinator(api = api, books = listOf(book))

        val decision = coordinator.preparePlayback("abs:server:no-item")

        assertTrue(decision is AbsProgressConflictCoordinator.PlaybackDecision.ContinueLocal)
        assertEquals(0, api.getProgressCalls)
    }

    @Test
    fun `remote missing progress continues local`() = runBlocking {
        val book = absRemoteBook()
        val api = FakeApiClient(progress = null)
        val coordinator = coordinator(api = api, books = listOf(book))

        val decision = coordinator.preparePlayback(book.id)

        assertTrue(decision is AbsProgressConflictCoordinator.PlaybackDecision.ContinueLocal)
        assertEquals(1, api.getProgressCalls)
    }

    @Test
    fun `local missing asks user when remote is beyond not-started threshold`() = runBlocking {
        val book = absRemoteBook()
        // Missing local progress is a 0ms checkpoint, so a far-away remote position becomes a conflict.
        val api = FakeApiClient(progress = AbsUserProgressDto(currentTime = 600.0, lastUpdate = 99L))
        val coordinator = coordinator(api = api, books = listOf(book), localProgress = null)

        val decision = coordinator.preparePlayback(book.id)

        assertTrue(decision is AbsProgressConflictCoordinator.PlaybackDecision.AskUser)
        val conflict = (decision as AbsProgressConflictCoordinator.PlaybackDecision.AskUser).conflict
        assertEquals(book.id, conflict.book.id)
        assertEquals("item-1", conflict.remoteItemId)
        // remote currentTime 600s -> 600_000ms
        assertEquals(600_000L, conflict.remoteProgress.globalPositionMs)
    }

    @Test
    fun `local missing and near-start remote continues local`() = runBlocking {
        val book = absRemoteBook()
        val api = FakeApiClient(progress = AbsUserProgressDto(currentTime = 10.0, lastUpdate = 99L))
        val coordinator = coordinator(api = api, books = listOf(book), localProgress = null)

        val decision = coordinator.preparePlayback(book.id)

        assertTrue(decision is AbsProgressConflictCoordinator.PlaybackDecision.ContinueLocal)
    }

    @Test
    fun `near equal positions stays in sync and continues local`() = runBlocking {
        val book = absRemoteBook()
        // remote 600s = 600_000ms, local 605_000ms -> delta 5_000ms <= 30_000ms threshold -> InSync
        val api = FakeApiClient(progress = AbsUserProgressDto(currentTime = 600.0, lastUpdate = 99L))
        val coordinator = coordinator(
            api = api,
            books = listOf(book),
            localProgress = progress(book.id, positionMs = 605_000L)
        )

        val decision = coordinator.preparePlayback(book.id)

        assertTrue(decision is AbsProgressConflictCoordinator.PlaybackDecision.ContinueLocal)
    }

    @Test
    fun `divergent positions ask user`() = runBlocking {
        val book = absRemoteBook()
        // remote 600_000ms, local 60_000ms -> delta 540_000ms > 30_000ms -> Conflict -> AskUser
        val api = FakeApiClient(progress = AbsUserProgressDto(currentTime = 600.0, lastUpdate = 99L))
        val coordinator = coordinator(
            api = api,
            books = listOf(book),
            localProgress = progress(book.id, positionMs = 60_000L)
        )

        val decision = coordinator.preparePlayback(book.id)

        assertTrue(decision is AbsProgressConflictCoordinator.PlaybackDecision.AskUser)
    }

    @Test
    fun `finished mismatch asks user even when positions match`() = runBlocking {
        val book = absRemoteBook(readStatus = AudiobookSchema.ReadStatus.FINISHED)
        // positions equal but local FINISHED vs remote not finished -> Conflict -> AskUser
        val api = FakeApiClient(progress = AbsUserProgressDto(currentTime = 600.0, isFinished = false, lastUpdate = 99L))
        val coordinator = coordinator(
            api = api,
            books = listOf(book),
            localProgress = progress(book.id, positionMs = 600_000L)
        )

        val decision = coordinator.preparePlayback(book.id)

        assertTrue(decision is AbsProgressConflictCoordinator.PlaybackDecision.AskUser)
        val conflict = (decision as AbsProgressConflictCoordinator.PlaybackDecision.AskUser).conflict
        assertEquals(false, conflict.remoteIsFinished)
    }

    @Test
    fun `missing credential continues local`() = runBlocking {
        val book = absRemoteBook()
        val api = FakeApiClient(progress = AbsUserProgressDto(currentTime = 600.0, lastUpdate = 1L))
        val coordinator = coordinator(api = api, books = listOf(book), credential = null)

        val decision = coordinator.preparePlayback(book.id)

        assertTrue(decision is AbsProgressConflictCoordinator.PlaybackDecision.ContinueLocal)
        assertEquals(0, api.getProgressCalls)
    }

    // --- acceptRemoteProgress ---

    @Test
    fun `accept remote saves progress and updates read status when finished known`() = runBlocking {
        val book = absRemoteBook()
        val progressGateway = FakeProgressGateway()
        val metadataGateway = FakeMetadataGateway()
        val coordinator = coordinator(
            api = FakeApiClient(progress = null),
            books = listOf(book),
            progressGateway = progressGateway,
            metadataGateway = metadataGateway
        )
        val conflict = AbsProgressConflictCoordinator.ProgressConflict(
            book = book,
            localProgress = null,
            remoteProgress = progress(book.id, positionMs = 120_000L),
            remoteIsFinished = true,
            remoteItemId = "item-1"
        )

        coordinator.acceptRemoteProgress(conflict)

        assertEquals(120_000L, progressGateway.saved[book.id]?.globalPositionMs)
        // isFinished true -> FINISHED read status
        assertEquals(AudiobookSchema.ReadStatus.FINISHED, metadataGateway.readStatus[book.id])
    }

    @Test
    fun `accept remote skips read status update when finished unknown`() = runBlocking {
        val book = absRemoteBook()
        val progressGateway = FakeProgressGateway()
        val metadataGateway = FakeMetadataGateway()
        val coordinator = coordinator(
            api = FakeApiClient(progress = null),
            books = listOf(book),
            progressGateway = progressGateway,
            metadataGateway = metadataGateway
        )
        val conflict = AbsProgressConflictCoordinator.ProgressConflict(
            book = book,
            localProgress = null,
            remoteProgress = progress(book.id, positionMs = 120_000L),
            remoteIsFinished = null,
            remoteItemId = "item-1"
        )

        coordinator.acceptRemoteProgress(conflict)

        assertEquals(120_000L, progressGateway.saved[book.id]?.globalPositionMs)
        assertNull(metadataGateway.readStatus[book.id])
    }

    @Test
    fun `accept remote with finished false and positive position becomes in progress`() = runBlocking {
        val book = absRemoteBook()
        val progressGateway = FakeProgressGateway()
        val metadataGateway = FakeMetadataGateway()
        val coordinator = coordinator(
            api = FakeApiClient(progress = null),
            books = listOf(book),
            progressGateway = progressGateway,
            metadataGateway = metadataGateway
        )
        val conflict = AbsProgressConflictCoordinator.ProgressConflict(
            book = book,
            localProgress = null,
            remoteProgress = progress(book.id, positionMs = 5_000L),
            remoteIsFinished = false,
            remoteItemId = "item-1"
        )

        coordinator.acceptRemoteProgress(conflict)

        assertEquals(AudiobookSchema.ReadStatus.IN_PROGRESS, metadataGateway.readStatus[book.id])
    }

    // --- resolveUploadDecision / local override short circuits ---

    @Test
    fun `non abs book uploads allowed without probe`() = runBlocking {
        val book = absBook(id = "local-1", sourceType = AudiobookSchema.SourceType.SINGLE_AUDIO)
        val api = FakeApiClient(progress = AbsUserProgressDto(currentTime = 1.0))
        val coordinator = coordinator(api = api, books = listOf(book))

        val decision = coordinator.resolveUploadDecision(book, progress("local-1", 1L), credential)

        assertEquals(AbsProgressConflictCoordinator.UploadDecision.Allow, decision)
        assertEquals(0, api.getProgressCalls)
    }

    @Test
    fun `accepted local override allows upload without probe`() = runBlocking {
        val book = absRemoteBook()
        val api = FakeApiClient(progress = AbsUserProgressDto(currentTime = 600.0, lastUpdate = 99L))
        val coordinator = coordinator(api = api, books = listOf(book))

        coordinator.acceptLocalProgress(book.id)
        val decision = coordinator.resolveUploadDecision(book, progress(book.id, 60_000L), credential)

        assertEquals(AbsProgressConflictCoordinator.UploadDecision.Allow, decision)
        assertEquals(0, api.getProgressCalls)
    }

    @Test
    fun `clear local override re-enables remote probe`() = runBlocking {
        val book = absRemoteBook()
        // divergent remote so a probe would yield Conflict for this stale local upload
        val api = FakeApiClient(progress = AbsUserProgressDto(currentTime = 600.0, lastUpdate = 999L))
        val coordinator = coordinator(api = api, books = listOf(book))

        coordinator.acceptLocalProgress(book.id)
        coordinator.clearLocalOverride(book.id)
        val decision = coordinator.resolveUploadDecision(
            book,
            progress(book.id, positionMs = 60_000L, lastPlayedAt = 1L),
            credential
        )

        assertEquals(1, api.getProgressCalls)
        assertEquals(AbsProgressConflictCoordinator.UploadDecision.Conflict, decision)
    }

    @Test
    fun `book without remote item id allows upload`() = runBlocking {
        val book = absBook(id = "abs:server:no-item", sourceType = AudiobookSchema.SourceType.ABS_REMOTE)
        val api = FakeApiClient(progress = AbsUserProgressDto(currentTime = 600.0))
        val coordinator = coordinator(api = api, books = listOf(book))

        val decision = coordinator.resolveUploadDecision(book, progress(book.id, 1L), credential)

        assertEquals(AbsProgressConflictCoordinator.UploadDecision.Allow, decision)
        assertEquals(0, api.getProgressCalls)
    }

    @Test
    fun `remote probe failure surfaces RemoteProbeFailed`() = runBlocking {
        val book = absRemoteBook()
        val api = FakeApiClient(progress = null, throwOnGetProgress = true)
        val coordinator = coordinator(api = api, books = listOf(book))

        val decision = coordinator.resolveUploadDecision(book, progress(book.id, 60_000L), credential)

        assertEquals(AbsProgressConflictCoordinator.UploadDecision.RemoteProbeFailed, decision)
    }

    @Test
    fun `remote missing allows upload`() = runBlocking {
        val book = absRemoteBook()
        val api = FakeApiClient(progress = null)
        val coordinator = coordinator(api = api, books = listOf(book))

        val decision = coordinator.resolveUploadDecision(book, progress(book.id, 60_000L), credential)

        assertEquals(AbsProgressConflictCoordinator.UploadDecision.Allow, decision)
    }

    @Test
    fun `conflict with newer local allows upload`() = runBlocking {
        val book = absRemoteBook()
        // divergent positions but local lastPlayedAt newer than remote lastUpdate -> shouldUploadLocalProgress true
        val api = FakeApiClient(progress = AbsUserProgressDto(currentTime = 600.0, lastUpdate = 10L))
        val coordinator = coordinator(api = api, books = listOf(book))

        val decision = coordinator.resolveUploadDecision(
            book,
            progress(book.id, positionMs = 60_000L, lastPlayedAt = 5_000L),
            credential
        )

        assertEquals(AbsProgressConflictCoordinator.UploadDecision.Allow, decision)
    }

    @Test
    fun `shouldUploadLocalProgress boolean mirrors allow decision`() = runBlocking {
        val book = absRemoteBook()
        val api = FakeApiClient(progress = AbsUserProgressDto(currentTime = 600.0, lastUpdate = 999L))
        val coordinator = coordinator(api = api, books = listOf(book))

        val allowed = coordinator.shouldUploadLocalProgress(
            book,
            progress(book.id, positionMs = 60_000L, lastPlayedAt = 1L),
            credential
        )

        assertEquals(false, allowed)
    }

    // --- helpers / fakes ---

    private fun coordinator(
        api: AbsApiClient,
        books: List<BookEntity>,
        localProgress: BookProgressEntity? = null,
        credential: AbsPlaybackSessionSyncer.CredentialSnapshot? = this.credential,
        progressGateway: ProgressGateway = FakeProgressGateway(localProgress?.let { mapOf(it.bookId to it) } ?: emptyMap()),
        metadataGateway: BookMetadataGateway = FakeMetadataGateway()
    ): AbsProgressConflictCoordinator {
        val catalog = FakeCatalogGateway(books)
        return AbsProgressConflictCoordinator(
            apiClient = api,
            bookCatalogGateway = catalog,
            bookMetadataGateway = metadataGateway,
            progressGateway = progressGateway,
            credentialProvider = { credential }
        )
    }

    private fun absRemoteBook(readStatus: AudiobookSchema.ReadStatus = AudiobookSchema.ReadStatus.IN_PROGRESS): BookEntity =
        absBook(id = "abs:server:item:item-1", sourceType = AudiobookSchema.SourceType.ABS_REMOTE, readStatus = readStatus)

    private fun absBook(
        id: String,
        sourceType: AudiobookSchema.SourceType,
        readStatus: AudiobookSchema.ReadStatus = AudiobookSchema.ReadStatus.IN_PROGRESS
    ): BookEntity = BookEntity(
        id = id,
        rootId = "root-1",
        sourceType = sourceType,
        title = "Book",
        totalDurationMs = 1_200_000L,
        readStatus = readStatus
    )

    private fun progress(bookId: String, positionMs: Long, lastPlayedAt: Long = 0L): BookProgressEntity =
        BookProgressEntity(bookId = bookId, globalPositionMs = positionMs, lastPlayedAt = lastPlayedAt)

    private class FakeApiClient(
        private val progress: AbsUserProgressDto?,
        private val throwOnGetProgress: Boolean = false
    ) : AbsApiClient {
        var getProgressCalls: Int = 0

        override suspend fun status(baseUrl: String): AbsStatusDto = AbsStatusDto(serverVersion = "2.35.1")
        override suspend fun login(baseUrl: String, username: String, password: String) = throw UnsupportedOperationException()
        override suspend fun authorize(baseUrl: String, token: String) = throw UnsupportedOperationException()
        override suspend fun getLibraries(baseUrl: String, token: String) = emptyList<com.viel.oto.abs.net.dto.AbsLibraryDto>()
        override suspend fun getLibraryItemsMinified(baseUrl: String, token: String, libraryId: String): AbsLibraryItemsResponseDto =
            AbsLibraryItemsResponseDto()
        override suspend fun batchGetItems(baseUrl: String, token: String, itemIds: List<String>): List<AbsLibraryItemDto> = emptyList()
        override suspend fun getProgressOrNull(baseUrl: String, token: String, itemId: String): AbsUserProgressDto? {
            getProgressCalls++
            if (throwOnGetProgress) throw RuntimeException("probe failed")
            return progress
        }
        override suspend fun openPlaybackSession(baseUrl: String, token: String, itemId: String, request: AbsPlayRequestDto): AbsPlaybackSessionDto =
            throw UnsupportedOperationException()
        override suspend fun syncSession(baseUrl: String, token: String, sessionId: String, currentTimeSec: Double, timeListenedSec: Double, durationSec: Double) = Unit
        override suspend fun closeSession(baseUrl: String, token: String, sessionId: String, currentTimeSec: Double, timeListenedSec: Double, durationSec: Double) = Unit
    }

    private class FakeCatalogGateway(books: List<BookEntity>) : BookCatalogGateway {
        private val byId = books.associateBy { it.id }
        override val audiobooks: Flow<List<BookWithProgress>> = flowOf(emptyList())
        override suspend fun getBookById(id: String): BookEntity? = byId[id]
        override fun observeBookById(id: String): Flow<BookEntity?> = flowOf(byId[id])
        override fun searchAudiobooks(query: String): Flow<List<BookWithProgress>> = flowOf(emptyList())
        override fun filterByYear(year: String): Flow<List<BookWithProgress>> = flowOf(emptyList())
        override fun filterByAuthor(author: String): Flow<List<BookWithProgress>> = flowOf(emptyList())
        override fun filterByAuthorLimited(author: String, excludeId: String, limit: Int): Flow<List<BookWithProgress>> = flowOf(emptyList())
        override fun filterByNarrator(narrator: String): Flow<List<BookWithProgress>> = flowOf(emptyList())
        override fun filterByNarratorLimited(narrator: String, excludeId: String, limit: Int): Flow<List<BookWithProgress>> = flowOf(emptyList())
        override fun getRecentlyAdded(limit: Int): Flow<List<BookWithProgress>> = flowOf(emptyList())
        override fun getRecentlyAddedExclusive(currentId: String, authors: List<String>, narrators: List<String>, limit: Int): Flow<List<BookWithProgress>> = flowOf(emptyList())
        override suspend fun getFilesForBookSync(bookId: String): List<BookFileEntity> = emptyList()
        override suspend fun getAllFilesForBookSync(bookId: String): List<BookFileEntity> = emptyList()
    }

    private class FakeMetadataGateway : BookMetadataGateway {
        val readStatus: MutableMap<String, AudiobookSchema.ReadStatus> = mutableMapOf()
        override suspend fun updateBookReadStatus(bookId: String, readStatus: AudiobookSchema.ReadStatus) {
            this.readStatus[bookId] = readStatus
        }
        override suspend fun updateBookDetails(id: String, title: String, author: String, narrator: String, description: String, year: String, series: String) = Unit
        override fun updateMetadata(bookId: String, title: String?, author: String?, narrator: String?, description: String?, duration: Long) = Unit
    }

    private class FakeProgressGateway(initial: Map<String, BookProgressEntity> = emptyMap()) : ProgressGateway {
        val saved: MutableMap<String, BookProgressEntity> = initial.toMutableMap()
        override fun updateProgress(bookId: String, position: Long) = Unit
        override suspend fun saveProgress(progress: BookProgressEntity) {
            saved[progress.bookId] = progress
        }
        override suspend fun getLastPlayedProgressSync(): BookProgressEntity? = saved.values.maxByOrNull { it.lastPlayedAt }
        override suspend fun getProgressForBookSync(bookId: String): BookProgressEntity? = saved[bookId]
    }
}
