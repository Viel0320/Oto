package com.viel.oto.abs.sync

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.viel.oto.abs.auth.AbsCredentialStore
import com.viel.oto.abs.mapping.AbsRemoteIdMapper
import com.viel.oto.abs.net.AbsApiClient
import com.viel.oto.abs.net.dto.AbsAuthorizeResponseDto
import com.viel.oto.abs.net.dto.AbsAuthorizedUserDto
import com.viel.oto.abs.net.dto.AbsItemMediaDto
import com.viel.oto.abs.net.dto.AbsLibraryDto
import com.viel.oto.abs.net.dto.AbsLibraryItemDto
import com.viel.oto.abs.net.dto.AbsLibraryItemsResponseDto
import com.viel.oto.abs.net.dto.AbsMediaMetadataDto
import com.viel.oto.abs.net.dto.AbsPlayRequestDto
import com.viel.oto.abs.net.dto.AbsPlaybackSessionDto
import com.viel.oto.abs.net.dto.AbsStatusDto
import com.viel.oto.data.abs.sync.AbsCatalogStore
import com.viel.oto.data.abs.sync.AbsItemMirrorEntity
import com.viel.oto.data.abs.sync.AbsSyncStateEntity
import com.viel.oto.data.cache.OnlineSourceCachePolicy
import com.viel.oto.data.cover.CoverImageResult
import com.viel.oto.data.db.AudiobookSchema
import com.viel.oto.data.entity.BookEntity
import com.viel.oto.data.entity.BookFileEntity
import com.viel.oto.data.entity.ChapterEntity
import com.viel.oto.data.entity.LibraryRootEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

class AbsCatalogIncrementalSyncTest {

    private val idMapper = AbsRemoteIdMapper()
    @Test
    fun `authorized progress refresh due should follow root sync freshness`() = runBlocking {
        val now = OnlineSourceCachePolicy.ABS_AUTHORIZED_PROGRESS_TTL_MS + 10_000L
        val freshStore = FakeCatalogStore(
            syncState = AbsSyncStateEntity(
                rootId = "fresh-root",
                serverKey = "server-1",
                libraryId = "lib-1",
                lastIncrementalSyncAt = now - OnlineSourceCachePolicy.ABS_AUTHORIZED_PROGRESS_TTL_MS
            )
        )
        val staleStore = FakeCatalogStore(
            syncState = AbsSyncStateEntity(
                rootId = "stale-root",
                serverKey = "server-1",
                libraryId = "lib-1",
                lastIncrementalSyncAt = now - OnlineSourceCachePolicy.ABS_AUTHORIZED_PROGRESS_TTL_MS - 1L
            )
        )

        assertTrue(!AbsCatalogSynchronizer(UnsupportedApi(), createCredentialStore("https://example.com/AudiobookShelf", "token-1"), freshStore).isAuthorizedProgressRefreshDue("fresh-root", now))
        assertTrue(AbsCatalogSynchronizer(UnsupportedApi(), createCredentialStore("https://example.com/AudiobookShelf", "token-1"), staleStore).isAuthorizedProgressRefreshDue("stale-root", now))
        assertTrue(AbsCatalogSynchronizer(UnsupportedApi(), createCredentialStore("https://example.com/AudiobookShelf", "token-1"), FakeCatalogStore()).isAuthorizedProgressRefreshDue("missing-root", now))
    }

    @Test
    fun `incremental sync should retry a failed item at most three times then keep only root level error summary`() = runBlocking {
        val credentialStore = createCredentialStore("https://example.com/AudiobookShelf", "token-1")
        val store = FakeCatalogStore()
        val api = AlwaysFailingDetailApi()
        val synchronizer = AbsCatalogSynchronizer(
            apiClient = api,
            credentialStore = credentialStore,
            catalogStore = store
        )
        val root = sampleRoot()

        synchronizer.syncRoot(root)

        assertEquals(4, api.detailRequestCount)
        assertTrue(store.books.isEmpty())
        assertNotNull(store.syncState)
        assertTrue(store.syncState?.lastIncrementalSyncAt != null)
        assertTrue(store.syncState?.lastError?.startsWith("DETAIL_ITEM_FAILED:1") == true)
    }

    @Test
    fun `incremental sync should reactivate unchanged remote deleted mirror without detail refetch`() = runBlocking {
        val credentialStore = createCredentialStore("https://example.com/AudiobookShelf", "token-1")
        val serverKey = idMapper.serverKey("https://example.com/AudiobookShelf", "user-1")
        val root = sampleRoot()
        val existingBookId = idMapper.bookId(serverKey, "item-1")
        val store = FakeCatalogStore(
            books = linkedMapOf(
                existingBookId to BookEntity(
                    id = existingBookId,
                    rootId = root.id,
                    sourceType = AudiobookSchema.SourceType.ABS_REMOTE,
                    sourceRoot = root.sourceUri,
                    title = "Persisted Book",
                    status = AudiobookSchema.BookStatus.DELETED
                )
            ),
            mirrors = linkedMapOf(
                "item-1" to AbsItemMirrorEntity(
                    localBookId = existingBookId,
                    rootId = root.id,
                    serverKey = serverKey,
                    remoteItemId = "item-1",
                    lastSeenAt = System.currentTimeMillis(),
                    remoteUpdatedAt = 100L,
                    state = AudiobookSchema.AbsMirrorState.REMOTE_DELETED
                )
            ),
            syncState = AbsSyncStateEntity(
                rootId = root.id,
                serverKey = serverKey,
                libraryId = root.basePath,
                /**
                 * Aligns test metadata with SHA-256 fingerprint logic.
                 * Ensures the mock sync state fullListFingerprint uses the same hash computation as the synchronizer class.
                 */
                fullListFingerprint = AbsCatalogSynchronizer.minifiedFingerprint(
                    listOf(AbsLibraryItemDto(id = "item-1", libraryId = root.basePath, mediaType = "book", updatedAt = 100L))
                )
            )
        )
        val api = UnchangedListApi()
        val synchronizer = AbsCatalogSynchronizer(
            apiClient = api,
            credentialStore = credentialStore,
            catalogStore = store
        )

        synchronizer.syncRoot(root)

        assertEquals(0, api.detailRequestCount)
        assertEquals(AudiobookSchema.AbsMirrorState.ACTIVE, store.mirrors["item-1"]?.state)
        assertEquals(AudiobookSchema.BookStatus.READY, store.books[existingBookId]?.status)
    }

    @Test
    fun `sync summary should report added and failed item counts`() = runBlocking {
        val credentialStore = createCredentialStore("https://example.com/AudiobookShelf", "token-1")
        val store = FakeCatalogStore()
        val api = PartialDetailApi()
        val synchronizer = AbsCatalogSynchronizer(
            apiClient = api,
            credentialStore = credentialStore,
            catalogStore = store
        )

        val summary = synchronizer.syncRootWithSummary(sampleRoot())

        assertEquals(2, summary.totalItems)
        assertEquals(1, summary.addedBooks)
        assertEquals(1, summary.syncedBooks)
        assertEquals(1, summary.failedItems)
        assertEquals(0, summary.reusedBooks)
    }

    @Test
    fun `catalog sync should refresh expired cover even when metadata fingerprint is unchanged`() = runBlocking {
        val credentialStore = createCredentialStore("https://example.com/AudiobookShelf", "token-1")
        val serverKey = idMapper.serverKey("https://example.com/AudiobookShelf", "user-1")
        val root = sampleRoot()
        val existingBookId = idMapper.bookId(serverKey, "item-1")
        val now = System.currentTimeMillis()
        val store = FakeCatalogStore(
            books = linkedMapOf(
                existingBookId to BookEntity(
                    id = existingBookId,
                    rootId = root.id,
                    sourceType = AudiobookSchema.SourceType.ABS_REMOTE,
                    sourceRoot = root.sourceUri,
                    title = "Persisted Book",
                    coverPath = "/cache/old-cover.jpg",
                    thumbnailPath = "/cache/old-thumb.jpg",
                    lastScannedAt = now - OnlineSourceCachePolicy.ABS_COVER_TTL_MS - 1L
                )
            ),
            mirrors = linkedMapOf(
                "item-1" to AbsItemMirrorEntity(
                    localBookId = existingBookId,
                    rootId = root.id,
                    serverKey = serverKey,
                    remoteItemId = "item-1",
                    lastSeenAt = now - OnlineSourceCachePolicy.ABS_CATALOG_MIRROR_TTL_MS - 1L,
                    remoteUpdatedAt = 100L,
                    state = AudiobookSchema.AbsMirrorState.ACTIVE
                )
            ),
            syncState = AbsSyncStateEntity(
                rootId = root.id,
                serverKey = serverKey,
                libraryId = root.basePath,
                /**
                 * Aligns test metadata with SHA-256 fingerprint logic.
                 * Ensures the mock sync state fullListFingerprint uses the same hash computation as the synchronizer class.
                 */
                fullListFingerprint = AbsCatalogSynchronizer.minifiedFingerprint(
                    listOf(AbsLibraryItemDto(id = "item-1", libraryId = root.basePath, mediaType = "book", updatedAt = 100L))
                )
            )
        )
        val coverStore = FakeCoverStore(
            result = CoverImageResult(
                originalPath = "/cache/refreshed-cover.jpg",
                thumbnailPath = "/cache/refreshed-thumb.jpg"
            )
        )
        val synchronizer = AbsCatalogSynchronizer(
            apiClient = CoverRefreshApi(),
            credentialStore = credentialStore,
            catalogStore = store
        )

        synchronizer.syncRoot(root)

        assertTrue(coverStore.downloadedItemIds.isEmpty())
        assertEquals("/cache/old-cover.jpg", store.books[existingBookId]?.coverPath)
        assertEquals("/cache/old-thumb.jpg", store.books[existingBookId]?.thumbnailPath)
        assertEquals(now - OnlineSourceCachePolicy.ABS_COVER_TTL_MS - 1L, store.books[existingBookId]?.lastScannedAt)
    }

    @Test
    fun `incremental sync should clear fingerprint when library is switched`() = runBlocking {
        val credentialStore = createCredentialStore("https://example.com/AudiobookShelf", "token-1")
        val serverKey = idMapper.serverKey("https://example.com/AudiobookShelf", "user-1")
        val root = sampleRoot().copy(basePath = "lib-new")
        val existingBookId = idMapper.bookId(serverKey, "item-1")
        val oldFingerprint = AbsCatalogSynchronizer.minifiedFingerprint(
            listOf(AbsLibraryItemDto(id = "item-1", libraryId = "lib-old", mediaType = "book", updatedAt = 100L))
        )
        val store = FakeCatalogStore(
            books = linkedMapOf(
                existingBookId to BookEntity(
                    id = existingBookId,
                    rootId = root.id,
                    sourceType = AudiobookSchema.SourceType.ABS_REMOTE,
                    sourceRoot = root.sourceUri,
                    title = "Persisted Book",
                    status = AudiobookSchema.BookStatus.DELETED
                )
            ),
            mirrors = linkedMapOf(
                "item-1" to AbsItemMirrorEntity(
                    localBookId = existingBookId,
                    rootId = root.id,
                    serverKey = serverKey,
                    remoteItemId = "item-1",
                    lastSeenAt = System.currentTimeMillis(),
                    remoteUpdatedAt = 100L,
                    state = AudiobookSchema.AbsMirrorState.ACTIVE
                )
            ),
            syncState = AbsSyncStateEntity(
                rootId = root.id,
                serverKey = serverKey,
                libraryId = "lib-old",
                fullListFingerprint = oldFingerprint
            )
        )
        val api = UnchangedListApi()
        val synchronizer = AbsCatalogSynchronizer(
            apiClient = api,
            credentialStore = credentialStore,
            catalogStore = store
        )

        /**
         * Asserts synchronizer forces fingerprint update on base path switches.
         * Checks that target library transitions lead to a freshly calculated hash.
         */
        synchronizer.syncRoot(root)

        assertEquals("lib-new", store.syncState?.libraryId)
        val expectedNewFingerprint = AbsCatalogSynchronizer.minifiedFingerprint(
            listOf(AbsLibraryItemDto(id = "item-1", libraryId = "lib-new", mediaType = "book", updatedAt = 100L))
        )
        assertEquals(expectedNewFingerprint, store.syncState?.fullListFingerprint)
    }

    private fun sampleRoot() = LibraryRootEntity(
        id = "root-1",
        sourceType = AudiobookSchema.LibrarySourceType.ABS,
        sourceUri = "https://example.com/AudiobookShelf",
        basePath = "lib-1",
        credentialId = "cred-1",
        displayName = "Audiobooks"
    )

    private fun createCredentialStore(baseUrl: String, token: String): AbsCredentialStore {
        val tempDir = createTempDirectory(prefix = "abs-catalog-incremental").toFile()
        val store = AbsCredentialStore.createForTesting(
            PreferenceDataStoreFactory.create(
                produceFile = { File(tempDir, "credentials.preferences_pb") }
            )
        )
        runBlocking {
            store.save(
                baseUrl = baseUrl,
                token = token,
                userId = "user-1",
                username = "demo-user",
                credentialId = "cred-1"
            )
        }
        return store
    }

    private class AlwaysFailingDetailApi : AbsApiClient {
        var detailRequestCount: Int = 0
        override suspend fun status(baseUrl: String) = AbsStatusDto(serverVersion = "2.35.1", isInit = true)
        override suspend fun login(baseUrl: String, username: String, password: String) = throw UnsupportedOperationException()
        override suspend fun authorize(baseUrl: String, token: String) =
            AbsAuthorizeResponseDto(user = AbsAuthorizedUserDto(id = "user-1", username = "demo-user", token = token))
        override suspend fun getLibraries(baseUrl: String, token: String) = emptyList<AbsLibraryDto>()
        override suspend fun getLibraryItemsMinified(baseUrl: String, token: String, libraryId: String) =
            AbsLibraryItemsResponseDto(
                results = listOf(AbsLibraryItemDto(id = "item-1", libraryId = libraryId, mediaType = "book", updatedAt = 100L)),
                total = 1,
                limit = 0,
                page = 0
            )
        override suspend fun batchGetItems(baseUrl: String, token: String, itemIds: List<String>): List<AbsLibraryItemDto> {
            detailRequestCount += 1
            error("detail fetch failed")
        }
        override suspend fun openPlaybackSession(baseUrl: String, token: String, itemId: String, request: AbsPlayRequestDto) =
            AbsPlaybackSessionDto(id = "session-1", libraryItemId = itemId)
        override suspend fun syncSession(baseUrl: String, token: String, sessionId: String, currentTimeSec: Double, timeListenedSec: Double, durationSec: Double) = Unit
        override suspend fun closeSession(baseUrl: String, token: String, sessionId: String, currentTimeSec: Double, timeListenedSec: Double, durationSec: Double) = Unit
    }

    private class UnchangedListApi : AbsApiClient {
        var detailRequestCount: Int = 0
        override suspend fun status(baseUrl: String) = AbsStatusDto(serverVersion = "2.35.1", isInit = true)
        override suspend fun login(baseUrl: String, username: String, password: String) = throw UnsupportedOperationException()
        override suspend fun authorize(baseUrl: String, token: String) =
            AbsAuthorizeResponseDto(user = AbsAuthorizedUserDto(id = "user-1", username = "demo-user", token = token))
        override suspend fun getLibraries(baseUrl: String, token: String) = emptyList<AbsLibraryDto>()
        override suspend fun getLibraryItemsMinified(baseUrl: String, token: String, libraryId: String) =
            AbsLibraryItemsResponseDto(
                results = listOf(AbsLibraryItemDto(id = "item-1", libraryId = libraryId, mediaType = "book", updatedAt = 100L)),
                total = 1,
                limit = 0,
                page = 0
            )
        override suspend fun batchGetItems(baseUrl: String, token: String, itemIds: List<String>): List<AbsLibraryItemDto> {
            detailRequestCount += 1
            return emptyList()
        }
        override suspend fun openPlaybackSession(baseUrl: String, token: String, itemId: String, request: AbsPlayRequestDto) =
            AbsPlaybackSessionDto(id = "session-1", libraryItemId = itemId)
        override suspend fun syncSession(baseUrl: String, token: String, sessionId: String, currentTimeSec: Double, timeListenedSec: Double, durationSec: Double) = Unit
        override suspend fun closeSession(baseUrl: String, token: String, sessionId: String, currentTimeSec: Double, timeListenedSec: Double, durationSec: Double) = Unit
    }

    private class PartialDetailApi : AbsApiClient {
        var requestCount: Int = 0
        override suspend fun status(baseUrl: String) = AbsStatusDto(serverVersion = "2.35.1", isInit = true)
        override suspend fun login(baseUrl: String, username: String, password: String) = throw UnsupportedOperationException()
        override suspend fun authorize(baseUrl: String, token: String) =
            AbsAuthorizeResponseDto(user = AbsAuthorizedUserDto(id = "user-1", username = "demo-user", token = token))
        override suspend fun getLibraries(baseUrl: String, token: String) = emptyList<AbsLibraryDto>()
        override suspend fun getLibraryItemsMinified(baseUrl: String, token: String, libraryId: String) =
            AbsLibraryItemsResponseDto(
                results = listOf(
                    AbsLibraryItemDto(id = "item-1", libraryId = libraryId, mediaType = "book", updatedAt = 100L),
                    AbsLibraryItemDto(id = "item-2", libraryId = libraryId, mediaType = "book", updatedAt = 101L)
                ),
                total = 2,
                limit = 0,
                page = 0
            )
        override suspend fun batchGetItems(baseUrl: String, token: String, itemIds: List<String>): List<AbsLibraryItemDto> {
            requestCount += 1
            return if (itemIds.size > 1) {
                listOf(successDetail("item-1"))
            } else if (itemIds.single() == "item-1") {
                listOf(successDetail("item-1"))
            } else {
                error("detail fetch failed for item-2")
            }
        }
        override suspend fun openPlaybackSession(baseUrl: String, token: String, itemId: String, request: AbsPlayRequestDto) =
            AbsPlaybackSessionDto(id = "session-1", libraryItemId = itemId)
        override suspend fun syncSession(baseUrl: String, token: String, sessionId: String, currentTimeSec: Double, timeListenedSec: Double, durationSec: Double) = Unit
        override suspend fun closeSession(baseUrl: String, token: String, sessionId: String, currentTimeSec: Double, timeListenedSec: Double, durationSec: Double) = Unit

        private fun successDetail(itemId: String) = AbsLibraryItemDto(
            id = itemId,
            libraryId = "lib-1",
            mediaType = "book",
            title = "Detail $itemId",
            updatedAt = 100L,
            media = AbsItemMediaDto(
                metadata = AbsMediaMetadataDto(title = "Detail $itemId"),
                tracks = listOf(
                    com.viel.oto.abs.net.dto.AbsTrackDto(
                        index = 1,
                        duration = 30.0,
                        contentUrl = "/api/items/$itemId/file/1",
                        metadata = com.viel.oto.abs.net.dto.AbsTrackMetadataDto(filename = "$itemId.mp3")
                    )
                ),
                duration = 30.0
            )
        )
    }

    private class CoverRefreshApi : AbsApiClient {
        override suspend fun status(baseUrl: String) = AbsStatusDto(serverVersion = "2.35.1", isInit = true)
        override suspend fun login(baseUrl: String, username: String, password: String) = throw UnsupportedOperationException()
        override suspend fun authorize(baseUrl: String, token: String) =
            AbsAuthorizeResponseDto(user = AbsAuthorizedUserDto(id = "user-1", username = "demo-user", token = token))
        override suspend fun getLibraries(baseUrl: String, token: String) = emptyList<AbsLibraryDto>()
        override suspend fun getLibraryItemsMinified(baseUrl: String, token: String, libraryId: String) =
            AbsLibraryItemsResponseDto(
                results = listOf(AbsLibraryItemDto(id = "item-1", libraryId = libraryId, mediaType = "book", updatedAt = 100L)),
                total = 1,
                limit = 0,
                page = 0
            )
        override suspend fun batchGetItems(baseUrl: String, token: String, itemIds: List<String>): List<AbsLibraryItemDto> =
            itemIds.map { itemId ->
                AbsLibraryItemDto(
                    id = itemId,
                    libraryId = "lib-1",
                    mediaType = "book",
                    title = "Detail $itemId",
                    updatedAt = 100L,
                    media = AbsItemMediaDto(
                        metadata = AbsMediaMetadataDto(title = "Detail $itemId"),
                        tracks = listOf(
                            com.viel.oto.abs.net.dto.AbsTrackDto(
                                index = 1,
                                duration = 30.0,
                                contentUrl = "/api/items/$itemId/file/1",
                                metadata = com.viel.oto.abs.net.dto.AbsTrackMetadataDto(filename = "$itemId.mp3")
                            )
                        ),
                        duration = 30.0
                    )
                )
            }
        override suspend fun openPlaybackSession(baseUrl: String, token: String, itemId: String, request: AbsPlayRequestDto) =
            AbsPlaybackSessionDto(id = "session-1", libraryItemId = itemId)
        override suspend fun syncSession(baseUrl: String, token: String, sessionId: String, currentTimeSec: Double, timeListenedSec: Double, durationSec: Double) = Unit
        override suspend fun closeSession(baseUrl: String, token: String, sessionId: String, currentTimeSec: Double, timeListenedSec: Double, durationSec: Double) = Unit
    }

    private class UnsupportedApi : AbsApiClient {
        override suspend fun status(baseUrl: String): AbsStatusDto = throw UnsupportedOperationException()
        override suspend fun login(baseUrl: String, username: String, password: String) = throw UnsupportedOperationException()
        override suspend fun authorize(baseUrl: String, token: String): AbsAuthorizeResponseDto = throw UnsupportedOperationException()
        override suspend fun getLibraries(baseUrl: String, token: String): List<AbsLibraryDto> = throw UnsupportedOperationException()
        override suspend fun getLibraryItemsMinified(baseUrl: String, token: String, libraryId: String): AbsLibraryItemsResponseDto = throw UnsupportedOperationException()
        override suspend fun batchGetItems(baseUrl: String, token: String, itemIds: List<String>): List<AbsLibraryItemDto> = throw UnsupportedOperationException()
        override suspend fun openPlaybackSession(baseUrl: String, token: String, itemId: String, request: AbsPlayRequestDto): AbsPlaybackSessionDto = throw UnsupportedOperationException()
        override suspend fun syncSession(baseUrl: String, token: String, sessionId: String, currentTimeSec: Double, timeListenedSec: Double, durationSec: Double) = throw UnsupportedOperationException()
        override suspend fun closeSession(baseUrl: String, token: String, sessionId: String, currentTimeSec: Double, timeListenedSec: Double, durationSec: Double) = throw UnsupportedOperationException()
    }

    private class FakeCoverStore(
        private val result: CoverImageResult
    ) : AbsCoverStore {
        val downloadedItemIds = mutableListOf<String>()

        override suspend fun downloadCover(root: LibraryRootEntity, remoteItemId: String): CoverImageResult {
            downloadedItemIds += remoteItemId
            return result
        }
    }

    private class FakeCatalogStore(
        val books: LinkedHashMap<String, BookEntity> = linkedMapOf(),
        val mirrors: LinkedHashMap<String, AbsItemMirrorEntity> = linkedMapOf(),
        var syncState: AbsSyncStateEntity? = null
    ) : AbsCatalogStore {
        override suspend fun getBookById(bookId: String): BookEntity? = books[bookId]
        override suspend fun getMirrorsByRootId(rootId: String): List<AbsItemMirrorEntity> =
            mirrors.values.filter { mirror -> mirror.rootId == rootId }
        override suspend fun getSyncState(rootId: String): AbsSyncStateEntity? =
            syncState?.takeIf { state -> state.rootId == rootId }
        override suspend fun upsertCatalogMirror(
            book: BookEntity,
            files: List<BookFileEntity>,
            chapters: List<ChapterEntity>,
            mirror: AbsItemMirrorEntity,
            syncState: AbsSyncStateEntity
        ) {
            books[book.id] = book
            mirrors[mirror.remoteItemId] = mirror
            this.syncState = syncState
        }
        override suspend fun replaceMirrors(mirrors: List<AbsItemMirrorEntity>) {
            mirrors.forEach { mirror -> this.mirrors[mirror.remoteItemId] = mirror }
        }
        override suspend fun saveSyncState(syncState: AbsSyncStateEntity) {
            this.syncState = syncState
        }
        override suspend fun updateBookStatus(bookId: String, status: AudiobookSchema.BookStatus) {
            books[bookId]?.let { book ->
                books[bookId] = book.copy(status = status)
            }
        }
    }
}
