package com.viel.aplayer.abs.sync

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
import com.viel.aplayer.data.dao.LibraryRootDao
import com.viel.aplayer.data.db.AudiobookSchema
import com.viel.aplayer.data.entity.BookEntity
import com.viel.aplayer.data.entity.BookFileEntity
import com.viel.aplayer.data.entity.ChapterEntity
import com.viel.aplayer.data.entity.LibraryRootEntity
import com.viel.aplayer.event.AppEventSink
import com.viel.aplayer.event.AppShellEvent
import com.viel.aplayer.event.feedback.FeedbackCategory
import com.viel.aplayer.event.feedback.FeedbackContext
import com.viel.aplayer.event.feedback.FeedbackDeliveryResult
import com.viel.aplayer.event.feedback.FeedbackFact
import com.viel.aplayer.event.feedback.FeedbackMessage
import com.viel.aplayer.event.feedback.FeedbackSeverity
import com.viel.aplayer.event.feedback.FeedbackTopic
import com.viel.aplayer.event.feedback.LibraryAccessForm
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
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
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
        val appEventSink = RecordingAppEventSink()
        val coordinator = createCoordinator(api = api, appEventSink = appEventSink)
        val events = CopyOnWriteArrayList<AbsSyncTaskResult>()
        val collector = CoordinatorEventCollector.start(coordinator, events)

        try {
            assertTrue(coordinator.start(ROOT_ID, AbsSyncTaskOrigin.MANUAL))
            assertTrue(api.authorizeEntered.await(1, TimeUnit.SECONDS))
            assertFalse(
                "Cancellation should remain control flow instead of producing failure feedback.",
                eventually { events.isNotEmpty() || appEventSink.feedbackFacts.isNotEmpty() }
            )
        } finally {
            // Test Scope Cleanup (Stops coordinator and collector resources after the cancellation assertion)
            // The coordinator owns an injected background scope, while the collector belongs to runBlocking.
            coordinator.close()
            collector.stop()
        }
    }

    @Test
    fun `close should cancel suspended sync without emitting failure feedback`() = runBlocking {
        val api = HangingAbsApi()
        val appEventSink = RecordingAppEventSink()
        val coordinator = createCoordinator(api = api, appEventSink = appEventSink)
        val events = CopyOnWriteArrayList<AbsSyncTaskResult>()
        val collector = CoordinatorEventCollector.start(coordinator, events)

        try {
            assertTrue(coordinator.start(ROOT_ID, AbsSyncTaskOrigin.MANUAL))
            assertTrue(api.authorizeEntered.await(1, TimeUnit.SECONDS))
            coordinator.close()
            assertTrue(api.cancellationObserved.await(1, TimeUnit.SECONDS))
            assertFalse(
                "Graph teardown should cancel suspended ABS work without user-facing failure feedback.",
                eventually(timeoutMs = 200) { events.isNotEmpty() || appEventSink.feedbackFacts.isNotEmpty() }
            )
        } finally {
            // Collector Cleanup (Ensures the hot event flow observer never leaks beyond this test)
            // close() may already have cancelled the coordinator scope, so only the local collector remains.
            collector.stop()
        }
    }

    @Test
    fun `ordinary sync exception should still emit redacted coordinator failure event and toast`() = runBlocking {
        val api = FailingAbsApi(IllegalStateException("Remote failed with Bearer secret-token"))
        val appEventSink = RecordingAppEventSink()
        val coordinator = createCoordinator(api = api, appEventSink = appEventSink)
        val events = CopyOnWriteArrayList<AbsSyncTaskResult>()
        val collector = CoordinatorEventCollector.start(coordinator, events)

        try {
            assertTrue(coordinator.start(ROOT_ID, AbsSyncTaskOrigin.AUTO_ADD))
            assertTrue(api.authorizeEntered.await(1, TimeUnit.SECONDS))
            assertTrue(
                "Non-cancellation failures should still reach the coordinator failure channel.",
                eventually { events.isNotEmpty() && appEventSink.feedbackFacts.isNotEmpty() }
            )

            val result = events.single()
            assertEquals(ROOT_ID, result.rootId)
            assertEquals(ROOT_ID, result.displayName)
            assertEquals(AbsSyncTaskOrigin.AUTO_ADD, result.origin)
            assertEquals("Remote failed with Bearer <redacted>", result.errorMessage)

            val fact = appEventSink.feedbackFacts.single()
            val toast = fact.message
            assertTrue(toast is FeedbackMessage.Resource)
            assertEquals(
                listOf("Remote failed with Bearer <redacted>"),
                (toast as FeedbackMessage.Resource).args
            )
            // Background ABS sync failure must classify as a library-access sync outcome keyed to the
            // specific root so it never absorbs another root's sync feedback.
            val identity = fact.outcome.identity
            assertEquals(FeedbackCategory.LIBRARY_ACCESS, identity.category)
            assertEquals(FeedbackTopic.LibrarySync, identity.topic)
            assertEquals(
                FeedbackContext.LibraryRoot(ROOT_ID, LibraryAccessForm.AUDIOBOOKSHELF),
                identity.context
            )
            assertEquals(FeedbackSeverity.FAILED, fact.outcome.severity)
        } finally {
            // Failure Test Cleanup (Cancels both producer and consumer scopes after assertions complete)
            // This keeps later tests isolated from the coordinator's application-style background scope.
            coordinator.close()
            collector.stop()
        }
    }

    private fun createCoordinator(
        api: AbsApiClient,
        appEventSink: RecordingAppEventSink
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
            appEventSink = appEventSink,
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
            // Credential Fixture (Keeps coordinator tests on the production ABS credential lookup path)
            // Only the remote API behavior varies between tests, so credentials stay valid and deterministic.
            store.save(
                baseUrl = "https://example.com/audiobookshelf",
                token = "token-1",
                credentialId = CREDENTIAL_ID
            )
        }
        return store
    }

    private fun absRoot() = LibraryRootEntity(
        id = ROOT_ID,
        sourceType = AudiobookSchema.LibrarySourceType.ABS,
        sourceUri = "https://example.com/audiobookshelf",
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
            // Event Collector Teardown (Cancels the dedicated collector scope after each assertion)
            // Coordinator emissions run on a background scope, so the observer must stop independently from runBlocking.
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
                    // Background Event Collection (Keeps SharedFlow consumption off the blocking test thread)
                    // This prevents the coordinator's emit call from depending on runBlocking while assertions poll.
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
            // Catalog Cancellation Fixture (Throws cancellation while the coordinator scope is still active)
            // This reproduces cancellation propagation being mistaken for a user-facing ABS sync failure.
            authorizeEntered.countDown()
            throw cancellation
        }
    }

    private class HangingAbsApi : BaseAbsApi() {
        val authorizeEntered = CountDownLatch(1)
        val cancellationObserved = CountDownLatch(1)

        override suspend fun authorize(baseUrl: String, token: String): AbsAuthorizeResponseDto {
            // Suspended Sync Fixture (Models an in-flight remote request during di teardown)
            // close() should stop this pending operation without publishing failure feedback to users.
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
            // Ordinary Failure Fixture (Keeps non-cancellation exceptions on the visible failure path)
            // The token-like text verifies coordinator redaction still protects feedback payloads.
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
        // Update AbsSyncTaskCoordinatorTest: Match FakeLibraryRootDao updateRootGrantState to use type-safe AudiobookSchema.LibraryRootStatus.
        override suspend fun updateRootGrantState(
            id: String,
            displayName: String,
            grantedAt: Long,
            status: AudiobookSchema.LibraryRootStatus
        ) = Unit
        // Update AbsSyncTaskCoordinatorTest: Match FakeLibraryRootDao updateRootScanState to use type-safe AudiobookSchema.LibraryRootStatus.
        override suspend fun updateRootScanState(id: String, lastScannedAt: Long, status: AudiobookSchema.LibraryRootStatus) = Unit
        // Update AbsSyncTaskCoordinatorTest: Match FakeLibraryRootDao updateRootStatus to use type-safe AudiobookSchema.LibraryRootStatus.
        override suspend fun updateRootStatus(id: String, status: AudiobookSchema.LibraryRootStatus) = Unit
        // Update AbsSyncTaskCoordinatorTest: Match FakeLibraryRootDao updateRootAvailability to use type-safe AudiobookSchema.AvailabilityStatus.
        override suspend fun updateRootAvailability(
            id: String,
            availabilityStatus: AudiobookSchema.AvailabilityStatus,
            checkedAt: Long,
            errorCode: String?
        ) = Unit
        override suspend fun deleteRoot(root: LibraryRootEntity) = Unit

        private fun isActiveAbsRoot(root: LibraryRootEntity): Boolean {
            // Active ABS Root Fixture Filter (Mirror the DAO predicate introduced for startup warmup)
            // Keeps this fake compatible with LibraryRootDao while returning only roots that production would include in the active ABS query.
            return root.status == AudiobookSchema.LibraryRootStatus.ACTIVE &&
                root.sourceType == AudiobookSchema.LibrarySourceType.ABS
        }
    }

    private class RecordingAppEventSink : AppEventSink {
        private val _events = MutableSharedFlow<AppShellEvent>()
        override val events: SharedFlow<AppShellEvent> = _events
        val feedbackFacts = CopyOnWriteArrayList<FeedbackFact>()

        override fun emitFeedback(fact: FeedbackFact): FeedbackDeliveryResult {
            // Feedback Recording Fixture (Captures coordinator toast requests without Android rendering)
            // The test asserts message facts directly so localization and shell collectors stay out of scope.
            feedbackFacts += fact
            return FeedbackDeliveryResult.Delivered(fact)
        }
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
            // Catalog Store Fixture (Persists only sync-state observations needed by the real synchronizer)
            // Coordinator tests fail before catalog materialization, but this keeps the fake safe for future paths.
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
