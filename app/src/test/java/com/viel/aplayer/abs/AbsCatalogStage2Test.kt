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
    private val catalogMapper = AbsCatalogMapper(idMapper, progressMapper)

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

        // 详尽的中文注释：当前真正可落为 BookFileEntity 的主链只认 tracks，所以有 tracks 的 book 必须通过筛选。
        assertTrue(isAbsPlayableBook(playable))
        // 详尽的中文注释：只有 audioFiles 而没有 tracks 的条目无法提供 contentUrl，不应再被误放行进入镜像库。
        assertTrue(!isAbsPlayableBook(audioFilesOnly))
        // 详尽的中文注释：即使 tracks 完整，只要 mediaType 不是 book，也不能进入 Audiobooks catalog mirror。
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

        // 详尽的中文注释：整批 detail 拉取失败时，已存在但这轮未见的镜像不应被误收敛成 STALE，
        // 否则单次网络抖动就会错误推进删除确认状态。
        assertEquals(AudiobookSchema.AbsMirrorState.ACTIVE, store.mirrors["stale-item"]?.state)
        // 详尽的中文注释：minified 只负责“发现 item”，不携带 tracks/contentUrl 真相；
        // 因此拿不到 detail 时不能直接把新书写进本地镜像库，避免生成无可播音轨的半成品书籍。
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
        progress: AbsUserProgressDto? = null
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
            updatedAt = 100L,
            addedAt = 50L,
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
        private val details: List<AbsLibraryItemDto>
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
            progress: BookProgressEntity?,
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
        override suspend fun updateBookStatus(bookId: String, status: String) {
            books[bookId]?.let { existing ->
                books[bookId] = existing.copy(status = status)
            }
        }
    }
}
