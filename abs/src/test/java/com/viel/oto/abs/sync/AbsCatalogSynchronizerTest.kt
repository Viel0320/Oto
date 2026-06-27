package com.viel.oto.abs.sync

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.viel.oto.abs.auth.AbsCredentialStore
import com.viel.oto.abs.mapping.AbsCatalogMapper
import com.viel.oto.abs.mapping.AbsProgressMapper
import com.viel.oto.abs.mapping.AbsRemoteIdMapper
import com.viel.oto.abs.net.AbsApiClient
import com.viel.oto.abs.net.dto.AbsAudioFileDto
import com.viel.oto.abs.net.dto.AbsAuthorizeResponseDto
import com.viel.oto.abs.net.dto.AbsAuthorizedUserDto
import com.viel.oto.abs.net.dto.AbsChapterDto
import com.viel.oto.abs.net.dto.AbsItemMediaDto
import com.viel.oto.abs.net.dto.AbsLibraryDto
import com.viel.oto.abs.net.dto.AbsLibraryItemDto
import com.viel.oto.abs.net.dto.AbsLibraryItemsResponseDto
import com.viel.oto.abs.net.dto.AbsMediaMetadataDto
import com.viel.oto.abs.net.dto.AbsPlayRequestDto
import com.viel.oto.abs.net.dto.AbsPlaybackSessionDto
import com.viel.oto.abs.net.dto.AbsStatusDto
import com.viel.oto.abs.net.dto.AbsTrackDto
import com.viel.oto.abs.net.dto.AbsTrackMetadataDto
import com.viel.oto.abs.net.dto.AbsUserProgressDto
import com.viel.oto.data.abs.sync.AbsCatalogStore
import com.viel.oto.data.abs.sync.AbsItemMirrorEntity
import com.viel.oto.data.abs.sync.AbsSyncStateEntity
import com.viel.oto.data.book.BookCatalogGateway
import com.viel.oto.data.book.BookDeletionGateway
import com.viel.oto.data.book.BookMetadataGateway
import com.viel.oto.data.book.BookmarkGateway
import com.viel.oto.data.book.ChapterGateway
import com.viel.oto.data.db.AudiobookSchema
import com.viel.oto.data.entity.BookEntity
import com.viel.oto.data.entity.BookFileEntity
import com.viel.oto.data.entity.BookProgressEntity
import com.viel.oto.data.entity.ChapterEntity
import com.viel.oto.data.entity.LibraryRootEntity
import com.viel.oto.data.progress.ProgressGateway
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

class AbsCatalogSynchronizerTest {

    private val idMapper = AbsRemoteIdMapper()
    private val progressMapper = AbsProgressMapper()
    private val catalogMapper = AbsCatalogMapper(idMapper)

    @Test
    fun `catalog sync should leave authorize media progress to authorized synchronizer`() = runBlocking {
        val serverKey = idMapper.serverKey("https://example.com/AudiobookShelf", "user-1")
        val root = LibraryRootEntity(
            id = idMapper.rootId(serverKey, "lib-1"),
            sourceType = AudiobookSchema.LibrarySourceType.ABS,
            sourceUri = "https://example.com/AudiobookShelf",
            basePath = "lib-1",
            credentialId = "cred-1",
            displayName = "Audiobooks"
        )
        val itemWithoutInlineProgress = sampleItem(itemId = "item-1", libraryId = "lib-1", progress = null)
        val catalogStore = FakeAbsCatalogStore()
        val credentialStore = createCredentialStore(token = "token-1", baseUrl = "https://example.com/AudiobookShelf")
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
        assertEquals(AudiobookSchema.ReadStatus.NOT_STARTED, catalogStore.books[bookId]?.readStatus)
        assertNull(catalogStore.progress[bookId])
    }

    @Test
    fun `catalog sync should route reused mirrors through authorized progress synchronizer`() = runBlocking {
        val serverKey = idMapper.serverKey("https://example.com/AudiobookShelf", "user-1")
        val root = LibraryRootEntity(
            id = idMapper.rootId(serverKey, "lib-1"),
            sourceType = AudiobookSchema.LibrarySourceType.ABS,
            sourceUri = "https://example.com/AudiobookShelf",
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
                /**
                 * Aligns test metadata with SHA-256 fingerprint logic.
                 * Ensures the mock sync state fullListFingerprint uses the same hash computation as the synchronizer class.
                 */
                fullListFingerprint = AbsCatalogSynchronizer.minifiedFingerprint(listOf(existingItem))
            )
        )
        val credentialStore = createCredentialStore(token = "token-1", baseUrl = "https://example.com/AudiobookShelf")
        val progressGateway = FakeProgressGateway(catalogStore.progress)
        val progressBookGateway = FakeBookQueryGateway(catalogStore.books, mapOf(existingBook.id to existingFiles))
        val authorizedProgressSynchronizer = AbsAuthorizedProgressSynchronizer(
            apiClient = FakeAbsApiClient(
                minified = AbsLibraryItemsResponseDto(results = listOf(existingItem), total = 1, limit = 1, page = 0),
                details = emptyList()
            ),
            credentialProvider = { AbsAuthorizedProgressSynchronizer.CredentialSnapshot("https://example.com/AudiobookShelf", "token-1") },
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

        assertEquals(0, summary.syncedBooks)
        assertEquals(1, summary.authorizedProgress.remoteProgressCount)
        assertEquals(1, summary.authorizedProgress.appliedCount)
        assertEquals(45_000L, progressGateway.progress[existingBook.id]?.globalPositionMs)
        assertEquals(9_000L, progressGateway.progress[existingBook.id]?.lastPlayedAt)
        assertEquals(AudiobookSchema.ReadStatus.IN_PROGRESS, catalogStore.books[existingBook.id]?.readStatus)
    }

    @Test
    fun `catalog synchronizer should mark unseen active items as stale and keep addedAt on resync`() = runBlocking {
        val serverKey = idMapper.serverKey("https://example.com/AudiobookShelf", "user-1")
        val root = LibraryRootEntity(
            id = idMapper.rootId(serverKey, "lib-1"),
            sourceType = AudiobookSchema.LibrarySourceType.ABS,
            sourceUri = "https://example.com/AudiobookShelf",
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
            baseUrl = "https://example.com/AudiobookShelf",
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
        val serverKey = idMapper.serverKey("https://example.com/AudiobookShelf", "user-1")
        val root = LibraryRootEntity(
            id = idMapper.rootId(serverKey, "lib-1"),
            sourceType = AudiobookSchema.LibrarySourceType.ABS,
            sourceUri = "https://example.com/AudiobookShelf",
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
            baseUrl = "https://example.com/AudiobookShelf",
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

        assertEquals(AudiobookSchema.AbsMirrorState.ACTIVE, store.mirrors["stale-item"]?.state)
        assertNull(store.books[idMapper.bookId(serverKey, "item-1")])
    }

    private fun createCredentialStore(baseUrl: String, token: String): AbsCredentialStore {
        val tempDir = createTempDirectory(prefix = "abs-catalog-sync").toFile()
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
        override val audiobooks: Flow<List<com.viel.oto.data.entity.BookWithProgress>> = flowOf(emptyList())
        override suspend fun getBookById(id: String): BookEntity? = books[id]
        override fun observeBookById(id: String): Flow<BookEntity?> = flowOf(books[id])
        override fun searchAudiobooks(query: String): Flow<List<com.viel.oto.data.entity.BookWithProgress>> = flowOf(emptyList())
        override fun filterByYear(year: String): Flow<List<com.viel.oto.data.entity.BookWithProgress>> = flowOf(emptyList())
        override fun filterByAuthor(author: String): Flow<List<com.viel.oto.data.entity.BookWithProgress>> = flowOf(emptyList())
        override fun filterByAuthorLimited(author: String, excludeId: String, limit: Int): Flow<List<com.viel.oto.data.entity.BookWithProgress>> = flowOf(emptyList())
        override fun filterByNarrator(narrator: String): Flow<List<com.viel.oto.data.entity.BookWithProgress>> = flowOf(emptyList())
        override fun filterByNarratorLimited(narrator: String, excludeId: String, limit: Int): Flow<List<com.viel.oto.data.entity.BookWithProgress>> = flowOf(emptyList())
        override fun getRecentlyAdded(limit: Int): Flow<List<com.viel.oto.data.entity.BookWithProgress>> = flowOf(emptyList())
        override fun getRecentlyAddedExclusive(currentId: String, authors: List<String>, narrators: List<String>, limit: Int): Flow<List<com.viel.oto.data.entity.BookWithProgress>> = flowOf(emptyList())
        override suspend fun deleteBook(bookId: String) = Unit
        override suspend fun updateBookReadStatus(bookId: String, readStatus: AudiobookSchema.ReadStatus) {
            books[bookId]?.let { existing -> books[bookId] = existing.copy(readStatus = readStatus) }
        }
        override suspend fun updateBookDetails(id: String, title: String, author: String, narrator: String, description: String, year: String, series: String) = Unit
        override suspend fun getFilesForBookSync(bookId: String): List<BookFileEntity> = files[bookId].orEmpty()
        override suspend fun getAllFilesForBookSync(bookId: String): List<BookFileEntity> = getFilesForBookSync(bookId)
        override fun updateMetadata(bookId: String, title: String?, author: String?, narrator: String?, description: String?, duration: Long) = Unit
        override fun getChapters(bookId: String): Flow<List<com.viel.oto.data.entity.ChapterWithBookFile>> = flowOf(emptyList())
        override suspend fun getChaptersForBookSync(bookId: String): List<com.viel.oto.data.entity.ChapterWithBookFile> = emptyList()
        override fun saveChapters(bookId: String, chapters: List<ChapterEntity>) = Unit
        override fun getBookmarks(bookId: String): Flow<List<com.viel.oto.data.entity.BookmarkEntity>> = flowOf(emptyList())
        override suspend fun addBookmark(bookId: String, position: Long, title: String) = Unit
        override suspend fun updateBookmark(bookmark: com.viel.oto.data.entity.BookmarkEntity) = Unit
        override suspend fun deleteBookmark(bookmark: com.viel.oto.data.entity.BookmarkEntity) = Unit
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
