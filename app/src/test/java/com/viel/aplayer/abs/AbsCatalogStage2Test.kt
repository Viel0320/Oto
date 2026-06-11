package com.viel.aplayer.abs

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.viel.aplayer.abs.auth.AbsCredentialStore
import com.viel.aplayer.abs.mapping.AbsCatalogMapper
import com.viel.aplayer.abs.mapping.AbsProgressMapper
import com.viel.aplayer.abs.mapping.AbsRemoteIdMapper
import com.viel.aplayer.abs.net.AbsApiClient
import com.viel.aplayer.abs.net.dto.AbsAudioFileDto
import com.viel.aplayer.abs.net.dto.AbsAuthorizeResponseDto
import com.viel.aplayer.abs.net.dto.AbsAuthorizedUserDto
import com.viel.aplayer.abs.net.dto.AbsChapterDto
import com.viel.aplayer.abs.net.dto.AbsItemMediaDto
import com.viel.aplayer.abs.net.dto.AbsLibraryDto
import com.viel.aplayer.abs.net.dto.AbsLibraryItemDto
import com.viel.aplayer.abs.net.dto.AbsLibraryItemsResponseDto
import com.viel.aplayer.abs.net.dto.AbsMediaMetadataDto
import com.viel.aplayer.abs.net.dto.AbsPlayRequestDto
import com.viel.aplayer.abs.net.dto.AbsPlaybackSessionDto
import com.viel.aplayer.abs.net.dto.AbsStatusDto
import com.viel.aplayer.abs.net.dto.AbsTrackDto
import com.viel.aplayer.abs.net.dto.AbsTrackMetadataDto
import com.viel.aplayer.abs.net.dto.AbsUserProgressDto
import com.viel.aplayer.abs.sync.AbsAuthorizedProgressSynchronizer
import com.viel.aplayer.abs.sync.AbsCatalogStore
import com.viel.aplayer.abs.sync.AbsCatalogSynchronizer
import com.viel.aplayer.abs.sync.AbsItemMirrorEntity
import com.viel.aplayer.abs.sync.AbsSyncStateEntity
import com.viel.aplayer.abs.sync.isAbsPlayableBook
import com.viel.aplayer.data.db.AudiobookSchema
import com.viel.aplayer.data.entity.BookEntity
import com.viel.aplayer.data.entity.BookFileEntity
import com.viel.aplayer.data.entity.BookProgressEntity
import com.viel.aplayer.data.entity.ChapterEntity
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

class AbsCatalogStage2Test {

    private val idMapper = AbsRemoteIdMapper()
    private val progressMapper = AbsProgressMapper()
    private val catalogMapper = AbsCatalogMapper(idMapper)

    @Test
    fun `remote id mapper must be stable and scoped by user plus server`() {
        val key1 = idMapper.serverKey("https://example.com/audiobookshelf", "user-1")
        val key2 = idMapper.serverKey("https://example.com/audiobookshelf", "user-1")
        val key3 = idMapper.serverKey("https://example.com/audiobookshelf", "user-2")
        assertEquals(key1, key2)
        assertTrue(key1 != key3)
        assertEquals("abs:$key1:library:lib-1", idMapper.rootId(key1, "lib-1"))
        assertEquals("abs:$key1:item:item-1", idMapper.bookId(key1, "item-1"))
        assertEquals("abs:$key1:item:item-1:track:3", idMapper.bookFileId(key1, "item-1", 3))
    }

    @Test
    fun `catalog mapper should map pi book track and chapter to local entities`() {
        val serverKey = idMapper.serverKey("https://example.com/audiobookshelf", "user-1")
        val root = LibraryRootEntity(
            id = idMapper.rootId(serverKey, "lib-1"),
            sourceType = AudiobookSchema.LibrarySourceType.ABS,
            sourceUri = "https://example.com/audiobookshelf",
            basePath = "lib-1",
            credentialId = "cred-1",
            displayName = "Audiobooks"
        )
        val item = sampleItem(
            itemId = "item-1",
            libraryId = "lib-1",
            trackCount = 1,
            chapters = listOf(
                AbsChapterDto(id = 0, title = "Previously on 24", start = 0.0, end = 42.008)
            )
        )

        val book = catalogMapper.toBook(root, serverKey, item, existing = null, syncedAt = 1000L)
        val files = catalogMapper.toFiles(root, serverKey, item)
        val chapters = catalogMapper.toChapters(serverKey, item, files)

        assertEquals(AudiobookSchema.SourceType.ABS_REMOTE, book.sourceType)
        assertEquals("First Fifty Digits of Pi", book.title)
        assertEquals(1, files.size)
        assertEquals("/api/items/item-1/file/856465", files.first().sourcePath)
        assertEquals(0, files.first().index)
        assertEquals(1, chapters.size)
        assertEquals(files.first().id, chapters.first().bookFileId)
        assertEquals(0L, chapters.first().fileOffsetMs)
    }

    @Test
    fun `catalog mapper should preserve remote addedAt for new ABS books and keep local value on resync`() {
        val serverKey = idMapper.serverKey("https://example.com/audiobookshelf", "user-1")
        val root = LibraryRootEntity(
            id = idMapper.rootId(serverKey, "lib-1"),
            sourceType = AudiobookSchema.LibrarySourceType.ABS,
            sourceUri = "https://example.com/audiobookshelf",
            basePath = "lib-1",
            credentialId = "cred-1",
            displayName = "Audiobooks"
        )
        val remoteAddedAt = 4_000L
        val existingAddedAt = 2_000L
        val item = sampleItem(
            itemId = "item-recent",
            libraryId = "lib-1",
            addedAt = remoteAddedAt
        )

        val firstSyncBook = catalogMapper.toBook(root, serverKey, item, existing = null, syncedAt = 9_000L)
        val resyncedBook = catalogMapper.toBook(
            root = root,
            serverKey = serverKey,
            item = item.copy(addedAt = 10_000L),
            existing = firstSyncBook.copy(addedAt = existingAddedAt),
            syncedAt = 11_000L
        )

        // Remote Recency Regression Guard (Locks Recently Added ordering to ABS catalog time instead of sync execution time)
        // If this falls back to syncedAt, all books imported in one ABS run tie and the home list keeps its upstream title order.
        assertEquals(remoteAddedAt, firstSyncBook.addedAt)
        // Local Recency Stability Guard (Protects existing shelf order from later ABS metadata refreshes)
        // Once a local book exists, resync should not replace its original addedAt with either remote mutations or current clock time.
        assertEquals(existingAddedAt, resyncedBook.addedAt)
    }

    @Test
    fun `abs playable gate must only accept book items with tracks`() {
        val playable = sampleItem(itemId = "item-playable", libraryId = "lib-1", trackCount = 1)
        val audioFilesOnly = sampleItem(itemId = "item-audiofiles-only", libraryId = "lib-1", trackCount = 0).copy(
            media = AbsItemMediaDto(
                metadata = AbsMediaMetadataDto(title = "Audio Files Only"),
                tracks = emptyList(),
                audioFiles = listOf(
                    AbsAudioFileDto(
                        ino = "af-1",
                        index = 1,
                        duration = 100.0,
                        size = 1024L,
                        metadata = AbsTrackMetadataDto(
                            filename = "audio-only.mp3",
                            ext = ".mp3",
                            size = 1024L,
                            mtimeMs = 1L
                        )
                    )
                ),
                chapters = emptyList(),
                duration = 100.0,
                size = 1024L
            )
        )
        val podcast = sampleItem(itemId = "item-podcast", libraryId = "lib-1", trackCount = 1).copy(
            mediaType = "podcast"
        )

        // Track-based playback validation. The core pipeline relies strictly on 'tracks' to build BookFileEntities, so only books with tracks pass the criteria.
        assertTrue(isAbsPlayableBook(playable))
        // Missing tracks exclusion. Items having 'audioFiles' but lacking 'tracks' cannot provide play URLs and must be excluded from the local catalog mirrors.
        assertTrue(!isAbsPlayableBook(audioFilesOnly))
        // MediaType book constraint. Even with complete tracks, items whose mediaType is not 'book' must not enter the audiobook catalog mirrors.
        assertTrue(!isAbsPlayableBook(podcast))
    }

    @Test
    fun `progress mapper should convert seconds to millis and skip missing progress`() {
        val serverKey = idMapper.serverKey("https://example.com/audiobookshelf", "user-1")
        val root = LibraryRootEntity(
            id = idMapper.rootId(serverKey, "lib-1"),
            sourceType = AudiobookSchema.LibrarySourceType.ABS,
            sourceUri = "https://example.com/audiobookshelf",
            basePath = "lib-1",
            credentialId = "cred-1",
            displayName = "Audiobooks"
        )
        val itemWithProgress = sampleItem(
            itemId = "item-1",
            libraryId = "lib-1",
            progress = AbsUserProgressDto(currentTime = 12.5, isFinished = false, lastUpdate = 999L)
        )
        val book = catalogMapper.toBook(root, serverKey, itemWithProgress, existing = null, syncedAt = 1000L)
        val files = catalogMapper.toFiles(root, serverKey, itemWithProgress)
        val progress = progressMapper.toProgressOrNull(itemWithProgress, book, files, 1000L)
        assertNotNull(progress)
        assertEquals(12500L, progress?.globalPositionMs)
        assertEquals(999L, progress?.lastPlayedAt)

        val itemWithoutProgress = sampleItem(itemId = "item-2", libraryId = "lib-1", progress = null)
        val noProgressBook = catalogMapper.toBook(root, serverKey, itemWithoutProgress, existing = null, syncedAt = 1000L)
        val noProgressFiles = catalogMapper.toFiles(root, serverKey, itemWithoutProgress)
        assertNull(progressMapper.toProgressOrNull(itemWithoutProgress, noProgressBook, noProgressFiles, 1000L))
    }

    @Test
    fun `catalog sync should leave authorize media progress to authorized synchronizer`() = runBlocking {
        val serverKey = idMapper.serverKey("https://example.com/audiobookshelf", "user-1")
        val root = LibraryRootEntity(
            id = idMapper.rootId(serverKey, "lib-1"),
            sourceType = AudiobookSchema.LibrarySourceType.ABS,
            sourceUri = "https://example.com/audiobookshelf",
            basePath = "lib-1",
            credentialId = "cred-1",
            displayName = "Audiobooks"
        )
        val itemWithoutInlineProgress = sampleItem(itemId = "item-1", libraryId = "lib-1", progress = null)
        val catalogStore = FakeAbsCatalogStore()
        val credentialStore = createCredentialStore(token = "token-1", baseUrl = "https://example.com/audiobookshelf")
        val synchronizer = AbsCatalogSynchronizer(
            apiClient = FakeAbsApiClient(
                minified = AbsLibraryItemsResponseDto(results = listOf(itemWithoutInlineProgress), total = 1, limit = 1, page = 0),
                details = listOf(itemWithoutInlineProgress),
                mediaProgress = listOf(
                    AbsUserProgressDto(
                        libraryItemId = "item-1",
                        currentTime = 12.5,
                        isFinished = false,
                        lastUpdate = 999L
                    )
                )
            ),
            credentialStore = credentialStore,
            catalogStore = catalogStore,
            batchSize = 10
        )

        synchronizer.syncRootWithSummary(root)

        val bookId = idMapper.bookId(serverKey, "item-1")
        // Catalog Progress Exclusion (Locks the catalog synchronizer to structural materialization only)
        // Authorized media progress is present in the response, but this path must not write playback state without the dedicated progress synchronizer.
        assertEquals(AudiobookSchema.ReadStatus.NOT_STARTED, catalogStore.books[bookId]?.readStatus)
        assertNull(catalogStore.progress[bookId])
    }

    @Test
    fun `catalog sync should route reused mirrors through authorized progress synchronizer`() = runBlocking {
        val serverKey = idMapper.serverKey("https://example.com/audiobookshelf", "user-1")
        val root = LibraryRootEntity(
            id = idMapper.rootId(serverKey, "lib-1"),
            sourceType = AudiobookSchema.LibrarySourceType.ABS,
            sourceUri = "https://example.com/audiobookshelf",
            basePath = "lib-1",
            credentialId = "cred-1",
            displayName = "Audiobooks"
        )
        val existingItem = sampleItem(itemId = "item-1", libraryId = "lib-1", progress = null, updatedAt = 5_000L)
        val existingBook = catalogMapper.toBook(root, serverKey, existingItem, existing = null, syncedAt = 1_000L)
        val existingFiles = catalogMapper.toFiles(root, serverKey, existingItem)
        val catalogStore = FakeAbsCatalogStore(
            books = mutableMapOf(existingBook.id to existingBook),
            progress = mutableMapOf(
                existingBook.id to BookProgressEntity(bookId = existingBook.id, globalPositionMs = 1_000L, lastPlayedAt = 1_000L)
            ),
            mirrors = mutableMapOf(
                "item-1" to AbsItemMirrorEntity(
                    localBookId = existingBook.id,
                    rootId = root.id,
                    serverKey = serverKey,
                    remoteItemId = "item-1",
                    lastSeenAt = System.currentTimeMillis(),
                    remoteUpdatedAt = existingItem.updatedAt,
                    state = AudiobookSchema.AbsMirrorState.ACTIVE
                )
            ),
            syncState = AbsSyncStateEntity(
                rootId = root.id,
                serverKey = serverKey,
                libraryId = "lib-1",
                fullListFingerprint = "item-1:${existingItem.updatedAt}"
            )
        )
        val credentialStore = createCredentialStore(token = "token-1", baseUrl = "https://example.com/audiobookshelf")
        val progressGateway = FakeProgressGateway(catalogStore.progress)
        // Authorized Progress Gateway Fixture (Share one fake for catalog reads and metadata writes)
        // This keeps readStatus updates visible on catalogStore.books while matching the production split gateway constructor.
        val progressBookGateway = FakeBookQueryGateway(catalogStore.books, mapOf(existingBook.id to existingFiles))
        val authorizedProgressSynchronizer = AbsAuthorizedProgressSynchronizer(
            apiClient = FakeAbsApiClient(
                minified = AbsLibraryItemsResponseDto(results = listOf(existingItem), total = 1, limit = 1, page = 0),
                details = emptyList()
            ),
            credentialProvider = { AbsAuthorizedProgressSynchronizer.CredentialSnapshot("https://example.com/audiobookshelf", "token-1") },
            bookCatalogGateway = progressBookGateway,
            bookMetadataGateway = progressBookGateway,
            progressGateway = progressGateway
        )
        val synchronizer = AbsCatalogSynchronizer(
            apiClient = FakeAbsApiClient(
                minified = AbsLibraryItemsResponseDto(results = listOf(existingItem), total = 1, limit = 1, page = 0),
                details = emptyList(),
                mediaProgress = listOf(
                    AbsUserProgressDto(
                        libraryItemId = "item-1",
                        currentTime = 45.0,
                        isFinished = false,
                        lastUpdate = 9_000L
                    )
                )
            ),
            credentialStore = credentialStore,
            catalogStore = catalogStore,
            authorizedProgressSynchronizer = authorizedProgressSynchronizer,
            batchSize = 10
        )

        val summary = synchronizer.syncRootWithSummary(root)

        // Reused Mirror Progress Bridge (Ensures unchanged catalog rows still receive resolver-approved user progress)
        // The item detail list is empty in this path, so the update can only arrive through the generic authorized progress synchronizer.
        assertEquals(0, summary.syncedBooks)
        assertEquals(1, summary.authorizedProgress.remoteProgressCount)
        assertEquals(1, summary.authorizedProgress.appliedCount)
        assertEquals(45_000L, progressGateway.progress[existingBook.id]?.globalPositionMs)
        assertEquals(9_000L, progressGateway.progress[existingBook.id]?.lastPlayedAt)
        assertEquals(AudiobookSchema.ReadStatus.IN_PROGRESS, catalogStore.books[existingBook.id]?.readStatus)
    }

    @Test
    fun `catalog synchronizer should mark unseen active items as stale and keep addedAt on resync`() = runBlocking {
        val serverKey = idMapper.serverKey("https://example.com/audiobookshelf", "user-1")
        val root = LibraryRootEntity(
            id = idMapper.rootId(serverKey, "lib-1"),
            sourceType = AudiobookSchema.LibrarySourceType.ABS,
            sourceUri = "https://example.com/audiobookshelf",
            basePath = "lib-1",
            credentialId = "cred-1",
            displayName = "Audiobooks"
        )
        val existingBookId = idMapper.bookId(serverKey, "stale-item")
        val existingBook = BookEntity(
            id = existingBookId,
            rootId = root.id,
            sourceType = AudiobookSchema.SourceType.ABS_REMOTE,
            sourceRoot = root.sourceUri,
            title = "Old Book",
            addedAt = 123L
        )
        val store = FakeAbsCatalogStore(
            books = mutableMapOf(existingBook.id to existingBook),
            mirrors = mutableMapOf(
                "stale-item" to AbsItemMirrorEntity(
                    localBookId = existingBook.id,
                    rootId = root.id,
                    serverKey = serverKey,
                    remoteItemId = "stale-item",
                    state = AudiobookSchema.AbsMirrorState.ACTIVE
                )
            ),
            syncState = AbsSyncStateEntity(rootId = root.id, serverKey = serverKey, libraryId = "lib-1")
        )
        val credentialStore = createCredentialStore(
            baseUrl = "https://example.com/audiobookshelf",
            token = "token-1"
        )
        val api = FakeAbsApiClient(
            minified = AbsLibraryItemsResponseDto(
                results = listOf(sampleItem(itemId = "item-1", libraryId = "lib-1")),
                total = 1,
                limit = 0,
                page = 0
            ),
            details = listOf(sampleItem(itemId = "item-1", libraryId = "lib-1"))
        )
        val synchronizer = AbsCatalogSynchronizer(
            apiClient = api,
            credentialStore = credentialStore,
            catalogStore = store
        )

        synchronizer.syncRoot(root)

        val syncedBook = store.books[idMapper.bookId(serverKey, "item-1")]
        val staleMirror = store.mirrors["stale-item"]
        assertNotNull(syncedBook)
        assertEquals(AudiobookSchema.AbsMirrorState.STALE, staleMirror?.state)

        val resyncStore = FakeAbsCatalogStore(
            books = mutableMapOf(
                syncedBook!!.id to syncedBook,
                existingBook.id to existingBook
            ),
            mirrors = store.mirrors.toMutableMap(),
            syncState = store.syncState!!
        )
        val resync = AbsCatalogSynchronizer(
            apiClient = api,
            credentialStore = credentialStore,
            catalogStore = resyncStore
        )
        resync.syncRoot(root)
        assertEquals(syncedBook.addedAt, resyncStore.books[syncedBook.id]?.addedAt)
        assertEquals(AudiobookSchema.AbsMirrorState.REMOTE_DELETED, resyncStore.mirrors["stale-item"]?.state)
        assertEquals(AudiobookSchema.BookStatus.DELETED, resyncStore.books[existingBook.id]?.status)
    }

    @Test
    fun `catalog synchronizer should not mark stale when detail batch fails and must not create new book from minified only`() = runBlocking {
        val serverKey = idMapper.serverKey("https://example.com/audiobookshelf", "user-1")
        val root = LibraryRootEntity(
            id = idMapper.rootId(serverKey, "lib-1"),
            sourceType = AudiobookSchema.LibrarySourceType.ABS,
            sourceUri = "https://example.com/audiobookshelf",
            basePath = "lib-1",
            credentialId = "cred-1",
            displayName = "Audiobooks"
        )
        val existingBookId = idMapper.bookId(serverKey, "stale-item")
        val existingBook = BookEntity(
            id = existingBookId,
            rootId = root.id,
            sourceType = AudiobookSchema.SourceType.ABS_REMOTE,
            sourceRoot = root.sourceUri,
            title = "Old Book",
            addedAt = 123L
        )
        val store = FakeAbsCatalogStore(
            books = mutableMapOf(existingBook.id to existingBook),
            mirrors = mutableMapOf(
                "stale-item" to AbsItemMirrorEntity(
                    localBookId = existingBook.id,
                    rootId = root.id,
                    serverKey = serverKey,
                    remoteItemId = "stale-item",
                    state = AudiobookSchema.AbsMirrorState.ACTIVE
                )
            ),
            syncState = AbsSyncStateEntity(rootId = root.id, serverKey = serverKey, libraryId = "lib-1")
        )
        val credentialStore = createCredentialStore(
            baseUrl = "https://example.com/audiobookshelf",
            token = "token-1"
        )
        val api = FailingBatchAbsApiClient(
            minified = AbsLibraryItemsResponseDto(
                results = listOf(sampleItem(itemId = "item-1", libraryId = "lib-1")),
                total = 1,
                limit = 0,
                page = 0
            )
        )
        val synchronizer = AbsCatalogSynchronizer(
            apiClient = api,
            credentialStore = credentialStore,
            catalogStore = store
        )

        synchronizer.syncRoot(root)

        // Network resilience logic. When detail batch fetches fail entirely, existing but currently unseen mirrors should not be marked as STALE,
        // preventing network fluctuations from incorrectly triggering deletion confirmation processes.
        assertEquals(AudiobookSchema.AbsMirrorState.ACTIVE, store.mirrors["stale-item"]?.state)
        // Minified data limits. The minified response only discovers items and lacks track/URL details;
        // hence, we must not write new books to the local mirrors without detail responses to prevent unplayable empty items.
        assertNull(store.books[idMapper.bookId(serverKey, "item-1")])
    }

    private fun createCredentialStore(baseUrl: String, token: String): AbsCredentialStore {
        val tempDir = createTempDirectory(prefix = "abs-catalog-stage2").toFile()
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

    private fun sampleItem(
        itemId: String,
        libraryId: String,
        trackCount: Int = 1,
        chapters: List<AbsChapterDto> = listOf(
            AbsChapterDto(id = 0, title = "Chapter 1", start = 0.0, end = 10.0)
        ),
        progress: AbsUserProgressDto? = null,
        updatedAt: Long = 100L,
        // ABS Fixture Added Time (Models remote catalog recency independently from test execution time)
        // Recently Added regressions need configurable ABS addedAt values instead of a fixed shared fixture timestamp.
        addedAt: Long = 50L
    ): AbsLibraryItemDto {
        val tracks = (1..trackCount).map { index ->
            val ino = if (index == 1) "856465" else "85646$index"
            AbsTrackDto(
                index = index,
                ino = ino,
                startOffset = (index - 1) * 100.0,
                duration = 100.0,
                contentUrl = "/api/items/$itemId/file/$ino",
                mimeType = "audio/mpeg",
                title = if (index == 1) "First Fifty Digits of Pi" else "Track $index",
                metadata = AbsTrackMetadataDto(
                    filename = if (index == 1) "FirstFiftyDigitsOfPi_librivox.mp3" else "track-$index.mp3",
                    ext = ".mp3",
                    size = 77458087L,
                    mtimeMs = 1732560692887L
                )
            )
        }
        val audioFiles = tracks.map { track ->
            AbsAudioFileDto(
                ino = track.ino,
                index = track.index,
                duration = track.duration,
                size = 77458087L,
                metadata = track.metadata
            )
        }
        return AbsLibraryItemDto(
            id = itemId,
            libraryId = libraryId,
            mediaType = "book",
            title = "First Fifty Digits of Pi",
            updatedAt = updatedAt,
            addedAt = addedAt,
            media = AbsItemMediaDto(
                metadata = AbsMediaMetadataDto(
                    title = "First Fifty Digits of Pi",
                    authorName = "Scott Hemphill",
                    narratorName = "",
                    publishedYear = "",
                    description = ""
                ),
                tracks = tracks,
                audioFiles = audioFiles,
                chapters = chapters,
                duration = tracks.sumOf { it.duration ?: 0.0 },
                size = audioFiles.sumOf { it.size ?: 0L }
            ),
            authors = emptyList(),
            progress = progress
        )
    }

    private class FakeAbsApiClient(
        private val minified: AbsLibraryItemsResponseDto,
        private val details: List<AbsLibraryItemDto>,
        private val mediaProgress: List<AbsUserProgressDto> = emptyList()
    ) : AbsApiClient {
        override suspend fun status(baseUrl: String): AbsStatusDto = AbsStatusDto(serverVersion = "2.35.1", isInit = true)
        override suspend fun login(baseUrl: String, username: String, password: String) =
            throw UnsupportedOperationException()
        override suspend fun authorize(baseUrl: String, token: String): AbsAuthorizeResponseDto =
            AbsAuthorizeResponseDto(user = AbsAuthorizedUserDto(id = "user-1", username = "demo-user", token = token, mediaProgress = mediaProgress))
        override suspend fun getLibraries(baseUrl: String, token: String): List<AbsLibraryDto> =
            listOf(AbsLibraryDto(id = "lib-1", name = "Audiobooks", mediaType = "book"))
        override suspend fun getLibraryItemsMinified(baseUrl: String, token: String, libraryId: String): AbsLibraryItemsResponseDto =
            minified
        override suspend fun batchGetItems(baseUrl: String, token: String, itemIds: List<String>): List<AbsLibraryItemDto> =
            details.filter { item -> item.id in itemIds }
        override suspend fun openPlaybackSession(baseUrl: String, token: String, itemId: String, request: AbsPlayRequestDto) =
            AbsPlaybackSessionDto(id = "session-1", libraryItemId = itemId)
        override suspend fun syncSession(baseUrl: String, token: String, sessionId: String, currentTimeSec: Double, timeListenedSec: Double, durationSec: Double) = Unit
        override suspend fun closeSession(baseUrl: String, token: String, sessionId: String, currentTimeSec: Double, timeListenedSec: Double, durationSec: Double) = Unit
    }

    private class FailingBatchAbsApiClient(
        private val minified: AbsLibraryItemsResponseDto
    ) : AbsApiClient {
        override suspend fun status(baseUrl: String): AbsStatusDto = AbsStatusDto(serverVersion = "2.35.1", isInit = true)
        override suspend fun login(baseUrl: String, username: String, password: String) =
            throw UnsupportedOperationException()
        override suspend fun authorize(baseUrl: String, token: String): AbsAuthorizeResponseDto =
            AbsAuthorizeResponseDto(user = AbsAuthorizedUserDto(id = "user-1", username = "demo-user", token = token))
        override suspend fun getLibraries(baseUrl: String, token: String): List<AbsLibraryDto> =
            listOf(AbsLibraryDto(id = "lib-1", name = "Audiobooks", mediaType = "book"))
        override suspend fun getLibraryItemsMinified(baseUrl: String, token: String, libraryId: String): AbsLibraryItemsResponseDto =
            minified
        override suspend fun batchGetItems(baseUrl: String, token: String, itemIds: List<String>): List<AbsLibraryItemDto> =
            error("batch failed")
        override suspend fun openPlaybackSession(baseUrl: String, token: String, itemId: String, request: AbsPlayRequestDto) =
            AbsPlaybackSessionDto(id = "session-1", libraryItemId = itemId)
        override suspend fun syncSession(baseUrl: String, token: String, sessionId: String, currentTimeSec: Double, timeListenedSec: Double, durationSec: Double) = Unit
        override suspend fun closeSession(baseUrl: String, token: String, sessionId: String, currentTimeSec: Double, timeListenedSec: Double, durationSec: Double) = Unit
    }

    private class FakeAbsCatalogStore(
        val books: MutableMap<String, BookEntity> = linkedMapOf(),
        val progress: MutableMap<String, BookProgressEntity> = linkedMapOf(),
        val mirrors: MutableMap<String, AbsItemMirrorEntity> = linkedMapOf(),
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
            // Catalog Store Fixture Scope (Mirrors the production boundary by avoiding progress writes during catalog upsert)
            // Progress remains available to FakeProgressGateway so authorized progress tests can prove the separate merge path.
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
            books[bookId]?.let { existing ->
                books[bookId] = existing.copy(status = status)
            }
        }
    }

    private class FakeBookQueryGateway(
        private val books: MutableMap<String, BookEntity>,
        private val files: Map<String, List<BookFileEntity>>
    ) : BookCatalogGateway,
        BookMetadataGateway,
        BookmarkGateway,
        ChapterGateway,
        BookDeletionGateway {
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
        override suspend fun updateBookReadStatus(bookId: String, readStatus: AudiobookSchema.ReadStatus) {
            books[bookId]?.let { existing -> books[bookId] = existing.copy(readStatus = readStatus) }
        }
        override suspend fun updateBookDetails(id: String, title: String, author: String, narrator: String, description: String, year: String, series: String) = Unit
        override suspend fun getFilesForBookSync(bookId: String): List<BookFileEntity> = files[bookId].orEmpty()
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
        val progress: MutableMap<String, BookProgressEntity>
    ) : ProgressGateway {
        override fun updateProgress(bookId: String, position: Long) = Unit
        override suspend fun saveProgress(progress: BookProgressEntity) {
            this.progress[progress.bookId] = progress
        }
        override suspend fun getLastPlayedProgressSync(): BookProgressEntity? = progress.values.maxByOrNull { it.lastPlayedAt }
        override suspend fun getProgressForBookSync(bookId: String): BookProgressEntity? = progress[bookId]
    }
}
