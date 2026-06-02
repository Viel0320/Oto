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
import com.viel.aplayer.abs.playback.AbsPendingProgressSyncDao
import com.viel.aplayer.abs.playback.AbsPendingProgressSyncEntity
import com.viel.aplayer.abs.playback.AbsPlaybackSessionDao
import com.viel.aplayer.abs.playback.AbsPlaybackSessionEntity
import com.viel.aplayer.abs.playback.AbsPlaybackSessionSyncer
import com.viel.aplayer.abs.sync.AbsCatalogStore
import com.viel.aplayer.abs.sync.AbsItemMirrorEntity
import com.viel.aplayer.abs.sync.AbsSyncStateEntity
import com.viel.aplayer.data.db.AudiobookSchema
import com.viel.aplayer.data.entity.BookEntity
import com.viel.aplayer.data.entity.BookProgressEntity
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
    fun `conflict resolver should prefer remote only when newer and not currently playing`() {
        val resolver = AbsProgressConflictResolver()
        val local = BookProgressEntity(bookId = "book-1", globalPositionMs = 1000L, lastPlayedAt = 2000L)
        val remoteOld = com.viel.aplayer.abs.net.dto.AbsUserProgressDto(currentTime = 5.0, lastUpdate = 1000L)
        val remoteNew = com.viel.aplayer.abs.net.dto.AbsUserProgressDto(currentTime = 5.0, lastUpdate = 3000L)

        assertFalse(resolver.shouldApplyRemoteProgress(local, remoteOld, isCurrentlyPlaying = false))
        assertTrue(resolver.shouldApplyRemoteProgress(local, remoteNew, isCurrentlyPlaying = false))
        assertFalse(resolver.shouldApplyRemoteProgress(local, remoteNew, isCurrentlyPlaying = true))
    }

    private fun absBook() = BookEntity(
        id = "abs:server:item:item-1",
        rootId = "root-1",
        sourceType = AudiobookSchema.SourceType.ABS_REMOTE,
        sourceRoot = "https://example.com/audiobookshelf",
        title = "ABS Book"
    )

    private class SuccessfulPlaybackApi : AbsApiClient {
        override suspend fun status(baseUrl: String) = AbsStatusDto(serverVersion = "2.35.1", isInit = true)
        override suspend fun login(baseUrl: String, username: String, password: String) = throw UnsupportedOperationException()
        override suspend fun authorize(baseUrl: String, token: String) =
            AbsAuthorizeResponseDto(user = AbsAuthorizedUserDto(id = "user-1", username = "demo", token = token))
        override suspend fun getLibraries(baseUrl: String, token: String) = emptyList<AbsLibraryDto>()
        override suspend fun getLibraryItemsMinified(baseUrl: String, token: String, libraryId: String) = AbsLibraryItemsResponseDto()
        override suspend fun batchGetItems(baseUrl: String, token: String, itemIds: List<String>) = emptyList<AbsLibraryItemDto>()
        override suspend fun openPlaybackSession(baseUrl: String, token: String, itemId: String, request: AbsPlayRequestDto) =
            AbsPlaybackSessionDto(id = "session-1", libraryItemId = itemId, audioTracks = listOf(AbsTrackDto(index = 1, contentUrl = "/hls/session-1/output.m3u8")))
        override suspend fun syncSession(baseUrl: String, token: String, sessionId: String, currentTimeSec: Double, timeListenedSec: Double, durationSec: Double) = Unit
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
            files: List<com.viel.aplayer.data.entity.BookFileEntity>,
            chapters: List<com.viel.aplayer.data.entity.ChapterEntity>,
            progress: BookProgressEntity?,
            mirror: AbsItemMirrorEntity,
            syncState: AbsSyncStateEntity
        ) = Unit
        override suspend fun replaceMirrors(mirrors: List<AbsItemMirrorEntity>) = Unit
        override suspend fun saveSyncState(syncState: AbsSyncStateEntity) = Unit
        override suspend fun updateBookStatus(bookId: String, status: String) = Unit
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
