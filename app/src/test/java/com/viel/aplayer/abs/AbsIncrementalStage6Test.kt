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
import com.viel.aplayer.abs.sync.AbsItemMirrorEntity
import com.viel.aplayer.abs.sync.AbsSyncStateEntity
import com.viel.aplayer.abs.sync.buildAbsIncrementalErrorSummary
import com.viel.aplayer.abs.sync.selectAbsDetailCandidateIds
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

class AbsIncrementalStage6Test {

    private val idMapper = AbsRemoteIdMapper()
    private val progressMapper = AbsProgressMapper()
    private val catalogMapper = AbsCatalogMapper(idMapper, progressMapper)

    @Test
    fun `incremental selector should skip unchanged items and keep changed or new items in detail queue`() {
        val existingMirrors = mapOf(
            "item-1" to AbsItemMirrorEntity(
                localBookId = "book-1",
                rootId = "root-1",
                serverKey = "server-1",
                remoteItemId = "item-1",
                remoteUpdatedAt = 100L,
                state = AudiobookSchema.AbsMirrorState.ACTIVE
            ),
            "item-2" to AbsItemMirrorEntity(
                localBookId = "book-2",
                rootId = "root-1",
                serverKey = "server-1",
                remoteItemId = "item-2",
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

        // 详尽的中文注释：当整份 minified 指纹完全没变时，已有 mirror 的 item 不应再进入详情队列。
        assertTrue(
            selectAbsDetailCandidateIds(
                minifiedItems = unchangedItems,
                existingMirrors = existingMirrors,
                previousFullListFingerprint = "item-1:100|item-2:200",
                currentFullListFingerprint = "item-1:100|item-2:200"
            ).isEmpty()
        )

        // 详尽的中文注释：一旦指纹变化，增量逻辑必须把“remoteUpdatedAt 变化的旧 item”和“本地没有 mirror 的新 item”拉进详情队列。
        assertEquals(
            listOf("item-2", "item-3"),
            selectAbsDetailCandidateIds(
                minifiedItems = changedItems,
                existingMirrors = existingMirrors,
                previousFullListFingerprint = "item-1:100|item-2:200",
                currentFullListFingerprint = "item-1:100|item-2:201|item-3:50"
            )
        )
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

        // 详尽的中文注释：第一次 batch/get 失败后，同步器只允许再做三次单 item 重试，超过上限立即放弃本轮条目。
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
                    remoteUpdatedAt = 100L,
                    state = AudiobookSchema.AbsMirrorState.REMOTE_DELETED
                )
            ),
            syncState = AbsSyncStateEntity(
                rootId = root.id,
                serverKey = serverKey,
                libraryId = root.basePath,
                fullListFingerprint = "item-1:100"
            )
        )
        val api = UnchangedListApi()
        val synchronizer = AbsCatalogSynchronizer(
            apiClient = api,
            credentialStore = credentialStore,
            catalogStore = store
        )

        synchronizer.syncRoot(root)

        // 详尽的中文注释：当 minified 指纹未变化且历史镜像已存在时，应直接复用本地镜像并激活 mirror，
        // 不应该为了“把状态改回 ACTIVE”而额外重拉详情。
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

        // 详尽的中文注释：摘要必须把真正新增成功入库的书本数和最终失败的 item 数分开统计，
        // 这样设置页自动后台同步完成后的 toast 才能准确回答用户“添加了几本、失败了几本”。
        assertEquals(2, summary.totalItems)
        assertEquals(1, summary.addedBooks)
        assertEquals(1, summary.syncedBooks)
        assertEquals(1, summary.failedItems)
        assertEquals(0, summary.reusedBooks)
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

        // 详尽的中文注释：低版本或裁剪后的服务端响应即使缺 tracks，也只能让结果自然变空，不能让 DTO 解析或 mapper 崩溃。
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

        // 详尽的中文注释：track metadata 缺少 size 时只应回退到 0；progress 缺 lastUpdate 时应回退到同步时刻，不允许空指针。
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

        // 详尽的中文注释：root 级错误文案只保留失败数量和首个失败条目，避免设置页被长错误列表淹没。
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
            books[bookId]?.let { book ->
                books[bookId] = book.copy(status = status)
            }
        }
    }
}
