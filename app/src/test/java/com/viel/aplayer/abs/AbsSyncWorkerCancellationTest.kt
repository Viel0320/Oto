package com.viel.aplayer.abs

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.viel.aplayer.abs.auth.AbsCredentialStore
import com.viel.aplayer.abs.net.AbsApiClient
import com.viel.aplayer.abs.net.dto.AbsAuthorizeResponseDto
import com.viel.aplayer.abs.net.dto.AbsLibraryDto
import com.viel.aplayer.abs.net.dto.AbsLibraryItemDto
import com.viel.aplayer.abs.net.dto.AbsLibraryItemsResponseDto
import com.viel.aplayer.abs.net.dto.AbsLoginResponseDto
import com.viel.aplayer.abs.net.dto.AbsPlayRequestDto
import com.viel.aplayer.abs.net.dto.AbsPlaybackSessionDto
import com.viel.aplayer.abs.net.dto.AbsStatusDto
import com.viel.aplayer.abs.sync.AbsCatalogStore
import com.viel.aplayer.abs.sync.AbsCatalogSynchronizer
import com.viel.aplayer.abs.sync.AbsItemMirrorEntity
import com.viel.aplayer.abs.sync.AbsSyncStateEntity
import com.viel.aplayer.abs.sync.AbsSyncWorker
import com.viel.aplayer.data.db.AudiobookSchema
import com.viel.aplayer.data.entity.BookEntity
import com.viel.aplayer.data.entity.BookFileEntity
import com.viel.aplayer.data.entity.ChapterEntity
import com.viel.aplayer.data.entity.LibraryRootEntity
import com.viel.aplayer.dependencies.AbsSyncWorkerDependencies
import com.viel.aplayer.event.AppEventSink
import com.viel.aplayer.event.AppShellEvent
import com.viel.aplayer.event.feedback.FeedbackDeliveryResult
import com.viel.aplayer.event.feedback.TransientFeedbackFact
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.runBlocking
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

    private class FakeCatalogStore : AbsCatalogStore {
        private var syncState: AbsSyncStateEntity? = null
        override suspend fun getBookById(bookId: String): BookEntity? = null
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
        override suspend fun updateBookStatus(bookId: String, status: String) = Unit
    }
}
