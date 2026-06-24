package com.viel.oto.ui.settings.recovery

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.viel.oto.R
import com.viel.oto.application.library.recovery.DeletedBookRecoveryCommands
import com.viel.oto.application.library.recovery.DeletedBookRecoveryItem
import com.viel.oto.application.library.recovery.DeletedBookRecoveryReadModel
import com.viel.oto.application.library.recovery.DeletedBookRecoveryResult
import com.viel.oto.event.AppEventSink
import com.viel.oto.event.feedback.RecoveryFeedbackFacts
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Presentation coordinator for soft-deleted book restoration.
 * Owns row-level loading, confirmation dialogs, and toast routing while delegating business rules to the recovery commands.
 */
class DeletedBookRecoveryViewModel(
    private val readModel: DeletedBookRecoveryReadModel,
    private val commands: DeletedBookRecoveryCommands,
    private val appEventSink: AppEventSink
) : ViewModel() {

    private val restoringBookIds = MutableStateFlow<Set<String>>(emptySet())
    private val dialogState = MutableStateFlow<DeletedBookRecoveryDialogState?>(null)

    val uiState: StateFlow<DeletedBookRecoveryUiState> =
        combine(
            readModel.observeRecoverableBooks(),
            restoringBookIds,
            dialogState
        ) { items, loadingIds, dialog ->
            DeletedBookRecoveryUiState(
                items = items,
                restoringBookIds = loadingIds,
                dialogState = dialog
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = DeletedBookRecoveryUiState()
        )

    fun requestRestoreBook(bookId: String, bookTitle: String) {
        dialogState.value = DeletedBookRecoveryDialogState.RestoreConfirmation(bookId, bookTitle)
    }

    fun restoreBook(bookId: String) {
        if (restoringBookIds.value.contains(bookId)) return
        viewModelScope.launch {
            markRestoring(bookId, restoring = true)
            try {
                handleRecoveryResult(bookId, commands.restoreBook(bookId))
            } catch (error: Throwable) {
                dialogState.value = DeletedBookRecoveryDialogState.Failure(
                    messageRes = R.string.deleted_book_recovery_failure_unexpected,
                    messageArg = error.message.orEmpty()
                )
            } finally {
                markRestoring(bookId, restoring = false)
            }
        }
    }

    fun confirmPartialRestore() {
        val pending = dialogState.value as? DeletedBookRecoveryDialogState.PartialConfirmation ?: return
        dialogState.value = null
        if (restoringBookIds.value.contains(pending.bookId)) return
        viewModelScope.launch {
            markRestoring(pending.bookId, restoring = true)
            try {
                handleRecoveryResult(
                    bookId = pending.bookId,
                    result = commands.confirmPartialRestore(
                        bookId = pending.bookId,
                        availableFileIds = pending.availableFileIds,
                        missingFileIds = pending.missingFileIds
                    )
                )
            } catch (error: Throwable) {
                dialogState.value = DeletedBookRecoveryDialogState.Failure(
                    messageRes = R.string.deleted_book_recovery_failure_unexpected,
                    messageArg = error.message.orEmpty()
                )
            } finally {
                markRestoring(pending.bookId, restoring = false)
            }
        }
    }

    fun dismissDialog() {
        dialogState.value = null
    }

    /**
     * Routes typed use-case outcomes to toast, failure dialog, or partial confirmation.
     * Keeps result-to-copy mapping in presentation while the use case remains language-neutral.
     */
    private fun handleRecoveryResult(bookId: String, result: DeletedBookRecoveryResult) {
        when (result) {
            DeletedBookRecoveryResult.RestoredReady -> {
                appEventSink.emitFeedback(RecoveryFeedbackFacts.deletedBookRecoveryRestoredReady(bookId))
            }
            DeletedBookRecoveryResult.RestoredPartial -> {
                appEventSink.emitFeedback(RecoveryFeedbackFacts.deletedBookRecoveryRestoredPartial(bookId))
            }
            is DeletedBookRecoveryResult.PartialFilesUnavailable -> {
                dialogState.value = DeletedBookRecoveryDialogState.PartialConfirmation(
                    bookId = bookId,
                    availableFileIds = result.availableFileIds,
                    missingFileIds = result.missingFileIds
                )
            }
            else -> {
                dialogState.value = result.toFailureDialogState()
            }
        }
    }

    private fun markRestoring(bookId: String, restoring: Boolean) {
        restoringBookIds.value = if (restoring) {
            restoringBookIds.value + bookId
        } else {
            restoringBookIds.value - bookId
        }
    }
}

/**
 * Aggregates list, loading, and dialog state for Compose.
 * Keeps the screen stateless and makes row loading independent from Flow list refreshes.
 */
data class DeletedBookRecoveryUiState(
    val items: List<DeletedBookRecoveryItem> = emptyList(),
    val restoringBookIds: Set<String> = emptySet(),
    val dialogState: DeletedBookRecoveryDialogState? = null
)

/**
 * Typed modal states for restore feedback.
 * Separates failure acknowledgement from partial restore confirmation so cancellation never writes file statuses.
 */
sealed interface DeletedBookRecoveryDialogState {
    data class Failure(
        @field:StringRes val messageRes: Int,
        val messageArg: String? = null
    ) : DeletedBookRecoveryDialogState

    data class PartialConfirmation(
        val bookId: String,
        val availableFileIds: List<String>,
        val missingFileIds: List<String>
    ) : DeletedBookRecoveryDialogState

    data class RestoreConfirmation(
        val bookId: String,
        val bookTitle: String
    ) : DeletedBookRecoveryDialogState
}

/**
 * Maps use-case failures to resource-backed user-facing explanations.
 * Keeps failure reasons exhaustive at the presentation boundary while retaining provider detail arguments where useful.
 */
private fun DeletedBookRecoveryResult.toFailureDialogState(): DeletedBookRecoveryDialogState.Failure =
    when (this) {
        DeletedBookRecoveryResult.MissingBook ->
            DeletedBookRecoveryDialogState.Failure(R.string.deleted_book_recovery_failure_missing_book)
        DeletedBookRecoveryResult.MissingRoot ->
            DeletedBookRecoveryDialogState.Failure(R.string.deleted_book_recovery_failure_missing_root)
        is DeletedBookRecoveryResult.RootUnavailable ->
            DeletedBookRecoveryDialogState.Failure(R.string.deleted_book_recovery_failure_root_unavailable, reason)
        DeletedBookRecoveryResult.AbsRemoteDeleted ->
            DeletedBookRecoveryDialogState.Failure(R.string.deleted_book_recovery_failure_abs_remote_deleted)
        DeletedBookRecoveryResult.NoAudioFiles ->
            DeletedBookRecoveryDialogState.Failure(R.string.deleted_book_recovery_failure_no_audio_files)
        is DeletedBookRecoveryResult.AllFilesUnavailable ->
            DeletedBookRecoveryDialogState.Failure(R.string.deleted_book_recovery_failure_all_files_unavailable, reason)
        DeletedBookRecoveryResult.RestoredReady,
        DeletedBookRecoveryResult.RestoredPartial,
        is DeletedBookRecoveryResult.PartialFilesUnavailable ->
            DeletedBookRecoveryDialogState.Failure(R.string.deleted_book_recovery_failure_missing_book)
    }
