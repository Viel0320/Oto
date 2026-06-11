package com.viel.aplayer.abs

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.viel.aplayer.abs.auth.AbsCredentialStore
import com.viel.aplayer.abs.net.AbsApiError
import com.viel.aplayer.abs.net.dto.AbsPlayRequestDto
import com.viel.aplayer.abs.net.dto.AbsPlaybackSessionDto
import com.viel.aplayer.abs.sync.AbsCatalogStore
import com.viel.aplayer.abs.sync.AbsCatalogSynchronizer
import com.viel.aplayer.abs.sync.AbsCoverStore
import com.viel.aplayer.abs.sync.AbsItemMirrorEntity
import com.viel.aplayer.abs.sync.AbsSyncStateEntity
import com.viel.aplayer.abs.vfs.AbsSourceProvider
import com.viel.aplayer.data.db.AudiobookSchema
import com.viel.aplayer.data.entity.BookEntity
import com.viel.aplayer.data.entity.BookFileEntity
import com.viel.aplayer.data.entity.ChapterEntity
import com.viel.aplayer.data.entity.LibraryRootEntity
import com.viel.aplayer.data.store.AppSettings
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
        val credentialStore = createCredentialStore("https://example.com/audiobookshelf", "token-1")
        val provider = AbsSourceProvider(
            context = null,
            credentialStore = credentialStore,
            settingsProvider = ::allowCleartextSettings
        )
        val root = LibraryRootEntity(
            id = "root-1",
            sourceType = AudiobookSchema.LibrarySourceType.ABS,
            sourceUri = "https://example.com/audiobookshelf",
            basePath = "lib-1",
            credentialId = "cred-1",
            displayName = "Audiobooks"
        )
        val resolved = provider.resolveContentUrl(
            baseUrl = "https://example.com/audiobookshelf/",
            root = root,
            contentUrl = "/api/items/item-1/file/856465"
        )
        assertEquals("https://example.com/audiobookshelf/api/items/item-1/file/856465", resolved.toString())
        try {
            provider.resolveContentUrl(
                baseUrl = "https://example.com/audiobookshelf/",
                root = root,
                contentUrl = "https://malicious.example/api/items/item-1/file/856465"
            )
            error("Expected exception")
        } catch (_: Exception) {
            // expected
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
            val credentialStore = createCredentialStore(server.url("/audiobookshelf/").toString(), "token-1")
            val provider = AbsSourceProvider(
                context = null,
                credentialStore = credentialStore,
                settingsProvider = ::allowCleartextSettings
            )
            val root = LibraryRootEntity(
                id = "root-1",
                sourceType = AudiobookSchema.LibrarySourceType.ABS,
                sourceUri = server.url("/audiobookshelf").toString().trimEnd('/'),
                basePath = "lib-1",
                credentialId = "cred-1",
                displayName = "Audiobooks"
            )
            val node = requireNotNull(provider.resolve(root, "/api/items/item-1/file/856465"))
            provider.openInputStream(node)?.close()
            provider.openInputStream(node, offset = 5)?.close()

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
            val credentialStore = createCredentialStore(server.url("/audiobookshelf/").toString(), "token-1")
            val provider = AbsSourceProvider(
                context = null,
                credentialStore = credentialStore,
                settingsProvider = ::allowCleartextSettings
            )
            val root = LibraryRootEntity(
                id = "root-1",
                sourceType = AudiobookSchema.LibrarySourceType.ABS,
                sourceUri = server.url("/audiobookshelf").toString().trimEnd('/'),
                basePath = "lib-1",
                credentialId = "cred-1",
                displayName = "Audiobooks"
            )
            val node = requireNotNull(provider.resolve(root, "/api/items/item-1/file/856465"))

            try {
                provider.openInputStream(node, offset = 5)?.close()
                fail("Expected ignored ABS range responses to fail")
            } catch (error: AbsApiError) {
                // Ignored Range Regression (Protects seek and resume from replaying ABS streams from byte zero)
                // The provider must reject HTTP 200 after requesting a non-zero byte offset because the server did not prove offset alignment.
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
            val credentialStore = createCredentialStore(server.url("/audiobookshelf/").toString(), "token-1")
            val provider = AbsSourceProvider(
                context = null,
                credentialStore = credentialStore,
                settingsProvider = ::allowCleartextSettings
            )
            val root = LibraryRootEntity(
                id = "root-1",
                sourceType = AudiobookSchema.LibrarySourceType.ABS,
                sourceUri = server.url("/audiobookshelf").toString().trimEnd('/'),
                basePath = "lib-1",
                credentialId = "cred-1",
                displayName = "Audiobooks"
            )
            val node = requireNotNull(provider.resolve(root, "/api/items/item-1/file/856465"))

            provider.readRange(node, offset = Long.MAX_VALUE - 1L, length = 8)

            val request = server.takeRequest()
            // ABS Range Overflow Regression (Locks pathological metadata probes to a saturated HTTP byte interval)
            // A near-Long.MAX_VALUE offset plus a positive bounded length used to wrap the request end into a negative number, producing malformed Range headers.
            assertEquals("bytes=${Long.MAX_VALUE - 1L}-${Long.MAX_VALUE}", request.getHeader("Range"))
        }
    }

    @Test
    fun `catalog synchronizer should write cached cover paths into book`() = runBlocking {
        val credentialStore = createCredentialStore("https://example.com/audiobookshelf", "token-1")
        val store = FakeCatalogStore()
        val synchronizer = AbsCatalogSynchronizer(
            apiClient = FakeCatalogApi(),
            credentialStore = credentialStore,
            catalogStore = store,
            coverCache = FakeCoverStore("cover.jpg", "thumb.jpg")
        )
        val root = LibraryRootEntity(
            id = "root-1",
            sourceType = AudiobookSchema.LibrarySourceType.ABS,
            sourceUri = "https://example.com/audiobookshelf",
            basePath = "lib-1",
            credentialId = "cred-1",
            displayName = "Audiobooks"
        )

        synchronizer.syncRoot(root)

        val book = store.books.values.firstOrNull()
        assertNotNull(book)
        assertEquals("cover.jpg", book?.coverPath)
        assertEquals("thumb.jpg", book?.thumbnailPath)
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
     * Test Cleartext Settings (Opts MockWebServer HTTP streams into transport compatibility)
     *
     * ABS media stream tests use local HTTP endpoints to inspect headers and Range behavior; production
     * code still defaults to blocking cleartext unless the user enables the global setting.
     */
    private fun allowCleartextSettings(): AppSettings =
        AppSettings(isCleartextTrafficAllowed = true)

    private class FakeCoverStore(
        private val coverPath: String,
        private val thumbPath: String
    ) : AbsCoverStore {
        override suspend fun downloadCover(
            root: LibraryRootEntity,
            remoteItemId: String
        ) = com.viel.aplayer.media.parser.CoverExtractor.CoverResult(coverPath, thumbPath, 123)
    }

    private class FakeCatalogApi : com.viel.aplayer.abs.net.AbsApiClient {
        override suspend fun status(baseUrl: String) = com.viel.aplayer.abs.net.dto.AbsStatusDto(serverVersion = "2.35.1", isInit = true)
        override suspend fun login(baseUrl: String, username: String, password: String) = throw UnsupportedOperationException()
        override suspend fun authorize(baseUrl: String, token: String) =
            com.viel.aplayer.abs.net.dto.AbsAuthorizeResponseDto(
                user = com.viel.aplayer.abs.net.dto.AbsAuthorizedUserDto(id = "user-1", username = "demo-user", token = token)
            )
        override suspend fun getLibraries(baseUrl: String, token: String) = emptyList<com.viel.aplayer.abs.net.dto.AbsLibraryDto>()
        override suspend fun getLibraryItemsMinified(baseUrl: String, token: String, libraryId: String) =
            com.viel.aplayer.abs.net.dto.AbsLibraryItemsResponseDto(
                results = listOf(
                    com.viel.aplayer.abs.net.dto.AbsLibraryItemDto(
                        id = "item-1",
                        libraryId = libraryId,
                        mediaType = "book",
                        title = "Pi",
                        updatedAt = 1L,
                        media = com.viel.aplayer.abs.net.dto.AbsItemMediaDto(
                            metadata = com.viel.aplayer.abs.net.dto.AbsMediaMetadataDto(title = "Pi", authorName = "Scott"),
                            tracks = listOf(
                                com.viel.aplayer.abs.net.dto.AbsTrackDto(
                                    index = 1,
                                    duration = 10.0,
                                    contentUrl = "/api/items/item-1/file/856465",
                                    metadata = com.viel.aplayer.abs.net.dto.AbsTrackMetadataDto(filename = "pi.mp3", ext = ".mp3", size = 10L)
                                )
                            ),
                            chapters = listOf(
                                com.viel.aplayer.abs.net.dto.AbsChapterDto(id = 0, title = "c1", start = 0.0, end = 10.0)
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
            // Source Provider Catalog Fixture Scope (Stores only catalog rows needed for virtual file resolution)
            // Playback progress is intentionally absent because source provider tests exercise ABS path construction, not progress merging.
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
