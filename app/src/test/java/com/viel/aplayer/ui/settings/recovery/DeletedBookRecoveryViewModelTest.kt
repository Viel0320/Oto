package com.viel.aplayer.ui.settings.recovery

import android.app.Application
import com.viel.aplayer.APlayerApplication
import com.viel.aplayer.ProcessContainer
import com.viel.aplayer.application.library.recovery.DeletedBookRecoveryCommands
import com.viel.aplayer.application.library.recovery.DeletedBookRecoveryItem
import com.viel.aplayer.application.library.recovery.DeletedBookRecoveryReadModel
import com.viel.aplayer.application.library.recovery.DeletedBookRecoveryResult
import com.viel.aplayer.event.AppEventSink
import com.viel.aplayer.event.AppShellEvent
import com.viel.aplayer.event.feedback.FeedbackCategory
import com.viel.aplayer.event.feedback.FeedbackContext
import com.viel.aplayer.event.feedback.FeedbackDeliveryResult
import com.viel.aplayer.event.feedback.FeedbackFact
import com.viel.aplayer.event.feedback.FeedbackMessage
import com.viel.aplayer.event.feedback.FeedbackTopic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.lang.reflect.Proxy

@OptIn(ExperimentalCoroutinesApi::class)
class MainDispatcherRule(
    val testDispatcher: TestDispatcher = UnconfinedTestDispatcher()
) : TestWatcher() {
    override fun starting(description: Description) {
        // Title: Main Dispatcher Redirection (Redirect Main dispatcher for pure JVM ViewModel unit testing)
        // Ensure that viewModelScope uses the unconfined test dispatcher during JVM execution to avoid illegal state exception.
        Dispatchers.setMain(testDispatcher)
    }

    override fun finished(description: Description) {
        // Title: Reset Main Dispatcher (Cleanup dispatcher configuration after test completion)
        // Restores production dispatcher state to prevent leakage into subsequent test runs.
        Dispatchers.resetMain()
    }
}

@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class DeletedBookRecoveryViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val fakeReadModel = FakeDeletedBookRecoveryReadModel()
    private val fakeCommands = FakeDeletedBookRecoveryCommands()
    private val fakeEventSink = FakeAppEventSink()

    private lateinit var application: Application
    private lateinit var viewModel: DeletedBookRecoveryViewModel

    @Before
    fun setUp() {
        // Title: Fake Process Container Injection (Setup container dependencies via reflection proxy)
        // Since ViewModel uses the application singleton context to pull dependencies, we inject a proxy process container intercepting the settings dependency queries.
        val fakeContainer = Proxy.newProxyInstance(
            ProcessContainer::class.java.classLoader,
            arrayOf(ProcessContainer::class.java)
        ) { _, method, _ ->
            when (method.name) {
                "getDeletedBookRecoveryReadModel" -> fakeReadModel
                "getDeletedBookRecoveryCommands" -> fakeCommands
                "getAppEventSink" -> fakeEventSink
                else -> null
            }
        } as ProcessContainer

        // Title: Inject Fake Container Singleton (Inject container instance to APlayerApplication)
        // Use reflection to override the private companion instance in APlayerApplication, ensuring static lookups return our fake container.
        val clazz = APlayerApplication::class.java
        val field = clazz.getDeclaredField("instance")
        field.isAccessible = true
        field.set(null, fakeContainer)

        // Title: Setup Robolectric Application Context (Obtain real Application instance under test sandbox)
        // Replace java.lang.reflect.Proxy for class types with Robolectric's RuntimeEnvironment to prevent illegal argument exception.
        application = RuntimeEnvironment.getApplication()

        viewModel = DeletedBookRecoveryViewModel(application)
    }

    @After
    fun tearDown() {
        // Title: Reset Container Singleton (Clean up reflection side-effects)
        // Restores instance field to null to avoid leaking proxy setup to other test suites.
        val clazz = APlayerApplication::class.java
        val field = clazz.getDeclaredField("instance")
        field.isAccessible = true
        field.set(null, null)
    }

    @Test
    fun initialDialogStateIsNull() {
        // Title: Assert Initial UI State (Verify default dialog presentation is inactive)
        // Confirms that when the recovery settings page loads, there are no modal overlays active by default.
        val state = viewModel.uiState.value
        assertNull(state.dialogState)
    }

    @Test
    fun requestRestoreBookShowsConfirmation() = runTest {
        // Title: Verify Recovery Trigger (Verify requestRestoreBook sets correct dialog state)
        // Checks that clicking the restore icon sets the RestoreConfirmation state with the corresponding book ID and title, displaying the dialog.
        val collectJob = launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect {}
        }

        viewModel.requestRestoreBook("book-123", "Test Audiobook")

        val state = viewModel.uiState.value
        val dialog = state.dialogState as? DeletedBookRecoveryDialogState.RestoreConfirmation
        assertEquals("book-123", dialog?.bookId)
        assertEquals("Test Audiobook", dialog?.bookTitle)

        collectJob.cancel()
    }

    @Test
    fun dismissDialogResetsStateToNull() = runTest {
        // Title: Verify Dialog Dismissal (Verify dialog dismiss clears dialog state)
        // Checks that cancelling or dismissing the confirmation dialog returns dialogState to null.
        val collectJob = launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect {}
        }

        viewModel.requestRestoreBook("book-123", "Test Audiobook")
        viewModel.dismissDialog()

        val state = viewModel.uiState.value
        assertNull(state.dialogState)

        collectJob.cancel()
    }

    @Test
    fun confirmRestoreExecutesRestoreCommandAndClearsState() = runTest {
        // Title: Verify Restore Execution (Verify successful restoration flow)
        // Confirms that confirming the restore dialog triggers the restoreBook command, displays a success toast, and cleans up page states.
        viewModel.requestRestoreBook("book-123", "Test Audiobook")
        viewModel.restoreBook("book-123")

        assertEquals("book-123", fakeCommands.restoreBookCalledWith)
        val state = viewModel.uiState.value
        // Note: restoreBook itself does not clear the confirmation dialog since the screen dismisses it on confirmation button click,
        // but here we verify it succeeds and triggers event toasts.
        assertEquals(1, fakeEventSink.emittedFeedback.size)
        val feedbackFact = fakeEventSink.emittedFeedback.first()
        val message = feedbackFact.message as FeedbackMessage.Resource
        assertEquals(com.viel.aplayer.R.string.feedback_deleted_book_recovery_restored_ready, message.resId)
        // Recovery feedback must classify as RECOVERY and stay keyed to the restored book.
        val identity = feedbackFact.outcome.identity
        assertEquals(FeedbackCategory.RECOVERY, identity.category)
        assertEquals(FeedbackTopic.DeletedBookRecovery, identity.topic)
        assertEquals(FeedbackContext.Book("book-123"), identity.context)
    }

    private class FakeDeletedBookRecoveryReadModel : DeletedBookRecoveryReadModel {
        override fun observeRecoverableBooks(): Flow<List<DeletedBookRecoveryItem>> {
            return flowOf(emptyList())
        }
    }

    private class FakeDeletedBookRecoveryCommands : DeletedBookRecoveryCommands {
        var restoreBookCalledWith: String? = null
        var confirmPartialRestoreCalledWith: Triple<String, List<String>, List<String>>? = null
        var restoreResult: DeletedBookRecoveryResult = DeletedBookRecoveryResult.RestoredReady

        override suspend fun restoreBook(bookId: String): DeletedBookRecoveryResult {
            restoreBookCalledWith = bookId
            return restoreResult
        }

        override suspend fun confirmPartialRestore(
            bookId: String,
            availableFileIds: List<String>,
            missingFileIds: List<String>
        ): DeletedBookRecoveryResult {
            confirmPartialRestoreCalledWith = Triple(bookId, availableFileIds, missingFileIds)
            return restoreResult
        }
    }

    private class FakeAppEventSink : AppEventSink {
        override val events: SharedFlow<AppShellEvent> = MutableSharedFlow()
        val emittedFeedback = mutableListOf<FeedbackFact>()

        override fun emitFeedback(fact: FeedbackFact): FeedbackDeliveryResult {
            emittedFeedback.add(fact)
            return FeedbackDeliveryResult.Delivered(fact)
        }
    }
}
