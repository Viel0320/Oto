package com.viel.oto.ui.settings.recovery

import android.app.Application
import com.viel.oto.application.library.recovery.DeletedBookRecoveryCommands
import com.viel.oto.application.library.recovery.DeletedBookRecoveryItem
import com.viel.oto.application.library.recovery.DeletedBookRecoveryReadModel
import com.viel.oto.application.library.recovery.DeletedBookRecoveryResult
import com.viel.oto.event.AppEventSink
import com.viel.oto.event.AppShellEvent
import com.viel.oto.event.feedback.FeedbackCategory
import com.viel.oto.event.feedback.FeedbackContext
import com.viel.oto.event.feedback.FeedbackDeliveryResult
import com.viel.oto.event.feedback.FeedbackFact
import com.viel.oto.event.feedback.FeedbackMessage
import com.viel.oto.event.feedback.FeedbackTopic
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

@OptIn(ExperimentalCoroutinesApi::class)
class MainDispatcherRule(
    val testDispatcher: TestDispatcher = UnconfinedTestDispatcher()
) : TestWatcher() {
    override fun starting(description: Description) {
        Dispatchers.setMain(testDispatcher)
    }

    override fun finished(description: Description) {
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
        application = RuntimeEnvironment.getApplication()
        viewModel = DeletedBookRecoveryViewModel(fakeReadModel, fakeCommands, fakeEventSink)
    }

    @Test
    fun initialDialogStateIsNull() {
        val state = viewModel.uiState.value
        assertNull(state.dialogState)
    }

    @Test
    fun requestRestoreBookShowsConfirmation() = runTest {
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
        viewModel.requestRestoreBook("book-123", "Test Audiobook")
        viewModel.restoreBook("book-123")

        assertEquals("book-123", fakeCommands.restoreBookCalledWith)
        val state = viewModel.uiState.value
        assertEquals(1, fakeEventSink.emittedFeedback.size)
        val feedbackFact = fakeEventSink.emittedFeedback.first()
        val message = feedbackFact.message as FeedbackMessage.Resource
        assertEquals(com.viel.oto.R.string.feedback_deleted_book_recovery_restored_ready, message.resId)
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
