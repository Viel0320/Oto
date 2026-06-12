package com.viel.aplayer.abs

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import com.viel.aplayer.abs.auth.AbsCredentialStore
import com.viel.aplayer.abs.mapping.AbsCatalogMapper
import com.viel.aplayer.abs.mapping.AbsProgressMapper
import com.viel.aplayer.abs.mapping.AbsRemoteIdMapper
import com.viel.aplayer.abs.net.AbsApiClient
import com.viel.aplayer.abs.net.dto.AbsAuthorizeResponseDto
import com.viel.aplayer.abs.net.dto.AbsAuthorizedUserDto
import com.viel.aplayer.abs.net.dto.AbsItemMediaDto
import com.viel.aplayer.abs.net.dto.AbsLibraryDto
import com.viel.aplayer.abs.net.dto.AbsLibraryItemDto
import com.viel.aplayer.abs.net.dto.AbsLibraryItemsResponseDto
import com.viel.aplayer.abs.net.dto.AbsMediaMetadataDto
import com.viel.aplayer.abs.net.dto.AbsPlayRequestDto
import com.viel.aplayer.abs.net.dto.AbsPlaybackSessionDto
import com.viel.aplayer.abs.net.dto.AbsStatusDto
import com.viel.aplayer.abs.sync.AbsCatalogStore
import com.viel.aplayer.abs.sync.AbsCatalogSynchronizer
import com.viel.aplayer.abs.sync.AbsCoverStore
import com.viel.aplayer.abs.sync.AbsItemMirrorEntity
import com.viel.aplayer.abs.sync.AbsSyncStateEntity
import com.viel.aplayer.abs.sync.buildAbsIncrementalErrorSummary
import com.viel.aplayer.abs.sync.selectAbsDetailCandidateIds
import com.viel.aplayer.data.cache.OnlineSourceCachePolicy
import com.viel.aplayer.data.db.AudiobookSchema
import com.viel.aplayer.data.entity.BookEntity
import com.viel.aplayer.data.entity.BookFileEntity
import com.viel.aplayer.data.entity.ChapterEntity
import com.viel.aplayer.data.entity.LibraryRootEntity
import com.viel.aplayer.media.parser.CoverExtractor
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

class AbsIncrementalStage6Test {

    private val idMapper = AbsRemoteIdMapper()
    private val progressMapper = AbsProgressMapper()
    private val catalogMapper = AbsCatalogMapper(idMapper)

    @Test
    fun `incremental selector should skip unchanged items and keep changed or new items in detail queue`() {
        val existingMirrors = mapOf(
            "item-1" to AbsItemMirrorEntity(
                localBookId = "book-1",
                rootId = "root-1",
                serverKey = "server-1",
                remoteItemId = "item-1",
                lastSeenAt = 10_000L,
                remoteUpdatedAt = 100L,
                state = AudiobookSchema.AbsMirrorState.ACTIVE
            ),
            "item-2" to AbsItemMirrorEntity(
                localBookId = "book-2",
                rootId = "root-1",
                serverKey = "server-1",
                remoteItemId = "item-2",
                lastSeenAt = 10_000L,
                remoteUpdatedAt = 200L,
                state = AudiobookSchema.AbsMirrorState.ACTIVE
            )
        )
        val unchangedItems = listOf(
            AbsLibraryItemDto(id = "item-1", mediaType = "book", updatedAt = 100L),
            AbsLibraryItemDto(id = "item-2", mediaType = "book", updatedAt = 200L)
        )
        val changedItems = listOf(
            AbsLibraryItemDto(id = "item-1", mediaType = "book", updatedAt = 100L),
            AbsLibraryItemDto(id = "item-2", mediaType = "book", updatedAt = 201L),
            AbsLibraryItemDto(id = "item-3", mediaType = "book", updatedAt = 50L)
        )

        // Fingerprint comparison validation. When the overall minified fingerprint remains unchanged, items with existing mirrors should not enter the detail fetch queue.
        assertTrue(
            selectAbsDetailCandidateIds(
                minifiedItems = unchangedItems,
                existingMirrors = existingMirrors,
                previousFullListFingerprint = "item-1:100|item-2:200",
                currentFullListFingerprint = "item-1:100|item-2:200",
                nowMillis = 10_000L
            ).isEmpty()
        )

        // Incremental queue populating. Once the fingerprint changes, the incremental synchronization logic must queue items with modified update timestamps or those lacking local mirrors.
        assertEquals(
            listOf("item-2", "item-3"),
            selectAbsDetailCandidateIds(
                minifiedItems = changedItems,
                existingMirrors = existingMirrors,
                previousFullListFingerprint = "item-1:100|item-2:200",
                currentFullListFingerprint = "item-1:100|item-2:201|item-3:50",
                nowMillis = 10_000L
            )
        )
    }

    @Test
    fun `incremental selector should refetch unchanged mirror after catalog ttl`() {
        val now = OnlineSourceCachePolicy.ABS_CATALOG_MIRROR_TTL_MS + 10_000L
        val item = AbsLibraryItemDto(id = "item-1", mediaType = "book", updatedAt = 100L)
        val freshMirrors = mapOf(
            "item-1" to AbsItemMirrorEntity(
                localBookId = "book-1",
                rootId = "root-1",
                serverKey = "server-1",
                remoteItemId = "item-1",
                lastSeenAt = now - OnlineSourceCachePolicy.ABS_CATALOG_MIRROR_TTL_MS,
                remoteUpdatedAt = 100L,
                state = AudiobookSchema.AbsMirrorState.ACTIVE
            )
        )
        val staleMirrors = mapOf(
            "item-1" to freshMirrors.getValue("item-1")
                .copy(lastSeenAt = now - OnlineSourceCachePolicy.ABS_CATALOG_MIRROR_TTL_MS - 1L)
        )

        // ABS Mirror TTL Fallback (Forces detail refresh after the catalog mirror freshness window)
        // Fingerprint and updatedAt checks still allow fresh mirrors to be reused, while old mirrors must be revalidated.
        assertTrue(
            selectAbsDetailCandidateIds(
                minifiedItems = listOf(item),
                existingMirrors = freshMirrors,
                previousFullListFingerprint = "item-1:100",
                currentFullListFingerprint = "item-1:100",
                nowMillis = now
            ).isEmpty()
        )
        assertEquals(
            listOf("item-1"),
            selectAbsDetailCandidateIds(
                minifiedItems = listOf(item),
                existingMirrors = staleMirrors,
                previousFullListFingerprint = "item-1:100",
                currentFullListFingerprint = "item-1:100",
                nowMillis = now
            )
        )
    }

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

        // Authorized Progress Startup TTL (Uses the persisted root sync stamp as the cold-start freshness marker)
        // Fresh roots should skip WorkManager enqueueing, while missing or expired root sync state must be treated as due.
        assertTrue(!AbsCatalogSynchronizer(UnsupportedApi(), createCredentialStore("https://example.com/audiobookshelf", "token-1"), freshStore).isAuthorizedProgressRefreshDue("fresh-root", now))
        assertTrue(AbsCatalogSynchronizer(UnsupportedApi(), createCredentialStore("https://example.com/audiobookshelf", "token-1"), staleStore).isAuthorizedProgressRefreshDue("stale-root", now))
        assertTrue(AbsCatalogSynchronizer(UnsupportedApi(), createCredentialStore("https://example.com/audiobookshelf", "token-1"), FakeCatalogStore()).isAuthorizedProgressRefreshDue("missing-root", now))
    }

    @Test
    fun `incremental sync should retry a failed item at most three times then keep only root level error summary`() = runBlocking {
        val credentialStore = createCredentialStore("https://example.com/audiobookshelf", "token-1")
        val store = FakeCatalogStore()
        val api = AlwaysFailingDetailApi()
        val synchronizer = AbsCatalogSynchronizer(
            apiClient = api,
            credentialStore = credentialStore,
            catalogStore = store
        )
        val root = sampleRoot()

        synchronizer.syncRoot(root)

        // Retry bounds validation. Following a batch request failure, the synchronizer allows at most three retries per item before discarding them for the current round.
        assertEquals(4, api.detailRequestCount)
        assertTrue(store.books.isEmpty())
        assertNotNull(store.syncState)
        assertTrue(store.syncState?.lastIncrementalSyncAt != null)
        assertTrue(store.syncState?.lastError?.startsWith("DETAIL_ITEM_FAILED:1") == true)
    }

    @Test
    fun `incremental sync should reactivate unchanged remote deleted mirror without detail refetch`() = runBlocking {
        val credentialStore = createCredentialStore("https://example.com/audiobookshelf", "token-1")
        val serverKey = idMapper.serverKey("https://example.com/audiobookshelf", "user-1")
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
                 * Generate Hash Fingerprint (Aligns test metadata with SHA-256 fingerprint logic)
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

        // Unchanged mirror reactivation. When the minified fingerprint is unchanged and a local mirror exists, we reactivate the mirror directly,
        // avoiding redundant detail requests simply to restore the active state status.
        assertEquals(0, api.detailRequestCount)
        assertEquals(AudiobookSchema.AbsMirrorState.ACTIVE, store.mirrors["item-1"]?.state)
        assertEquals(AudiobookSchema.BookStatus.READY, store.books[existingBookId]?.status)
    }

    @Test
    fun `sync summary should report added and failed item counts`() = runBlocking {
        val credentialStore = createCredentialStore("https://example.com/audiobookshelf", "token-1")
        val store = FakeCatalogStore()
        val api = PartialDetailApi()
        val synchronizer = AbsCatalogSynchronizer(
            apiClient = api,
            credentialStore = credentialStore,
            catalogStore = store
        )

        val summary = synchronizer.syncRootWithSummary(sampleRoot())

        // Sync statistics isolation. The sync summary must separately track successfully imported books and failed items,
        // ensuring the settings page's auto-sync toast accurately informs the user of successful and failed items.
        assertEquals(2, summary.totalItems)
        assertEquals(1, summary.addedBooks)
        assertEquals(1, summary.syncedBooks)
        assertEquals(1, summary.failedItems)
        assertEquals(0, summary.reusedBooks)
    }

    @Test
    fun `catalog sync should refresh expired cover even when metadata fingerprint is unchanged`() = runBlocking {
        val credentialStore = createCredentialStore("https://example.com/audiobookshelf", "token-1")
        val serverKey = idMapper.serverKey("https://example.com/audiobookshelf", "user-1")
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
                 * Generate Hash Fingerprint (Aligns test metadata with SHA-256 fingerprint logic)
                 * Ensures the mock sync state fullListFingerprint uses the same hash computation as the synchronizer class.
                 */
                fullListFingerprint = AbsCatalogSynchronizer.minifiedFingerprint(
                    listOf(AbsLibraryItemDto(id = "item-1", libraryId = root.basePath, mediaType = "book", updatedAt = 100L))
                )
            )
        )
        val coverStore = FakeCoverStore(
            result = CoverExtractor.CoverResult(
                originalPath = "/cache/refreshed-cover.jpg",
                thumbnailPath = "/cache/refreshed-thumb.jpg"
            )
        )
        val synchronizer = AbsCatalogSynchronizer(
            apiClient = CoverRefreshApi(),
            credentialStore = credentialStore,
            catalogStore = store,
            coverCache = coverStore
        )

        synchronizer.syncRoot(root)

        // ABS Cover TTL Fallback (Refreshes old artwork even when the catalog fingerprint did not expose a change)
        // The stale mirror is refetched to revalidate detail data, and the stale cover stamp triggers one cover download with a fresh UI cache version.
        assertEquals(listOf("item-1"), coverStore.downloadedItemIds)
        assertEquals("/cache/refreshed-cover.jpg", store.books[existingBookId]?.coverPath)
        assertEquals("/cache/refreshed-thumb.jpg", store.books[existingBookId]?.thumbnailPath)
        assertTrue((store.books[existingBookId]?.lastScannedAt ?: 0L) >= now)
    }

    @Test
    fun `dto compatibility should tolerate missing tracks size and progress timestamp`() {
        val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
        val adapter = moshi.adapter(AbsLibraryItemDto::class.java)
        val root = sampleRoot()
        val serverKey = idMapper.serverKey("https://example.com/audiobookshelf", "user-1")

        val missingTracksItem = adapter.fromJson(
            """
            {
              "id": "item-no-tracks",
              "libraryId": "lib-1",
              "mediaType": "book",
              "title": "No Tracks",
              "updatedAt": 1,
              "media": {
                "duration": 10.0
              }
            }
            """.trimIndent()
        )!!

        val bookWithoutTracks = catalogMapper.toBook(root, serverKey, missingTracksItem, existing = null, syncedAt = 1234L)
        val filesWithoutTracks = catalogMapper.toFiles(root, serverKey, missingTracksItem)

        // Empty tracks compatibility. A server response missing track fields (due to older versions or pruning) should gracefully fall back to an empty list without causing DTO or mapper crashes.
        assertEquals("No Tracks", bookWithoutTracks.title)
        assertTrue(filesWithoutTracks.isEmpty())

        val missingSizeAndProgressTimeItem = adapter.fromJson(
            """
            {
              "id": "item-partial",
              "libraryId": "lib-1",
              "mediaType": "book",
              "title": "Partial",
              "updatedAt": 2,
              "progress": {
                "currentTime": 2.5,
                "isFinished": false
              },
              "media": {
                "duration": 10.0,
                "tracks": [
                  {
                    "index": 1,
                    "duration": 10.0,
                    "contentUrl": "/api/items/item-partial/file/1",
                    "metadata": {
                      "filename": "partial.mp3"
                    }
                  }
                ]
              }
            }
            """.trimIndent()
        )!!

        val book = catalogMapper.toBook(root, serverKey, missingSizeAndProgressTimeItem, existing = null, syncedAt = 1234L)
        val files = catalogMapper.toFiles(root, serverKey, missingSizeAndProgressTimeItem)
        val progress = progressMapper.toProgressOrNull(missingSizeAndProgressTimeItem, book, files, 1234L)

        // Metadata safety bounds. A track missing a size attribute defaults to 0, and progress missing lastUpdate defaults to the sync time, preventing NullPointerExceptions.
        assertEquals(1, files.size)
        assertEquals(0L, files.first().fileSize)
        assertEquals(1234L, progress?.lastPlayedAt)
    }

    @Test
    fun `incremental error summary should stay compact and deterministic`() {
        val summary = buildAbsIncrementalErrorSummary(
            linkedMapOf(
                "item-2" to "HTTP_500",
                "item-3" to "TIMEOUT"
            )
        )

        // Error message truncation. The root-level error summary retains only the total failure count and the first failing item, preventing the settings UI from being overwhelmed.
        assertEquals("DETAIL_ITEM_FAILED:2:first=item-2:HTTP_500", summary)
        assertNull(buildAbsIncrementalErrorSummary(emptyMap()))
    }

    private fun sampleRoot() = LibraryRootEntity(
        id = "root-1",
        sourceType = AudiobookSchema.LibrarySourceType.ABS,
        sourceUri = "https://example.com/audiobookshelf",
        basePath = "lib-1",
        credentialId = "cred-1",
        displayName = "Audiobooks"
    )

    private fun createCredentialStore(baseUrl: String, token: String): AbsCredentialStore {
        val tempDir = createTempDirectory(prefix = "abs-incremental-stage6").toFile()
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
                    com.viel.aplayer.abs.net.dto.AbsTrackDto(
                        index = 1,
                        duration = 30.0,
                        contentUrl = "/api/items/$itemId/file/1",
                        metadata = com.viel.aplayer.abs.net.dto.AbsTrackMetadataDto(filename = "$itemId.mp3")
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
                            com.viel.aplayer.abs.net.dto.AbsTrackDto(
                                index = 1,
                                duration = 30.0,
                                contentUrl = "/api/items/$itemId/file/1",
                                metadata = com.viel.aplayer.abs.net.dto.AbsTrackMetadataDto(filename = "$itemId.mp3")
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
        private val result: CoverExtractor.CoverResult
    ) : AbsCoverStore {
        val downloadedItemIds = mutableListOf<String>()

        override suspend fun downloadCover(root: LibraryRootEntity, remoteItemId: String): CoverExtractor.CoverResult {
            // Cover TTL Fixture (Records cover refresh calls without performing network or image processing)
            // The synchronizer test only needs to verify whether the online cache policy decides to refresh artwork.
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
            // Incremental Catalog Fixture Scope (Represents only the rows owned by catalog synchronization)
            // Progress assertions in this file continue to target AbsProgressMapper directly instead of the catalog store fake.
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
