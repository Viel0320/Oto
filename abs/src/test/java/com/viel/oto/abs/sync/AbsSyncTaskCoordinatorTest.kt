package com.viel.oto.abs.sync

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.viel.oto.abs.auth.AbsCredentialStore
import com.viel.oto.abs.net.AbsApiClient
import com.viel.oto.abs.net.dto.AbsAuthorizeResponseDto
import com.viel.oto.abs.net.dto.AbsLibraryDto
import com.viel.oto.abs.net.dto.AbsLibraryItemDto
import com.viel.oto.abs.net.dto.AbsLibraryItemsResponseDto
import com.viel.oto.abs.net.dto.AbsLoginResponseDto
import com.viel.oto.abs.net.dto.AbsPlayRequestDto
import com.viel.oto.abs.net.dto.AbsPlaybackSessionDto
import com.viel.oto.abs.net.dto.AbsStatusDto
import com.viel.oto.data.abs.sync.AbsCatalogStore
import com.viel.oto.data.abs.sync.AbsItemMirrorEntity
import com.viel.oto.data.abs.sync.AbsSyncStateEntity
import com.viel.oto.data.dao.LibraryRootDao
import com.viel.oto.data.db.AudiobookSchema
import com.viel.oto.data.entity.BookEntity
import com.viel.oto.data.entity.BookFileEntity
import com.viel.oto.data.entity.ChapterEntity
import com.viel.oto.data.entity.LibraryRootEntity
import com.viel.oto.library.availability.LibraryRootAvailabilityUpdate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory

class AbsSyncTaskCoordinatorTest {

    @Test
    fun `catalog cancellation should not emit coordinator failure event or toast`() = runBlocking {
        val api = CancellingAbsApi(CancellationException("ABS sync cancelled"))
        val feedbackSink = RecordingAbsSyncFeedbackSink()
        val coordinator = createCoordinator(api = api, feedbackSink = feedbackSink)
        val events = CopyOnWriteArrayList<AbsSyncTaskResult>()
        val collector = CoordinatorEventCollector.start(coordinator, events)

        try {
            assertTrue(coordinator.start(ROOT_ID, AbsSyncTaskOrigin.MANUAL))
            assertTrue(api.authorizeEntered.await(1, TimeUnit.SECONDS))
            assertFalse(
                "Cancellation should remain control flow instead of producing failure feedback.",
                eventually { events.isNotEmpty() || feedbackSink.feedback.isNotEmpty() }
            )
        } finally {
            coordinator.close()
            collector.stop()
        }
    }

    @Test
    fun `close should cancel suspended sync without emitting failure feedback`() = runBlocking {
        val api = HangingAbsApi()
        val feedbackSink = RecordingAbsSyncFeedbackSink()
        val coordinator = createCoordinator(api = api, feedbackSink = feedbackSink)
        val events = CopyOnWriteArrayList<AbsSyncTaskResult>()
        val collector = CoordinatorEventCollector.start(coordinator, events)

        try {
            assertTrue(coordinator.start(ROOT_ID, AbsSyncTaskOrigin.MANUAL))
            assertTrue(api.authorizeEntered.await(1, TimeUnit.SECONDS))
            coordinator.close()
            assertTrue(api.cancellationObserved.await(1, TimeUnit.SECONDS))
            assertFalse(
                "Graph teardown should cancel suspended ABS work without user-facing failure feedback.",
                eventually(timeoutMs = 200) { events.isNotEmpty() || feedbackSink.feedback.isNotEmpty() }
            )
        } finally {
            collector.stop()
        }
    }

    @Test
    fun `ordinary sync exception should still emit redacted coordinator failure event and toast`() = runBlocking {
        val api = FailingAbsApi(IllegalStateException("Remote failed with Bearer secret-token"))
        val feedbackSink = RecordingAbsSyncFeedbackSink()
        val coordinator = createCoordinator(api = api, feedbackSink = feedbackSink)
        val events = CopyOnWriteArrayList<AbsSyncTaskResult>()
        val collector = CoordinatorEventCollector.start(coordinator, events)

        try {
            assertTrue(coordinator.start(ROOT_ID, AbsSyncTaskOrigin.AUTO_ADD))
            assertTrue(api.authorizeEntered.await(1, TimeUnit.SECONDS))
            assertTrue(
                "Non-cancellation failures should still reach the coordinator failure channel.",
                eventually { events.isNotEmpty() && feedbackSink.feedback.isNotEmpty() }
            )

            val result = events.single()
            assertEquals(ROOT_ID, result.rootId)
            assertEquals(ROOT_ID, result.displayName)
            assertEquals(AbsSyncTaskOrigin.AUTO_ADD, result.origin)
            assertEquals("Remote failed with Bearer <redacted>", result.errorMessage)

            assertEquals(
                RecordedAbsSyncFeedback.Failed(ROOT_ID, "Remote failed with Bearer <redacted>"),
                feedbackSink.feedback.single()
            )
        } finally {
            coordinator.close()
            collector.stop()
        }
    }

    private fun createCoordinator(
        api: AbsApiClient,
        feedbackSink: RecordingAbsSyncFeedbackSink
    ): AbsSyncTaskCoordinator {
        val credentialStore = createCredentialStore()
        val synchronizer = AbsCatalogSynchronizer(
            apiClient = api,
            credentialStore = credentialStore,
            catalogStore = FakeCatalogStore()
        )
        return AbsSyncTaskCoordinator(
            libraryRootDao = FakeLibraryRootDao(absRoot()),
            synchronizer = synchronizer,
            feedbackSink = feedbackSink,
            scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        )
    }

    private fun createCredentialStore(): AbsCredentialStore {
        val tempDir = createTempDirectory(prefix = "abs-sync-task-coordinator").toFile()
        val store = AbsCredentialStore.createForTesting(
            PreferenceDataStoreFactory.create(
                produceFile = { File(tempDir, "credentials.preferences_pb") }
            )
        )
        runBlocking {
            store.save(
                baseUrl = "https://example.com/AudiobookShelf",
                token = "token-1",
                credentialId = CREDENTIAL_ID
            )
        }
        return store
    }

    private fun absRoot() = LibraryRootEntity(
        id = ROOT_ID,
        sourceType = AudiobookSchema.LibrarySourceType.ABS,
        sourceUri = "https://example.com/AudiobookShelf",
        basePath = "lib-1",
        credentialId = CREDENTIAL_ID,
        displayName = "ABS"
    )

    private fun eventually(timeoutMs: Long = 1_000, predicate: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (System.nanoTime() < deadline) {
            if (predicate()) return true
            Thread.sleep(10)
        }
        return predicate()
    }

    private class CoordinatorEventCollector(
        private val scope: CoroutineScope,
        private val job: Job
    ) {
        suspend fun stop() {
            job.cancelAndJoin()
            scope.cancel()
        }

        companion object {
            fun start(
                coordinator: AbsSyncTaskCoordinator,
                events: MutableList<AbsSyncTaskResult>
            ): CoordinatorEventCollector {
                val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
                val job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
                    coordinator.events.collect { event ->
                        events += event
                    }
                }
                return CoordinatorEventCollector(scope, job)
            }
        }
    }

    private abstract class BaseAbsApi : AbsApiClient {
        override suspend fun status(baseUrl: String): AbsStatusDto =
            AbsStatusDto(serverVersion = "2.35.1", isInit = true)
        override suspend fun login(baseUrl: String, username: String, password: String): AbsLoginResponseDto =
            throw UnsupportedOperationException()
        override suspend fun authorize(baseUrl: String, token: String): AbsAuthorizeResponseDto =
            throw UnsupportedOperationException()
        override suspend fun getLibraries(baseUrl: String, token: String): List<AbsLibraryDto> =
            throw UnsupportedOperationException()
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

    private class CancellingAbsApi(
        private val cancellation: CancellationException
    ) : BaseAbsApi() {
        val authorizeEntered = CountDownLatch(1)

        override suspend fun authorize(baseUrl: String, token: String): AbsAuthorizeResponseDto {
            authorizeEntered.countDown()
            throw cancellation
        }
    }

    private class HangingAbsApi : BaseAbsApi() {
        val authorizeEntered = CountDownLatch(1)
        val cancellationObserved = CountDownLatch(1)

        override suspend fun authorize(baseUrl: String, token: String): AbsAuthorizeResponseDto {
            authorizeEntered.countDown()
            try {
                awaitCancellation()
            } finally {
                cancellationObserved.countDown()
            }
        }
    }

    private class FailingAbsApi(
        private val failure: RuntimeException
    ) : BaseAbsApi() {
        val authorizeEntered = CountDownLatch(1)

        override suspend fun authorize(baseUrl: String, token: String): AbsAuthorizeResponseDto {
            authorizeEntered.countDown()
            throw failure
        }
    }

    private class FakeLibraryRootDao(
        private val root: LibraryRootEntity
    ) : LibraryRootDao {
        override fun getAllRoots(): Flow<List<LibraryRootEntity>> = flowOf(listOf(root))
        override suspend fun getActiveRootsOnce(): List<LibraryRootEntity> = listOf(root)
        override suspend fun getActiveAbsRootsOnce(): List<LibraryRootEntity> =
            root.takeIf(::isActiveAbsRoot)?.let(::listOf).orEmpty()
        override suspend fun getRootById(id: String): LibraryRootEntity? = root.takeIf { it.id == id }
        override suspend fun getAllRootsOnce(): List<LibraryRootEntity> = listOf(root)
        override suspend fun insertRoot(root: LibraryRootEntity) = Unit
        override suspend fun updateRootGrantState(
            id: String,
            displayName: String,
            grantedAt: Long,
            status: AudiobookSchema.LibraryRootStatus
        ) = Unit
        override suspend fun updateRootScanState(id: String, lastScannedAt: Long, status: AudiobookSchema.LibraryRootStatus) = Unit
        override suspend fun updateRootStatus(id: String, status: AudiobookSchema.LibraryRootStatus) = Unit
        override suspend fun updateRootAvailability(
            id: String,
            availabilityStatus: AudiobookSchema.AvailabilityStatus,
            checkedAt: Long,
            errorCode: String?
        ) = Unit
        override suspend fun deleteRoot(root: LibraryRootEntity) = Unit

        private fun isActiveAbsRoot(root: LibraryRootEntity): Boolean {
            return root.status == AudiobookSchema.LibraryRootStatus.ACTIVE &&
                root.sourceType == AudiobookSchema.LibrarySourceType.ABS
        }
    }

    private class RecordingAbsSyncFeedbackSink : AbsSyncFeedbackSink {
        val feedback = CopyOnWriteArrayList<RecordedAbsSyncFeedback>()

        override fun syncRootMissing() {
            feedback += RecordedAbsSyncFeedback.RootMissing
        }

        override fun syncBlocked(rootId: String, availability: LibraryRootAvailabilityUpdate) {
            feedback += RecordedAbsSyncFeedback.Blocked(rootId, availability)
        }

        override fun syncCompleted(rootId: String, summary: AbsSyncSummary) {
            feedback += RecordedAbsSyncFeedback.Completed(rootId, summary)
        }

        override fun syncFailed(rootId: String, redactedMessage: String) {
            feedback += RecordedAbsSyncFeedback.Failed(rootId, redactedMessage)
        }
    }

    private sealed interface RecordedAbsSyncFeedback {
        data object RootMissing : RecordedAbsSyncFeedback
        data class Blocked(val rootId: String, val availability: LibraryRootAvailabilityUpdate) : RecordedAbsSyncFeedback
        data class Completed(val rootId: String, val summary: AbsSyncSummary) : RecordedAbsSyncFeedback
        data class Failed(val rootId: String, val redactedMessage: String) : RecordedAbsSyncFeedback
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
            this.syncState = syncState
        }
        override suspend fun replaceMirrors(mirrors: List<AbsItemMirrorEntity>) = Unit
        override suspend fun saveSyncState(syncState: AbsSyncStateEntity) {
            this.syncState = syncState
        }
        override suspend fun updateBookStatus(bookId: String, status: AudiobookSchema.BookStatus) = Unit
    }

    private companion object {
        private const val ROOT_ID = "root-1"
        private const val CREDENTIAL_ID = "cred-1"
    }
}
