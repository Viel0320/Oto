package com.viel.aplayer.abs

import com.viel.aplayer.abs.mapping.AbsProgressConflictResolver
import com.viel.aplayer.abs.net.AbsApiClient
import com.viel.aplayer.abs.net.dto.AbsAuthorizeResponseDto
import com.viel.aplayer.abs.net.dto.AbsAuthorizedUserDto
import com.viel.aplayer.abs.net.dto.AbsLibraryDto
import com.viel.aplayer.abs.net.dto.AbsLibraryItemDto
import com.viel.aplayer.abs.net.dto.AbsLibraryItemsResponseDto
import com.viel.aplayer.abs.net.dto.AbsPlayRequestDto
import com.viel.aplayer.abs.net.dto.AbsPlaybackSessionDto
import com.viel.aplayer.abs.net.dto.AbsStatusDto
import com.viel.aplayer.abs.net.dto.AbsTrackDto
import com.viel.aplayer.abs.net.dto.AbsUserProgressDto
import com.viel.aplayer.abs.playback.AbsPendingProgressSyncDao
import com.viel.aplayer.abs.playback.AbsPendingProgressSyncEntity
import com.viel.aplayer.abs.playback.AbsPlaybackSessionDao
import com.viel.aplayer.abs.playback.AbsPlaybackSessionEntity
import com.viel.aplayer.abs.playback.AbsPlaybackSessionSyncer
import com.viel.aplayer.abs.playback.AbsProgressConflictCoordinator
import com.viel.aplayer.abs.sync.AbsCatalogStore
import com.viel.aplayer.abs.sync.AbsItemMirrorEntity
import com.viel.aplayer.abs.sync.AbsSyncStateEntity
import com.viel.aplayer.data.db.AudiobookSchema
import com.viel.aplayer.data.entity.BookEntity
import com.viel.aplayer.data.entity.BookFileEntity
import com.viel.aplayer.data.entity.BookProgressEntity
import com.viel.aplayer.data.entity.BookWithProgress
import com.viel.aplayer.data.entity.BookmarkEntity
import com.viel.aplayer.data.entity.ChapterEntity
import com.viel.aplayer.data.entity.ChapterWithBookFile
import com.viel.aplayer.data.gateway.BookCatalogGateway
import com.viel.aplayer.data.gateway.BookDeletionGateway
import com.viel.aplayer.data.gateway.BookMetadataGateway
import com.viel.aplayer.data.gateway.BookmarkGateway
import com.viel.aplayer.data.gateway.ChapterGateway
import com.viel.aplayer.data.gateway.ProgressGateway
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AbsPlaybackSessionStage4Test {

    @Test
    fun `open sync close should only rely on 2xx and plain OK`() = kotlinx.coroutines.runBlocking {
        val playbackSessionDao = FakePlaybackSessionDao()
        val pendingDao = FakePendingSyncDao()
        val syncer = AbsPlaybackSessionSyncer(
            apiClient = SuccessfulPlaybackApi(),
            absPlaybackSessionDao = playbackSessionDao,
            absPendingProgressSyncDao = pendingDao,
            catalogStore = FakeCatalogStore(),
            credentialProvider = { AbsPlaybackSessionSyncer.CredentialSnapshot("https://example.com/audiobookshelf", "token-1") }
        )
        val book = absBook()

        syncer.openSession(book, remoteItemId = "item-1")
        syncer.syncProgress(
            book = book,
            progress = BookProgressEntity(bookId = book.id, globalPositionMs = 12500L, lastPlayedAt = 1000L),
            durationMs = 20000L
        )
        syncer.closeSession(
            book = book,
            progress = BookProgressEntity(bookId = book.id, globalPositionMs = 12500L, lastPlayedAt = 1000L),
            durationMs = 20000L
        )

        assertNull(playbackSessionDao.getByBookId(book.id))
        assertNull(pendingDao.getByBookId(book.id))
    }

    @Test
    fun `failed sync should persist pending progress without breaking local flow`() = kotlinx.coroutines.runBlocking {
        val playbackSessionDao = FakePlaybackSessionDao().apply {
            saved[absBook().id] = AbsPlaybackSessionEntity(
                bookId = absBook().id,
                remoteItemId = "item-1",
                sessionId = "session-1",
                currentTimeSec = 0.0,
                timeListenedSec = 0.0,
                state = "OPEN"
            )
        }
        val pendingDao = FakePendingSyncDao()
        val syncer = AbsPlaybackSessionSyncer(
            apiClient = FailingSyncPlaybackApi(),
            absPlaybackSessionDao = playbackSessionDao,
            absPendingProgressSyncDao = pendingDao,
            catalogStore = FakeCatalogStore(),
            credentialProvider = { AbsPlaybackSessionSyncer.CredentialSnapshot("https://example.com/audiobookshelf", "token-1") }
        )
        val book = absBook()

        syncer.syncProgress(
            book = book,
            progress = BookProgressEntity(bookId = book.id, globalPositionMs = 12500L, lastPlayedAt = 1000L),
            durationMs = 20000L
        )

        val pending = pendingDao.getByBookId(book.id)
        assertNotNull(pending)
        assertEquals(12.5, pending!!.currentTimeSec, 0.0001)
    }

    @Test
    fun `open session should be idempotent while local session is active`() = kotlinx.coroutines.runBlocking {
        val playbackSessionDao = FakePlaybackSessionDao()
        val api = SuccessfulPlaybackApi()
        val syncer = AbsPlaybackSessionSyncer(
            apiClient = api,
            absPlaybackSessionDao = playbackSessionDao,
            absPendingProgressSyncDao = FakePendingSyncDao(),
            catalogStore = FakeCatalogStore(),
            credentialProvider = { AbsPlaybackSessionSyncer.CredentialSnapshot("https://example.com/audiobookshelf", "token-1") }
        )
        val book = absBook()

        syncer.openSession(book, remoteItemId = "item-1")
        syncer.openSession(book, remoteItemId = "item-1")

        // Idempotent Open Regression (Prevents resume commands from creating duplicate ABS server sessions)
        // PlaybackManager may ensure a session when local play resumes, so the syncer must no-op when a session row is already active.
        assertEquals(1, api.openCallCount)
        assertNotNull(playbackSessionDao.getByBookId(book.id))
    }

    @Test
    fun `sync should skip upload when remote progress conflicts`() = kotlinx.coroutines.runBlocking {
        // Remote Conflict Upload Guard (Verifies live sync does not overwrite a divergent ABS checkpoint)
        // The server position is intentionally far ahead of the local position, so the sync call must be skipped instead of queued or sent.
        val book = absBook()
        val api = SuccessfulPlaybackApi(remoteProgress = AbsUserProgressDto(currentTime = 100.0, lastUpdate = 3000L))
        val playbackSessionDao = FakePlaybackSessionDao().apply {
            saved[book.id] = AbsPlaybackSessionEntity(
                bookId = book.id,
                remoteItemId = "item-1",
                sessionId = "session-1",
                currentTimeSec = 0.0,
                timeListenedSec = 0.0,
                state = "OPEN"
            )
        }
        val pendingDao = FakePendingSyncDao()
        val coordinator = conflictCoordinator(
            api = api,
            book = book,
            localProgress = BookProgressEntity(bookId = book.id, globalPositionMs = 12_500L, lastPlayedAt = 2_000L)
        )
        val syncer = AbsPlaybackSessionSyncer(
            apiClient = api,
            absPlaybackSessionDao = playbackSessionDao,
            absPendingProgressSyncDao = pendingDao,
            catalogStore = FakeCatalogStore(),
            credentialProvider = { AbsPlaybackSessionSyncer.CredentialSnapshot("https://example.com/audiobookshelf", "token-1") },
            progressConflictCoordinator = coordinator
        )

        syncer.syncProgress(
            book = book,
            progress = BookProgressEntity(bookId = book.id, globalPositionMs = 12_500L, lastPlayedAt = 2_000L),
            durationMs = 200_000L
        )

        assertEquals(0, api.syncCallCount)
        assertNull(pendingDao.getByBookId(book.id))
    }

    @Test
    fun `pending flush should discard stale retry when remote progress conflicts`() = kotlinx.coroutines.runBlocking {
        // Pending Conflict Disposal (Prevents stale retry payloads from overwriting newer server progress)
        // Opening a new session flushes pending rows, and this test confirms the retry row is deleted once a real remote conflict is detected.
        val book = absBook()
        val api = SuccessfulPlaybackApi(remoteProgress = AbsUserProgressDto(currentTime = 100.0, lastUpdate = 3000L))
        val playbackSessionDao = FakePlaybackSessionDao()
        val pendingDao = FakePendingSyncDao().apply {
            insertOrReplace(
                AbsPendingProgressSyncEntity(
                    bookId = book.id,
                    remoteItemId = "item-1",
                    currentTimeSec = 12.5,
                    timeListenedSec = 12.5,
                    durationSec = 200.0,
                    updatedAt = 2_000L
                )
            )
        }
        val coordinator = conflictCoordinator(
            api = api,
            book = book,
            localProgress = BookProgressEntity(bookId = book.id, globalPositionMs = 12_500L, lastPlayedAt = 2_000L)
        )
        val syncer = AbsPlaybackSessionSyncer(
            apiClient = api,
            absPlaybackSessionDao = playbackSessionDao,
            absPendingProgressSyncDao = pendingDao,
            catalogStore = FakeCatalogStore(),
            credentialProvider = { AbsPlaybackSessionSyncer.CredentialSnapshot("https://example.com/audiobookshelf", "token-1") },
            progressConflictCoordinator = coordinator
        )

        syncer.openSession(book, remoteItemId = "item-1")

        assertEquals(0, api.syncCallCount)
        assertNull(pendingDao.getByBookId(book.id))
    }

    @Test
    fun `conflict resolver should prefer remote only when newer and not currently playing`() {
        val resolver = AbsProgressConflictResolver()
        val local = BookProgressEntity(bookId = "book-1", globalPositionMs = 1000L, lastPlayedAt = 2000L)
        val remoteOld = AbsUserProgressDto(currentTime = 5.0, lastUpdate = 1000L)
        val remoteNew = AbsUserProgressDto(currentTime = 5.0, lastUpdate = 3000L)

        assertFalse(resolver.shouldApplyRemoteProgress(local, remoteOld, isCurrentlyPlaying = false))
        assertTrue(resolver.shouldApplyRemoteProgress(local, remoteNew, isCurrentlyPlaying = false))
        assertFalse(resolver.shouldApplyRemoteProgress(local, remoteNew, isCurrentlyPlaying = true))
    }

    @Test
    fun `remote progress download should use newer timestamp even when positions diverge`() {
        val resolver = AbsProgressConflictResolver()
        val local = BookProgressEntity(bookId = "book-1", globalPositionMs = 1_000L, lastPlayedAt = 2_000L)
        val remoteNew = AbsUserProgressDto(currentTime = 100.0, lastUpdate = 3_000L)

        // Newer Remote Authority (Documents cross-device progress download semantics)
        // A large position gap alone must not block download when the server checkpoint was updated after the local checkpoint.
        assertTrue(resolver.shouldApplyRemoteProgress(local, remoteNew, isCurrentlyPlaying = false))
    }

    @Test
    fun `sync should upload newer local progress even when remote position diverges`() = kotlinx.coroutines.runBlocking {
        val book = absBook()
        val api = SuccessfulPlaybackApi(remoteProgress = AbsUserProgressDto(currentTime = 100.0, lastUpdate = 3_000L))
        val playbackSessionDao = FakePlaybackSessionDao().apply {
            saved[book.id] = AbsPlaybackSessionEntity(
                bookId = book.id,
                remoteItemId = "item-1",
                sessionId = "session-1",
                currentTimeSec = 0.0,
                timeListenedSec = 0.0,
                state = "OPEN"
            )
        }
        val pendingDao = FakePendingSyncDao()
        val coordinator = conflictCoordinator(
            api = api,
            book = book,
            localProgress = BookProgressEntity(bookId = book.id, globalPositionMs = 125_000L, lastPlayedAt = 4_000L)
        )
        val syncer = AbsPlaybackSessionSyncer(
            apiClient = api,
            absPlaybackSessionDao = playbackSessionDao,
            absPendingProgressSyncDao = pendingDao,
            catalogStore = FakeCatalogStore(),
            credentialProvider = { AbsPlaybackSessionSyncer.CredentialSnapshot("https://example.com/audiobookshelf", "token-1") },
            progressConflictCoordinator = coordinator
        )

        syncer.syncProgress(
            book = book,
            progress = BookProgressEntity(bookId = book.id, globalPositionMs = 125_000L, lastPlayedAt = 4_000L),
            durationMs = 200_000L
        )

        // Newer Local Authority (Documents upload arbitration beyond position delta)
        // Playback creates a fresh local checkpoint, so an older remote timestamp must not block the session sync request.
        assertEquals(1, api.syncCallCount)
        assertNull(pendingDao.getByBookId(book.id))
    }

    @Test
    fun `conflict resolver should tolerate thirty seconds of progress drift`() {
        // Thirty-Second Drift Tolerance (Documents the maximum accepted local-vs-remote progress delta)
        // Exactly thirty seconds is treated as in-sync, while any larger delta requires explicit conflict handling.
        val resolver = AbsProgressConflictResolver()
        val local = BookProgressEntity(bookId = "book-1", globalPositionMs = 60_000L, lastPlayedAt = 2_000L)

        assertEquals(
            AbsProgressConflictResolver.Decision.InSync,
            resolver.resolve(local, AbsUserProgressDto(currentTime = 90.0, lastUpdate = 3_000L))
        )
        assertEquals(
            AbsProgressConflictResolver.Decision.Conflict,
            resolver.resolve(local, AbsUserProgressDto(currentTime = 90.001, lastUpdate = 3_000L))
        )
    }

    private fun absBook() = BookEntity(
        id = "abs:server:item:item-1",
        rootId = "root-1",
        sourceType = AudiobookSchema.SourceType.ABS_REMOTE,
        sourceRoot = "https://example.com/audiobookshelf",
        title = "ABS Book"
    )

    private fun conflictCoordinator(
        api: AbsApiClient,
        book: BookEntity,
        localProgress: BookProgressEntity?
    ) = AbsProgressConflictCoordinator(
        // Conflict Coordinator Fixture (Assembles the production arbitration service with minimal fake gateways)
        // The fakes provide only the progress and book snapshots needed by upload and pending-flush decisions.
        apiClient = api,
        // Conflict Gateway Fixture (Reuse fake across split catalog and metadata seams)
        // The coordinator reads book/file data from catalog and updates readStatus only through metadata on remote acceptance.
        bookCatalogGateway = FakeBookQueryGateway(book),
        bookMetadataGateway = FakeBookQueryGateway(book),
        progressGateway = FakeProgressGateway(localProgress),
        credentialProvider = { AbsPlaybackSessionSyncer.CredentialSnapshot("https://example.com/audiobookshelf", "token-1") }
    )

    private class SuccessfulPlaybackApi(
        private val remoteProgress: AbsUserProgressDto? = null
    ) : AbsApiClient {
        var openCallCount = 0
        var syncCallCount = 0
        override suspend fun status(baseUrl: String) = AbsStatusDto(serverVersion = "2.35.1", isInit = true)
        override suspend fun login(baseUrl: String, username: String, password: String) = throw UnsupportedOperationException()
        override suspend fun authorize(baseUrl: String, token: String) =
            AbsAuthorizeResponseDto(user = AbsAuthorizedUserDto(id = "user-1", username = "demo", token = token))
        override suspend fun getLibraries(baseUrl: String, token: String) = emptyList<AbsLibraryDto>()
        override suspend fun getLibraryItemsMinified(baseUrl: String, token: String, libraryId: String) = AbsLibraryItemsResponseDto()
        override suspend fun batchGetItems(baseUrl: String, token: String, itemIds: List<String>) = emptyList<AbsLibraryItemDto>()
        override suspend fun getProgressOrNull(baseUrl: String, token: String, itemId: String): AbsUserProgressDto? = remoteProgress
        override suspend fun openPlaybackSession(baseUrl: String, token: String, itemId: String, request: AbsPlayRequestDto): AbsPlaybackSessionDto {
            openCallCount += 1
            return AbsPlaybackSessionDto(id = "session-1", libraryItemId = itemId, audioTracks = listOf(AbsTrackDto(index = 1, contentUrl = "/hls/session-1/output.m3u8")))
        }
        override suspend fun syncSession(baseUrl: String, token: String, sessionId: String, currentTimeSec: Double, timeListenedSec: Double, durationSec: Double) {
            syncCallCount += 1
        }
        override suspend fun closeSession(baseUrl: String, token: String, sessionId: String, currentTimeSec: Double, timeListenedSec: Double, durationSec: Double) = Unit
    }

    private class FakePlaybackSessionDao : AbsPlaybackSessionDao {
        val saved = linkedMapOf<String, AbsPlaybackSessionEntity>()
        override suspend fun insertOrReplace(session: AbsPlaybackSessionEntity) {
            saved[session.bookId] = session
        }
        override suspend fun getByBookId(bookId: String): AbsPlaybackSessionEntity? = saved[bookId]
        override suspend fun deleteByBookId(bookId: String) {
            saved.remove(bookId)
        }
        override suspend fun deleteByBookIds(bookIds: List<String>) {
            bookIds.forEach { id -> saved.remove(id) }
        }
    }

    private class FakePendingSyncDao : AbsPendingProgressSyncDao {
        private val saved = linkedMapOf<String, AbsPendingProgressSyncEntity>()
        override suspend fun insertOrReplace(sync: AbsPendingProgressSyncEntity) {
            saved[sync.bookId] = sync
        }
        override suspend fun getByBookId(bookId: String): AbsPendingProgressSyncEntity? = saved[bookId]
        override suspend fun deleteByBookId(bookId: String) {
            saved.remove(bookId)
        }
        override suspend fun deleteByBookIds(bookIds: List<String>) {
            bookIds.forEach { id -> saved.remove(id) }
        }
    }

    private class FakeCatalogStore : AbsCatalogStore {
        override suspend fun getBookById(bookId: String): BookEntity? = null
        override suspend fun getMirrorsByRootId(rootId: String): List<AbsItemMirrorEntity> = emptyList()
        override suspend fun getSyncState(rootId: String): AbsSyncStateEntity? = null
        override suspend fun upsertCatalogMirror(
            book: BookEntity,
            files: List<BookFileEntity>,
            chapters: List<ChapterEntity>,
            mirror: AbsItemMirrorEntity,
            syncState: AbsSyncStateEntity
        ) {
            // Playback Session Catalog Fixture Scope (Provides the catalog interface without accepting progress writes)
            // Session tests validate upload/download arbitration through ProgressGateway, so catalog materialization is intentionally inert here.
        }
        override suspend fun replaceMirrors(mirrors: List<AbsItemMirrorEntity>) = Unit
        override suspend fun saveSyncState(syncState: AbsSyncStateEntity) = Unit
        override suspend fun updateBookStatus(bookId: String, status: String) = Unit
    }

    private class FakeBookQueryGateway(
        private var book: BookEntity
    ) : BookCatalogGateway,
        BookMetadataGateway,
        BookmarkGateway,
        ChapterGateway,
        BookDeletionGateway {
        override val audiobooks: Flow<List<BookWithProgress>> = flowOf(emptyList())
        override suspend fun getBookById(id: String): BookEntity? = book.takeIf { it.id == id }
        override fun observeBookById(id: String): Flow<BookEntity?> = flowOf(book.takeIf { it.id == id })
        override fun searchAudiobooks(query: String): Flow<List<BookWithProgress>> = flowOf(emptyList())
        override fun filterByYear(year: String): Flow<List<BookWithProgress>> = flowOf(emptyList())
        override fun filterByAuthor(author: String): Flow<List<BookWithProgress>> = flowOf(emptyList())
        override fun filterByAuthorLimited(author: String, excludeId: String, limit: Int): Flow<List<BookWithProgress>> = flowOf(emptyList())
        override fun filterByNarrator(narrator: String): Flow<List<BookWithProgress>> = flowOf(emptyList())
        override fun filterByNarratorLimited(narrator: String, excludeId: String, limit: Int): Flow<List<BookWithProgress>> = flowOf(emptyList())
        override fun getRecentlyAdded(limit: Int): Flow<List<BookWithProgress>> = flowOf(emptyList())
        override fun getRecentlyAddedExclusive(currentId: String, authors: List<String>, narrators: List<String>, limit: Int): Flow<List<BookWithProgress>> = flowOf(emptyList())
        override suspend fun deleteBook(bookId: String) = Unit
        override suspend fun updateBookReadStatus(bookId: String, readStatus: String) {
            if (book.id == bookId) book = book.copy(readStatus = readStatus)
        }
        // Mock Update Book Details (Mock interface matching including series parameter)
        override suspend fun updateBookDetails(id: String, title: String, author: String, narrator: String, description: String, year: String, series: String) = Unit
        override suspend fun getFilesForBookSync(bookId: String): List<BookFileEntity> = emptyList()
        override suspend fun getAllFilesForBookSync(bookId: String): List<BookFileEntity> = emptyList()
        override fun updateMetadata(bookId: String, title: String?, author: String?, narrator: String?, description: String?, duration: Long) = Unit
        override fun getChapters(bookId: String): Flow<List<ChapterWithBookFile>> = flowOf(emptyList())
        override suspend fun getChaptersForBookSync(bookId: String): List<ChapterWithBookFile> = emptyList()
        override fun saveChapters(bookId: String, chapters: List<ChapterEntity>) = Unit
        override fun getBookmarks(bookId: String): Flow<List<BookmarkEntity>> = flowOf(emptyList())
        override suspend fun addBookmark(bookId: String, position: Long, title: String) = Unit
        override suspend fun updateBookmark(bookmark: BookmarkEntity) = Unit
        override suspend fun deleteBookmark(bookmark: BookmarkEntity) = Unit
    }

    private class FakeProgressGateway(
        private val localProgress: BookProgressEntity?
    ) : ProgressGateway {
        var savedProgress: BookProgressEntity? = null
        override fun updateProgress(bookId: String, position: Long) = Unit
        override suspend fun saveProgress(progress: BookProgressEntity) {
            savedProgress = progress
        }
        override suspend fun getLastPlayedProgressSync(): BookProgressEntity? = localProgress
        override suspend fun getProgressForBookSync(bookId: String): BookProgressEntity? =
            localProgress?.takeIf { it.bookId == bookId }
    }

    private class FailingSyncPlaybackApi : AbsApiClient {
        override suspend fun status(baseUrl: String) = AbsStatusDto(serverVersion = "2.35.1", isInit = true)
        override suspend fun login(baseUrl: String, username: String, password: String) = throw UnsupportedOperationException()
        override suspend fun authorize(baseUrl: String, token: String) =
            AbsAuthorizeResponseDto(user = AbsAuthorizedUserDto(id = "user-1", username = "demo", token = token))
        override suspend fun getLibraries(baseUrl: String, token: String) = emptyList<AbsLibraryDto>()
        override suspend fun getLibraryItemsMinified(baseUrl: String, token: String, libraryId: String) = AbsLibraryItemsResponseDto()
        override suspend fun batchGetItems(baseUrl: String, token: String, itemIds: List<String>) = emptyList<AbsLibraryItemDto>()
        override suspend fun openPlaybackSession(baseUrl: String, token: String, itemId: String, request: AbsPlayRequestDto) =
            AbsPlaybackSessionDto(id = "session-1", libraryItemId = itemId, audioTracks = listOf(AbsTrackDto(index = 1, contentUrl = "/hls/session-1/output.m3u8")))
        override suspend fun syncSession(baseUrl: String, token: String, sessionId: String, currentTimeSec: Double, timeListenedSec: Double, durationSec: Double) {
            error("sync failed")
        }
        override suspend fun closeSession(baseUrl: String, token: String, sessionId: String, currentTimeSec: Double, timeListenedSec: Double, durationSec: Double) = Unit
    }
}
