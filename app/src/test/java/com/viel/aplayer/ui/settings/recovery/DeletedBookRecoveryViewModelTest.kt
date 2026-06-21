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

        val clazz = APlayerApplication::class.java
        val field = clazz.getDeclaredField("instance")
        field.isAccessible = true
        field.set(null, fakeContainer)

        application = RuntimeEnvironment.getApplication()

        viewModel = DeletedBookRecoveryViewModel(application)
    }

    @After
    fun tearDown() {
        val clazz = APlayerApplication::class.java
        val field = clazz.getDeclaredField("instance")
        field.isAccessible = true
        field.set(null, null)
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
        assertEquals(com.viel.aplayer.R.string.feedback_deleted_book_recovery_restored_ready, message.resId)
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
