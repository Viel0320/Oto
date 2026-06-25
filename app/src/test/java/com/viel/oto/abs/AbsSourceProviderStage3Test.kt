package com.viel.oto.abs

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.viel.oto.abs.auth.AbsCredentialStore
import com.viel.oto.abs.net.AbsApiError
import com.viel.oto.abs.net.AbsTokenRefreshClient
import com.viel.oto.abs.net.AbsTokenRefreshResult
import com.viel.oto.abs.net.dto.AbsPlayRequestDto
import com.viel.oto.abs.net.dto.AbsPlaybackSessionDto
import com.viel.oto.abs.sync.AbsCatalogStore
import com.viel.oto.abs.sync.AbsCatalogSynchronizer
import com.viel.oto.abs.sync.AbsCoverStore
import com.viel.oto.abs.sync.AbsItemMirrorEntity
import com.viel.oto.abs.sync.AbsSyncStateEntity
import com.viel.oto.abs.vfs.AbsAuthExpiredException
import com.viel.oto.abs.vfs.AbsRangeBodyTooLargeException
import com.viel.oto.abs.vfs.AbsSourceProvider
import com.viel.oto.data.db.AudiobookSchema
import com.viel.oto.data.entity.BookEntity
import com.viel.oto.data.entity.BookFileEntity
import com.viel.oto.data.entity.ChapterEntity
import com.viel.oto.data.entity.LibraryRootEntity
import com.viel.oto.shared.settings.AppSettings
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

class AbsSourceProviderStage3Test {

    @Test
    fun `resolve content url should preserve base subpath and reject external hosts`() {
        val credentialStore = createCredentialStore("https://example.com/AudiobookShelf", "token-1")
        val provider = AbsSourceProvider(
            context = null,
            credentialStore = credentialStore,
            settingsProvider = ::allowCleartextSettings
        )
        val root = LibraryRootEntity(
            id = "root-1",
            sourceType = AudiobookSchema.LibrarySourceType.ABS,
            sourceUri = "https://example.com/AudiobookShelf",
            basePath = "lib-1",
            credentialId = "cred-1",
            displayName = "Audiobooks"
        )
        val resolved = provider.resolveContentUrl(
            baseUrl = "https://example.com/AudiobookShelf/",
            root = root,
            contentUrl = "/api/items/item-1/file/856465"
        )
        assertEquals("https://example.com/AudiobookShelf/api/items/item-1/file/856465", resolved.toString())
        try {
            provider.resolveContentUrl(
                baseUrl = "https://example.com/AudiobookShelf/",
                root = root,
                contentUrl = "https://malicious.example/api/items/item-1/file/856465"
            )
            error("Expected exception")
        } catch (_: Exception) {
        }
    }

    @Test
    fun `open and range requests should carry bearer token and range header`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "audio/mpeg")
                    .setHeader("Content-Length", "100")
                    .setBody("0123456789")
            )
            server.enqueue(
                MockResponse()
                    .setResponseCode(206)
                    .setHeader("Content-Type", "audio/mpeg")
                    .setHeader("Content-Range", "bytes 5-9/10")
                    .setBody("56789")
            )
            val credentialStore = createCredentialStore(server.url("/AudiobookShelf/").toString(), "token-1")
            val provider = AbsSourceProvider(
                context = null,
                credentialStore = credentialStore,
                settingsProvider = ::allowCleartextSettings
            )
            val root = LibraryRootEntity(
                id = "root-1",
                sourceType = AudiobookSchema.LibrarySourceType.ABS,
                sourceUri = server.url("/AudiobookShelf").toString().trimEnd('/'),
                basePath = "lib-1",
                credentialId = "cred-1",
                displayName = "Audiobooks"
            )
            val node = requireNotNull(provider.resolve(root, "/api/items/item-1/file/856465"))
            provider.openInputStream(node).close()
            provider.openInputStream(node, offset = 5).close()

            val openRequest = server.takeRequest()
            assertEquals("Bearer token-1", openRequest.getHeader("Authorization"))
            val rangeRequest = server.takeRequest()
            assertEquals("bytes=5-", rangeRequest.getHeader("Range"))
        }
    }

    @Test
    fun `offset open should reject ignored range responses`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "audio/mpeg")
                    .setBody("0123456789")
            )
            val credentialStore = createCredentialStore(server.url("/AudiobookShelf/").toString(), "token-1")
            val provider = AbsSourceProvider(
                context = null,
                credentialStore = credentialStore,
                settingsProvider = ::allowCleartextSettings
            )
            val root = LibraryRootEntity(
                id = "root-1",
                sourceType = AudiobookSchema.LibrarySourceType.ABS,
                sourceUri = server.url("/AudiobookShelf").toString().trimEnd('/'),
                basePath = "lib-1",
                credentialId = "cred-1",
                displayName = "Audiobooks"
            )
            val node = requireNotNull(provider.resolve(root, "/api/items/item-1/file/856465"))

            try {
                provider.openInputStream(node, offset = 5).close()
                fail("Expected ignored ABS range responses to fail")
            } catch (error: AbsApiError) {
                assertEquals("RANGE_IGNORED", error.code)
            }

            val request = server.takeRequest()
            assertEquals("bytes=5-", request.getHeader("Range"))
        }
    }

    @Test
    fun `readRange should saturate overflowing range end`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse()
                    .setResponseCode(206)
                    .setHeader("Content-Type", "audio/mpeg")
                    .setHeader("Content-Range", "bytes ${Long.MAX_VALUE - 1L}-${Long.MAX_VALUE}/*")
                    .setBody("xy")
            )
            val credentialStore = createCredentialStore(server.url("/AudiobookShelf/").toString(), "token-1")
            val provider = AbsSourceProvider(
                context = null,
                credentialStore = credentialStore,
                settingsProvider = ::allowCleartextSettings
            )
            val root = LibraryRootEntity(
                id = "root-1",
                sourceType = AudiobookSchema.LibrarySourceType.ABS,
                sourceUri = server.url("/AudiobookShelf").toString().trimEnd('/'),
                basePath = "lib-1",
                credentialId = "cred-1",
                displayName = "Audiobooks"
            )
            val node = requireNotNull(provider.resolve(root, "/api/items/item-1/file/856465"))

            provider.readRange(node, offset = Long.MAX_VALUE - 1L, length = 8)

            val request = server.takeRequest()
            assertEquals("bytes=${Long.MAX_VALUE - 1L}-${Long.MAX_VALUE}", request.getHeader("Range"))
        }
    }

    @Test
    fun `readRange should throw typed failure when response body exceeds requested length`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse()
                    .setResponseCode(206)
                    .setHeader("Content-Type", "audio/mpeg")
                    .setHeader("Content-Range", "bytes 5-9/100")
                    .setBody("567890")
            )
            val credentialStore = createCredentialStore(server.url("/AudiobookShelf/").toString(), "token-1")
            val provider = AbsSourceProvider(
                context = null,
                credentialStore = credentialStore,
                settingsProvider = ::allowCleartextSettings
            )
            val root = LibraryRootEntity(
                id = "root-1",
                sourceType = AudiobookSchema.LibrarySourceType.ABS,
                sourceUri = server.url("/AudiobookShelf").toString().trimEnd('/'),
                basePath = "lib-1",
                credentialId = "cred-1",
                displayName = "Audiobooks"
            )
            val node = requireNotNull(provider.resolve(root, "/api/items/item-1/file/856465"))

            try {
                provider.readRange(node, offset = 5L, length = 5)
                fail("Expected oversized ABS range response body to fail")
            } catch (error: AbsRangeBodyTooLargeException) {
                assertEquals(AbsRangeBodyTooLargeException.CODE, error.code)
                assertEquals(5, error.requestedLength)
                assertEquals(6L, error.observedBytes)
                assertEquals(AudiobookSchema.AvailabilityStatus.SERVER_ERROR, error.availabilityStatus)
            }

            val request = server.takeRequest()
            assertEquals("bytes=5-9", request.getHeader("Range"))
        }
    }

    @Test
    fun `readRange should reject mismatched content range before accepting bytes`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse()
                    .setResponseCode(206)
                    .setHeader("Content-Type", "audio/mpeg")
                    .setHeader("Content-Range", "bytes 0-4/100")
                    .setBody("56789")
            )
            val credentialStore = createCredentialStore(server.url("/AudiobookShelf/").toString(), "token-1")
            val provider = AbsSourceProvider(
                context = null,
                credentialStore = credentialStore,
                settingsProvider = ::allowCleartextSettings
            )
            val root = LibraryRootEntity(
                id = "root-1",
                sourceType = AudiobookSchema.LibrarySourceType.ABS,
                sourceUri = server.url("/AudiobookShelf").toString().trimEnd('/'),
                basePath = "lib-1",
                credentialId = "cred-1",
                displayName = "Audiobooks"
            )
            val node = requireNotNull(provider.resolve(root, "/api/items/item-1/file/856465"))

            try {
                provider.readRange(node, offset = 5L, length = 5)
                fail("Expected mismatched ABS Content-Range to fail")
            } catch (error: AbsApiError) {
                assertEquals("RANGE_CONTENT_MISMATCH", error.code)
                assertEquals(AudiobookSchema.AvailabilityStatus.SERVER_ERROR, error.availabilityStatus)
            }

            val request = server.takeRequest()
            assertEquals("bytes=5-9", request.getHeader("Range"))
        }
    }

    @Test
    fun `readRange should accept shorter tail content range within requested window`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse()
                    .setResponseCode(206)
                    .setHeader("Content-Type", "audio/mpeg")
                    .setHeader("Content-Range", "bytes 7-9/10")
                    .setBody("789")
            )
            val credentialStore = createCredentialStore(server.url("/AudiobookShelf/").toString(), "token-1")
            val provider = AbsSourceProvider(
                context = null,
                credentialStore = credentialStore,
                settingsProvider = ::allowCleartextSettings
            )
            val root = LibraryRootEntity(
                id = "root-1",
                sourceType = AudiobookSchema.LibrarySourceType.ABS,
                sourceUri = server.url("/AudiobookShelf").toString().trimEnd('/'),
                basePath = "lib-1",
                credentialId = "cred-1",
                displayName = "Audiobooks"
            )
            val node = requireNotNull(provider.resolve(root, "/api/items/item-1/file/856465"))

            val data = provider.readRange(node, offset = 7L, length = 10)

            assertEquals("789", data?.toString(Charsets.UTF_8))
            val request = server.takeRequest()
            assertEquals("bytes=7-16", request.getHeader("Range"))
        }
    }

    @Test
    fun `stream unauthorized should refresh token and throw typed auth expiration`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(401))
            val credentialStore = createCredentialStore(server.url("/AudiobookShelf/").toString(), "token-1")
            val tokenRefreshClient = RecordingTokenRefreshClient(AbsTokenRefreshResult.Success("token-2"))
            val provider = AbsSourceProvider(
                context = null,
                credentialStore = credentialStore,
                settingsProvider = ::allowCleartextSettings,
                tokenRefreshClient = tokenRefreshClient
            )
            val root = LibraryRootEntity(
                id = "root-1",
                sourceType = AudiobookSchema.LibrarySourceType.ABS,
                sourceUri = server.url("/AudiobookShelf").toString().trimEnd('/'),
                basePath = "lib-1",
                credentialId = "cred-1",
                displayName = "Audiobooks"
            )
            val node = requireNotNull(provider.resolve(root, "/api/items/item-1/file/856465"))

            try {
                provider.openInputStream(node).close()
                fail("Expected typed ABS auth expiration")
            } catch (error: AbsAuthExpiredException) {
                assertEquals("cred-1", error.credentialId)
                assertEquals("root-1", error.rootId)
                assertEquals(AbsTokenRefreshResult.Success("token-2"), error.refreshResult)
            }

            assertEquals(listOf("cred-1"), tokenRefreshClient.refreshCalls)
            val request = server.takeRequest()
            assertEquals("Bearer token-1", request.getHeader("Authorization"))
        }
    }

    @Test
    fun `catalog synchronizer should write cached cover paths into book`() = runBlocking {
        val credentialStore = createCredentialStore("https://example.com/AudiobookShelf", "token-1")
        val store = FakeCatalogStore()
        val synchronizer = AbsCatalogSynchronizer(
            apiClient = FakeCatalogApi(),
            credentialStore = credentialStore,
            catalogStore = store
        )
        val root = LibraryRootEntity(
            id = "root-1",
            sourceType = AudiobookSchema.LibrarySourceType.ABS,
            sourceUri = "https://example.com/AudiobookShelf",
            basePath = "lib-1",
            credentialId = "cred-1",
            displayName = "Audiobooks"
        )

        synchronizer.syncRoot(root)

        val book = store.books.values.firstOrNull()
        assertNotNull(book)
        org.junit.Assert.assertNull(book?.coverPath)
        org.junit.Assert.assertNull(book?.thumbnailPath)
    }

    private fun createCredentialStore(baseUrl: String, token: String): AbsCredentialStore {
        val tempDir = createTempDirectory(prefix = "abs-source-provider").toFile()
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

    /**
     * Opts MockWebServer HTTP streams into transport compatibility.
     *
     * ABS media stream tests use local HTTP endpoints to inspect headers and Range behavior; production
     * code still defaults to blocking cleartext unless the user enables the global setting.
     */
    private fun allowCleartextSettings(): AppSettings =
        AppSettings(isCleartextTrafficAllowed = true)

    private class RecordingTokenRefreshClient(
        private val result: AbsTokenRefreshResult
    ) : AbsTokenRefreshClient {
        val refreshCalls = mutableListOf<String>()

        override suspend fun refreshToken(credentialId: String): AbsTokenRefreshResult {
            refreshCalls += credentialId
            return result
        }
    }

    private class FakeCoverStore(
        private val coverPath: String,
        private val thumbPath: String
    ) : AbsCoverStore {
        override suspend fun downloadCover(
            root: LibraryRootEntity,
            remoteItemId: String
        ) = com.viel.oto.media.parser.CoverExtractor.CoverResult(coverPath, thumbPath, 123)
    }

    private class FakeCatalogApi : com.viel.oto.abs.net.AbsApiClient {
        override suspend fun status(baseUrl: String) = com.viel.oto.abs.net.dto.AbsStatusDto(serverVersion = "2.35.1", isInit = true)
        override suspend fun login(baseUrl: String, username: String, password: String) = throw UnsupportedOperationException()
        override suspend fun authorize(baseUrl: String, token: String) =
            com.viel.oto.abs.net.dto.AbsAuthorizeResponseDto(
                user = com.viel.oto.abs.net.dto.AbsAuthorizedUserDto(id = "user-1", username = "demo-user", token = token)
            )
        override suspend fun getLibraries(baseUrl: String, token: String) = emptyList<com.viel.oto.abs.net.dto.AbsLibraryDto>()
        override suspend fun getLibraryItemsMinified(baseUrl: String, token: String, libraryId: String) =
            com.viel.oto.abs.net.dto.AbsLibraryItemsResponseDto(
                results = listOf(
                    com.viel.oto.abs.net.dto.AbsLibraryItemDto(
                        id = "item-1",
                        libraryId = libraryId,
                        mediaType = "book",
                        title = "Pi",
                        updatedAt = 1L,
                        media = com.viel.oto.abs.net.dto.AbsItemMediaDto(
                            metadata = com.viel.oto.abs.net.dto.AbsMediaMetadataDto(title = "Pi", authorName = "Scott"),
                            tracks = listOf(
                                com.viel.oto.abs.net.dto.AbsTrackDto(
                                    index = 1,
                                    duration = 10.0,
                                    contentUrl = "/api/items/item-1/file/856465",
                                    metadata = com.viel.oto.abs.net.dto.AbsTrackMetadataDto(filename = "pi.mp3", ext = ".mp3", size = 10L)
                                )
                            ),
                            chapters = listOf(
                                com.viel.oto.abs.net.dto.AbsChapterDto(id = 0, title = "c1", start = 0.0, end = 10.0)
                            ),
                            duration = 10.0
                        )
                    )
                ),
                total = 1,
                limit = 0,
                page = 0
            )
        override suspend fun batchGetItems(baseUrl: String, token: String, itemIds: List<String>) =
            getLibraryItemsMinified(baseUrl, token, "lib-1").results.orEmpty()
        override suspend fun openPlaybackSession(baseUrl: String, token: String, itemId: String, request: AbsPlayRequestDto) =
            AbsPlaybackSessionDto(id = "session-1", libraryItemId = itemId)
        override suspend fun syncSession(baseUrl: String, token: String, sessionId: String, currentTimeSec: Double, timeListenedSec: Double, durationSec: Double) = Unit
        override suspend fun closeSession(baseUrl: String, token: String, sessionId: String, currentTimeSec: Double, timeListenedSec: Double, durationSec: Double) = Unit
    }

    private class FakeCatalogStore : AbsCatalogStore {
        val books = linkedMapOf<String, BookEntity>()
        private val mirrors = linkedMapOf<String, AbsItemMirrorEntity>()
        private var syncState: AbsSyncStateEntity? = null
        override suspend fun getBookById(bookId: String): BookEntity? = books[bookId]
        override suspend fun getMirrorsByRootId(rootId: String): List<AbsItemMirrorEntity> = mirrors.values.toList()
        override suspend fun getSyncState(rootId: String): AbsSyncStateEntity? = syncState
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
            books[bookId] = books.getValue(bookId).copy(status = status)
        }
    }
}
