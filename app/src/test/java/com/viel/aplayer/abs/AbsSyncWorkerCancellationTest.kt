package com.viel.aplayer.abs

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.viel.aplayer.abs.auth.AbsCredentialStore
import com.viel.aplayer.abs.net.AbsApiClient
import com.viel.aplayer.abs.net.dto.AbsAuthorizeResponseDto
import com.viel.aplayer.abs.net.dto.AbsAuthorizedUserDto
import com.viel.aplayer.abs.net.dto.AbsItemMediaDto
import com.viel.aplayer.abs.net.dto.AbsLibraryDto
import com.viel.aplayer.abs.net.dto.AbsLibraryItemDto
import com.viel.aplayer.abs.net.dto.AbsLibraryItemsResponseDto
import com.viel.aplayer.abs.net.dto.AbsLoginResponseDto
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
import com.viel.aplayer.abs.sync.AbsSyncWorker
import com.viel.aplayer.data.db.AudiobookSchema
import com.viel.aplayer.data.entity.BookEntity
import com.viel.aplayer.data.entity.BookFileEntity
import com.viel.aplayer.data.entity.BookProgressEntity
import com.viel.aplayer.data.entity.ChapterEntity
import com.viel.aplayer.data.entity.LibraryRootEntity
import com.viel.aplayer.data.book.BookCatalogGateway
import com.viel.aplayer.data.book.BookMetadataGateway
import com.viel.aplayer.data.progress.ProgressGateway
import com.viel.aplayer.di.dependencies.AbsSyncWorkerDependencies
import com.viel.aplayer.event.AppEventSink
import com.viel.aplayer.event.AppShellEvent
import com.viel.aplayer.event.feedback.FeedbackDeliveryResult
import com.viel.aplayer.event.feedback.TransientFeedbackFact
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

class AbsSyncWorkerCancellationTest {

    @Test
    fun `worker sync should propagate catalog cancellation instead of retrying`() = runBlocking {
        val cancellation = CancellationException("ABS worker sync cancelled")
        val synchronizer = AbsCatalogSynchronizer(
            apiClient = CancellingAbsApi(cancellation),
            credentialStore = createCredentialStore(),
            catalogStore = FakeCatalogStore()
        )
        val dependencies = FakeWorkerDependencies(synchronizer)

        try {
            // Worker Cancellation Regression (Prevents WorkManager cancellation from being converted into Result.retry)
            // The fake API cancels during the real catalog synchronizer path, so this assertion covers the worker retry boundary.
            AbsSyncWorker.runSync(
                rootId = "root-1",
                root = absRoot(),
                workerDependencies = dependencies
            )
            fail("Expected CancellationException to propagate from ABS sync worker")
        } catch (error: CancellationException) {
            assertSame(cancellation, error)
        }
    }

    @Test
    fun `worker sync should propagate detail batch cancellation instead of retrying`() = runBlocking {
        val cancellation = CancellationException("ABS batch fetch cancelled")
        val api = BatchCancellingAbsApi(cancellation)
        val store = FakeCatalogStore()
        val synchronizer = AbsCatalogSynchronizer(
            apiClient = api,
            credentialStore = createCredentialStore(),
            catalogStore = store
        )

        try {
            // Detail Batch Cancellation Regression (Covers the first expanded item fetch boundary)
            // A cancelled batch request must escape the worker instead of being downgraded into per-item fallback or Result.success.
            AbsSyncWorker.runSync(
                rootId = "root-1",
                root = absRoot(),
                workerDependencies = FakeWorkerDependencies(synchronizer)
            )
            fail("Expected CancellationException to propagate from ABS detail batch fetch")
        } catch (error: CancellationException) {
            assertSame(cancellation, error)
            assertEquals(1, api.detailRequestCount)
            assertNull(store.syncState?.lastError)
        }
    }

    @Test
    fun `worker sync should stop retry loop when single item retry is cancelled`() = runBlocking {
        val cancellation = CancellationException("ABS single item retry cancelled")
        val api = RetryCancellingAbsApi(cancellation)
        val store = FakeCatalogStore()
        val synchronizer = AbsCatalogSynchronizer(
            apiClient = api,
            credentialStore = createCredentialStore(),
            catalogStore = store
        )

        try {
            // Retry Cancellation Regression (Prevents cancellation from consuming all retry attempts)
            // Ordinary batch failures may enter item fallback, but the first cancelled retry must stop the whole sync.
            AbsSyncWorker.runSync(
                rootId = "root-1",
                root = absRoot(),
                workerDependencies = FakeWorkerDependencies(synchronizer)
            )
            fail("Expected CancellationException to propagate from ABS single item retry")
        } catch (error: CancellationException) {
            assertSame(cancellation, error)
            assertEquals(2, api.detailRequestCount)
            assertNull(store.syncState?.lastError)
        }
    }

    @Test
    fun `worker sync should propagate item materialization cancellation`() = runBlocking {
        val cancellation = CancellationException("ABS item materialization cancelled")
        val store = FakeCatalogStore(cancellationOnGetBook = cancellation)
        val synchronizer = AbsCatalogSynchronizer(
            apiClient = SuccessfulDetailAbsApi(),
            credentialStore = createCredentialStore(),
            catalogStore = store
        )

        try {
            // Materialization Cancellation Regression (Covers database reads inside item upsert)
            // Item-level best effort may absorb ordinary mapping or persistence failures, but not coroutine cancellation.
            AbsSyncWorker.runSync(
                rootId = "root-1",
                root = absRoot(),
                workerDependencies = FakeWorkerDependencies(synchronizer)
            )
            fail("Expected CancellationException to propagate from ABS item materialization")
        } catch (error: CancellationException) {
            assertSame(cancellation, error)
            assertNull(store.syncState?.lastError)
        }
    }

    @Test
    fun `worker sync should propagate authorized progress merge cancellation without failure summary`() = runBlocking {
        val cancellation = CancellationException("ABS authorized progress merge cancelled")
        val store = FakeCatalogStore()
        val synchronizer = AbsCatalogSynchronizer(
            apiClient = AuthorizedProgressCancellingAbsApi(),
            credentialStore = createCredentialStore(),
            catalogStore = store,
            authorizedProgressSynchronizer = AbsAuthorizedProgressSynchronizer(
                apiClient = UnsupportedAbsApi(),
                credentialProvider = { null },
                bookCatalogGateway = CancellingBookCatalogGateway(cancellation),
                bookMetadataGateway = NoOpBookMetadataGateway(),
                progressGateway = NoOpProgressGateway()
            )
        )

        try {
            // Authorized Progress Cancellation Regression (Covers the post-catalog merge boundary)
            // The catalog rows may already have a success sync state, but cancellation must not become a progress failure summary.
            AbsSyncWorker.runSync(
                rootId = "root-1",
                root = absRoot(),
                workerDependencies = FakeWorkerDependencies(synchronizer)
            )
            fail("Expected CancellationException to propagate from ABS authorized progress merge")
        } catch (error: CancellationException) {
            assertSame(cancellation, error)
            assertNull(store.syncState?.lastError)
        }
    }

    private fun absRoot() = LibraryRootEntity(
        id = "root-1",
        sourceType = AudiobookSchema.LibrarySourceType.ABS,
        sourceUri = "https://example.com/audiobookshelf",
        basePath = "lib-1",
        credentialId = "cred-1",
        displayName = "ABS"
    )

    private fun createCredentialStore(): AbsCredentialStore {
        val tempDir = createTempDirectory(prefix = "abs-sync-worker").toFile()
        val store = AbsCredentialStore.createForTesting(
            PreferenceDataStoreFactory.create(
                produceFile = { File(tempDir, "credentials.preferences_pb") }
            )
        )
        runBlocking {
            // Worker Credential Fixture (Keeps the synchronizer on its production credential lookup path)
            // The test only varies the remote API cancellation, so credentials remain valid and deterministic.
            store.save(
                baseUrl = "https://example.com/audiobookshelf",
                token = "token-1",
                credentialId = "cred-1"
            )
        }
        return store
    }

    /**
     * Catalog Cancellation API Base (Keeps all deeper cancellation tests on the same successful setup path)
     * Individual subclasses override only the detail or progress boundary that should cancel, keeping worker assertions focused.
     */
    private abstract class CatalogCancellationAbsApi : AbsApiClient {
        override suspend fun status(baseUrl: String): AbsStatusDto = AbsStatusDto(serverVersion = "2.35.1", isInit = true)
        override suspend fun login(baseUrl: String, username: String, password: String): AbsLoginResponseDto = throw UnsupportedOperationException()
        override suspend fun authorize(baseUrl: String, token: String): AbsAuthorizeResponseDto =
            AbsAuthorizeResponseDto(user = AbsAuthorizedUserDto(id = "user-1", username = "demo-user", token = token))
        override suspend fun getLibraries(baseUrl: String, token: String): List<AbsLibraryDto> = emptyList()
        override suspend fun getLibraryItemsMinified(baseUrl: String, token: String, libraryId: String): AbsLibraryItemsResponseDto =
            AbsLibraryItemsResponseDto(
                results = listOf(AbsLibraryItemDto(id = "item-1", libraryId = libraryId, mediaType = "book", updatedAt = 100L)),
                total = 1,
                limit = 0,
                page = 0
            )
        override suspend fun openPlaybackSession(
            baseUrl: String,
            token: String,
            itemId: String,
            request: AbsPlayRequestDto
        ): AbsPlaybackSessionDto = throw UnsupportedOperationException()
        override suspend fun syncSession(
            baseUrl: String,
            token: String,
            sessionId: String,
            currentTimeSec: Double,
            timeListenedSec: Double,
            durationSec: Double
        ) = Unit
        override suspend fun closeSession(
            baseUrl: String,
            token: String,
            sessionId: String,
            currentTimeSec: Double,
            timeListenedSec: Double,
            durationSec: Double
        ) = Unit
    }

    private class BatchCancellingAbsApi(
        private val cancellation: CancellationException
    ) : CatalogCancellationAbsApi() {
        var detailRequestCount: Int = 0

        override suspend fun batchGetItems(baseUrl: String, token: String, itemIds: List<String>): List<AbsLibraryItemDto> {
            // Batch Cancellation Fixture (Cancels on the first expanded item request)
            // This reproduces cancellation being treated as a batch failure and falling into single-item retry.
            detailRequestCount += 1
            throw cancellation
        }
    }

    private class RetryCancellingAbsApi(
        private val cancellation: CancellationException
    ) : CatalogCancellationAbsApi() {
        var detailRequestCount: Int = 0

        override suspend fun batchGetItems(baseUrl: String, token: String, itemIds: List<String>): List<AbsLibraryItemDto> {
            // Retry Cancellation Fixture (Fails the aggregate batch, then cancels the first fallback request)
            // The request count proves cancellation stops before the retry loop consumes its remaining attempts.
            detailRequestCount += 1
            if (detailRequestCount == 1) error("batch failed before retry")
            throw cancellation
        }
    }

    private class SuccessfulDetailAbsApi : CatalogCancellationAbsApi() {
        override suspend fun batchGetItems(baseUrl: String, token: String, itemIds: List<String>): List<AbsLibraryItemDto> =
            itemIds.map { itemId -> playableDetail(itemId) }
    }

    private class AuthorizedProgressCancellingAbsApi : CatalogCancellationAbsApi() {
        override suspend fun authorize(baseUrl: String, token: String): AbsAuthorizeResponseDto =
            AbsAuthorizeResponseDto(
                user = AbsAuthorizedUserDto(
                    id = "user-1",
                    username = "demo-user",
                    token = token,
                    mediaProgress = listOf(
                        AbsUserProgressDto(
                            libraryItemId = "item-1",
                            currentTime = 12.0,
                            isFinished = false,
                            lastUpdate = 1_000L
                        )
                    )
                )
            )

        override suspend fun getLibraryItemsMinified(baseUrl: String, token: String, libraryId: String): AbsLibraryItemsResponseDto =
            AbsLibraryItemsResponseDto(results = emptyList(), total = 0, limit = 0, page = 0)

        override suspend fun batchGetItems(baseUrl: String, token: String, itemIds: List<String>): List<AbsLibraryItemDto> =
            emptyList()
    }

    private class UnsupportedAbsApi : AbsApiClient {
        override suspend fun status(baseUrl: String): AbsStatusDto = throw UnsupportedOperationException()
        override suspend fun login(baseUrl: String, username: String, password: String): AbsLoginResponseDto = throw UnsupportedOperationException()
        override suspend fun authorize(baseUrl: String, token: String): AbsAuthorizeResponseDto = throw UnsupportedOperationException()
        override suspend fun getLibraries(baseUrl: String, token: String): List<AbsLibraryDto> = throw UnsupportedOperationException()
        override suspend fun getLibraryItemsMinified(baseUrl: String, token: String, libraryId: String): AbsLibraryItemsResponseDto = throw UnsupportedOperationException()
        override suspend fun batchGetItems(baseUrl: String, token: String, itemIds: List<String>): List<AbsLibraryItemDto> = throw UnsupportedOperationException()
        override suspend fun openPlaybackSession(baseUrl: String, token: String, itemId: String, request: AbsPlayRequestDto): AbsPlaybackSessionDto = throw UnsupportedOperationException()
        override suspend fun syncSession(baseUrl: String, token: String, sessionId: String, currentTimeSec: Double, timeListenedSec: Double, durationSec: Double) = throw UnsupportedOperationException()
        override suspend fun closeSession(baseUrl: String, token: String, sessionId: String, currentTimeSec: Double, timeListenedSec: Double, durationSec: Double) = throw UnsupportedOperationException()
    }

    private class CancellingBookCatalogGateway(
        private val cancellation: CancellationException
    ) : BookCatalogGateway {
        override val audiobooks: Flow<List<com.viel.aplayer.data.entity.BookWithProgress>> = flowOf(emptyList())
        override suspend fun getBookById(id: String): BookEntity? {
            // Authorized Progress Merge Cancellation Fixture (Cancels at the first local catalog lookup)
            // The catalog synchronizer must treat this as cooperative cancellation rather than a recoverable progress merge failure.
            throw cancellation
        }
        override fun observeBookById(id: String): Flow<BookEntity?> = flowOf(null)
        override fun searchAudiobooks(query: String): Flow<List<com.viel.aplayer.data.entity.BookWithProgress>> = flowOf(emptyList())
        override fun filterByYear(year: String): Flow<List<com.viel.aplayer.data.entity.BookWithProgress>> = flowOf(emptyList())
        override fun filterByAuthor(author: String): Flow<List<com.viel.aplayer.data.entity.BookWithProgress>> = flowOf(emptyList())
        override fun filterByAuthorLimited(author: String, excludeId: String, limit: Int): Flow<List<com.viel.aplayer.data.entity.BookWithProgress>> = flowOf(emptyList())
        override fun filterByNarrator(narrator: String): Flow<List<com.viel.aplayer.data.entity.BookWithProgress>> = flowOf(emptyList())
        override fun filterByNarratorLimited(narrator: String, excludeId: String, limit: Int): Flow<List<com.viel.aplayer.data.entity.BookWithProgress>> = flowOf(emptyList())
        override fun getRecentlyAdded(limit: Int): Flow<List<com.viel.aplayer.data.entity.BookWithProgress>> = flowOf(emptyList())
        override fun getRecentlyAddedExclusive(currentId: String, authors: List<String>, narrators: List<String>, limit: Int): Flow<List<com.viel.aplayer.data.entity.BookWithProgress>> = flowOf(emptyList())
        override suspend fun getFilesForBookSync(bookId: String): List<BookFileEntity> = emptyList()
        override suspend fun getAllFilesForBookSync(bookId: String): List<BookFileEntity> = emptyList()
    }

    private class NoOpBookMetadataGateway : BookMetadataGateway {
        // Update signature to ReadStatus enum for type safety.
        override suspend fun updateBookReadStatus(bookId: String, readStatus: AudiobookSchema.ReadStatus) = Unit
        override suspend fun updateBookDetails(id: String, title: String, author: String, narrator: String, description: String, year: String, series: String) = Unit
        override fun updateMetadata(bookId: String, title: String?, author: String?, narrator: String?, description: String?, duration: Long) = Unit
    }

    private class NoOpProgressGateway : ProgressGateway {
        override fun updateProgress(bookId: String, position: Long) = Unit
        override suspend fun saveProgress(progress: BookProgressEntity) = Unit
        override suspend fun getLastPlayedProgressSync(): BookProgressEntity? = null
        override suspend fun getProgressForBookSync(bookId: String): BookProgressEntity? = null
    }

    private companion object {
        /**
         * Playable Detail Fixture (Creates the smallest ABS book detail that passes the catalog materialization gate)
         * The cancellation tests need a real upsert path, so the item includes one track with a valid content URL.
         */
        fun playableDetail(itemId: String): AbsLibraryItemDto =
            AbsLibraryItemDto(
                id = itemId,
                libraryId = "lib-1",
                mediaType = "book",
                title = "Detail $itemId",
                updatedAt = 100L,
                media = AbsItemMediaDto(
                    metadata = AbsMediaMetadataDto(title = "Detail $itemId"),
                    tracks = listOf(
                        AbsTrackDto(
                            index = 1,
                            duration = 30.0,
                            contentUrl = "/api/items/$itemId/file/1",
                            metadata = AbsTrackMetadataDto(filename = "$itemId.mp3")
                        )
                    ),
                    duration = 30.0
                )
            )
    }

    private class CancellingAbsApi(
        private val cancellation: CancellationException
    ) : AbsApiClient {
        override suspend fun status(baseUrl: String): AbsStatusDto = AbsStatusDto(serverVersion = "2.35.1", isInit = true)
        override suspend fun login(baseUrl: String, username: String, password: String): AbsLoginResponseDto = throw UnsupportedOperationException()
        override suspend fun authorize(baseUrl: String, token: String): AbsAuthorizeResponseDto {
            // Remote API Cancellation Fixture (Injects cancellation at the first catalog request boundary)
            // This keeps the regression focused on worker error mapping rather than catalog item transformation.
            throw cancellation
        }
        override suspend fun getLibraries(baseUrl: String, token: String): List<AbsLibraryDto> = emptyList()
        override suspend fun getLibraryItemsMinified(baseUrl: String, token: String, libraryId: String): AbsLibraryItemsResponseDto =
            throw UnsupportedOperationException()
        override suspend fun batchGetItems(baseUrl: String, token: String, itemIds: List<String>): List<AbsLibraryItemDto> =
            throw UnsupportedOperationException()
        override suspend fun openPlaybackSession(
            baseUrl: String,
            token: String,
            itemId: String,
            request: AbsPlayRequestDto
        ): AbsPlaybackSessionDto = throw UnsupportedOperationException()
        override suspend fun syncSession(
            baseUrl: String,
            token: String,
            sessionId: String,
            currentTimeSec: Double,
            timeListenedSec: Double,
            durationSec: Double
        ) = Unit
        override suspend fun closeSession(
            baseUrl: String,
            token: String,
            sessionId: String,
            currentTimeSec: Double,
            timeListenedSec: Double,
            durationSec: Double
        ) = Unit
    }

    private class FakeWorkerDependencies(
        override val absCatalogSynchronizer: AbsCatalogSynchronizer
    ) : AbsSyncWorkerDependencies {
        override val appEventSink: AppEventSink = FakeAppEventSink()
    }

    private class FakeAppEventSink : AppEventSink {
        override val events: SharedFlow<AppShellEvent> = MutableSharedFlow()
        override fun emitFeedback(fact: TransientFeedbackFact): FeedbackDeliveryResult =
            FeedbackDeliveryResult.Delivered(fact)
        override fun showTrackUnavailableDialog(bookId: String, queueIndex: Int): Boolean = false
    }

    private class FakeCatalogStore(
        private val cancellationOnGetBook: CancellationException? = null
    ) : AbsCatalogStore {
        var syncState: AbsSyncStateEntity? = null
        override suspend fun getBookById(bookId: String): BookEntity? {
            // Catalog Materialization Cancellation Fixture (Allows tests to cancel inside the upsert path)
            // The default remains an empty in-memory store so existing worker cancellation tests keep their original setup.
            cancellationOnGetBook?.let { cancellation -> throw cancellation }
            return null
        }
        override suspend fun getMirrorsByRootId(rootId: String): List<AbsItemMirrorEntity> = emptyList()
        override suspend fun getSyncState(rootId: String): AbsSyncStateEntity? = syncState
        override suspend fun upsertCatalogMirror(
            book: BookEntity,
            files: List<BookFileEntity>,
            chapters: List<ChapterEntity>,
            mirror: AbsItemMirrorEntity,
            syncState: AbsSyncStateEntity
        ) {
            // Catalog Store Cancellation Fixture (Accepts failure-state writes caused by the synchronizer finally path)
            // The worker assertion is about exception propagation, so catalog persistence is intentionally inert.
            this.syncState = syncState
        }
        override suspend fun replaceMirrors(mirrors: List<AbsItemMirrorEntity>) = Unit
        override suspend fun saveSyncState(syncState: AbsSyncStateEntity) {
            this.syncState = syncState
        }
        // Update signature to BookStatus enum for type safety.
        override suspend fun updateBookStatus(bookId: String, status: AudiobookSchema.BookStatus) = Unit
    }
}
