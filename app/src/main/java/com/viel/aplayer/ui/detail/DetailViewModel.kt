package com.viel.aplayer.ui.detail

import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.viel.aplayer.application.download.BookCacheStatus
import com.viel.aplayer.application.library.detail.DetailBookItem
import com.viel.aplayer.application.library.detail.DetailSnapshot
import com.viel.aplayer.di.dependencies.DetailScreenDependencies
import com.viel.aplayer.event.feedback.DownloadCacheFeedbackFacts
import com.viel.aplayer.event.feedback.FeedbackFact
import com.viel.aplayer.logger.AbsLogSanitizer
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

/**
 * Detail View Model: Coordinates UI state, availability checking, and asset details for the book details panel.
 *
 * Manages active book selections, checks file accessibility in VFS, extracts dominant cover colors,
 * and maintains reactive UI state updates.
 * Separated from `LibraryViewModel` to respect single-responsibility principles and establish clean domains.
 */
class DetailViewModel(
    private val detailDependencies: DetailScreenDependencies
) : ViewModel() {
    private val detailBookReadModel = detailDependencies.detailBookReadModel
    private val detailBookCommands = detailDependencies.detailBookCommands
    private val downloadStatusReadModel = detailDependencies.downloadStatusReadModel
    private val downloadController = detailDependencies.downloadController
    private val appEventSink = detailDependencies.appEventSink

    private var bookObserveJob: kotlinx.coroutines.Job? = null
    private var cacheObserveJob: kotlinx.coroutines.Job? = null


    private val _uiState = MutableStateFlow(DetailUiState())
    val uiState: StateFlow<DetailUiState> = _uiState.asStateFlow()

    /** Elapse clock offset; null indicates inactive protection. */
    private val _playbackStartedAt = MutableStateFlow<Long?>(null)

    /**
     * Hold display progress during initial playback kick-off.
     *
     * Initiated when playing a book with 0% progress.
     * Starts a 3-second protection window where `displayProgressPercent` is forced to 0.
     * This avoids flickering transitions between "Start Listening" and "Continue at X%" buttons on slow startup streams.
     */
    fun onPlayPressed() {
        if (_uiState.value.progressPercent == 0) {
            _playbackStartedAt.value = SystemClock.elapsedRealtime()
            _uiState.update { it.copy(displayProgressPercent = 0) }
            viewModelScope.launch {
                delay(UNPLAYED_PROTECTION_WINDOW_MS.milliseconds)
                _playbackStartedAt.value = null
                _uiState.update { it.copy(displayProgressPercent = it.progressPercent) }
            }
        }
    }

    /**
     * Select Audiobook: Configures the details screen to focus on the selected detail item.
     *
     * Populates primary metadata fields immediately, then triggers asynchronous tasks to check
     * physical track availability via VFS and retrieve dominant cover colors.
     *
     * @param book The target audiobook detail item. Pass `null` to close the details pane.
     * @param entrySource The UI surface that triggered this detail selection, used only for motion channel routing.
     */
    fun selectBook(
        book: DetailBookItem?,
        entrySource: DetailEntrySource = DetailEntrySource.None
    ) {
        bookObserveJob?.cancel()
        bookObserveJob = null
        cacheObserveJob?.cancel()
        cacheObserveJob = null
        val current = _uiState.value

        if (book != null) {
            val snapshot = DetailSnapshot(item = book)
            _playbackStartedAt.value = null
            _uiState.value = current.copy(
                book = snapshot,
                entrySource = entrySource,
                isVisible = true,
                isAvailable = true,
                progressPercent = snapshot.progressPercent,
                displayProgressPercent = snapshot.progressPercent,
                fullSourcePath = "",
                bookCacheStatus = BookCacheStatus.none()
            )

            viewModelScope.launch {
                val selectedBookId = snapshot.bookId
                val finalPath = detailBookReadModel.resolveSourceLocation(snapshot)

                _uiState.update { state ->
                    val currentSnapshot = state.book ?: return@update state
                    if (currentSnapshot.bookId == selectedBookId) {
                        state.copy(
                            book = currentSnapshot.copy(sourceLocation = finalPath),
                            fullSourcePath = finalPath
                        )
                    } else {
                        state
                    }
                }
            }

            viewModelScope.launch {
                val checkedBookId = snapshot.bookId
                val isAvailable = detailBookCommands.refreshAvailability(checkedBookId)
                _uiState.update { state ->
                    val currentSnapshot = state.book ?: return@update state
                    if (currentSnapshot.bookId == checkedBookId) {
                        state.copy(
                            book = currentSnapshot.copy(isAvailable = isAvailable),
                            isAvailable = isAvailable
                        )
                    } else {
                        state
                    }
                }
            }

            bookObserveJob = viewModelScope.launch {
                detailBookReadModel.observeLiveSnapshot(snapshot).collect { updatedSnapshot ->
                    _uiState.update { state ->
                        val currentSnapshot = state.book ?: return@update state
                        if (currentSnapshot.bookId == updatedSnapshot.bookId) {
                            state.copy(
                                book = updatedSnapshot.copy(
                                    isAvailable = currentSnapshot.isAvailable,
                                    sourceLocation = currentSnapshot.sourceLocation
                                )
                            )
                        } else {
                            state
                        }
                    }
                }
            }

            cacheObserveJob = viewModelScope.launch {
                val selectedBookId = snapshot.bookId
                downloadStatusReadModel.observeBookCacheStatus(selectedBookId).collect { cacheStatus ->
                    _uiState.update { state ->
                        val currentSnapshot = state.book ?: return@update state
                        if (currentSnapshot.bookId == selectedBookId) {
                            state.copy(bookCacheStatus = cacheStatus)
                        } else {
                            state
                        }
                    }
                }
            }
        } else {
            _playbackStartedAt.value = null
            _uiState.update { state ->
                state.copy(
                    isVisible = false,
                    entrySource = DetailEntrySource.None,
                    fullSourcePath = "",
                    bookCacheStatus = BookCacheStatus.none()
                )
            }
        }

    }

    /**
     * Adjust Pane Visibility: Sets the visibility status of the details view.
     *
     * @param visible Pass `false` to close the details pane (leaves selection intact for exit animations).
     */
    fun setVisible(visible: Boolean) {
        _uiState.update { it.copy(isVisible = visible) }
    }


    /**
     * Dismiss Selection on Delete: Closes the details panel immediately if the currently viewed book is deleted.
     *
     * @param bookId The unique identifier of the deleted audiobook.
     */
    fun dismissIfShowing(bookId: String) {
        if (_uiState.value.book?.bookId == bookId) {
            _uiState.update {
                it.copy(
                    isVisible = false,
                    book = null,
                    bookCacheStatus = BookCacheStatus.none(),
                    entrySource = DetailEntrySource.None
                )
            }
        }
    }

    fun downloadBook(bookId: String) {
        runDownloadCommand(
            bookId = bookId,
            successFact = DownloadCacheFeedbackFacts.queued(bookId),
            action = { downloadController.downloadBook(bookId) }
        )
    }

    fun pauseDownload(bookId: String) {
        runDownloadCommand(
            bookId = bookId,
            successFact = DownloadCacheFeedbackFacts.paused(bookId),
            action = { downloadController.pauseDownload(bookId) }
        )
    }

    fun resumeDownload(bookId: String) {
        runDownloadCommand(
            bookId = bookId,
            successFact = DownloadCacheFeedbackFacts.resumed(bookId),
            action = { downloadController.resumeDownload(bookId) }
        )
    }

    fun deleteDownload(bookId: String) {
        runDownloadCommand(
            bookId = bookId,
            successFact = DownloadCacheFeedbackFacts.deleted(bookId),
            action = { downloadController.deleteDownload(bookId) }
        )
    }

    fun onDownloadNotificationPermissionDenied() {
        appEventSink.emitFeedback(DownloadCacheFeedbackFacts.notificationPermissionDenied())
    }

    private fun runDownloadCommand(
        bookId: String,
        successFact: FeedbackFact,
        action: suspend () -> Unit
    ) {
        viewModelScope.launch {
            runCatching {
                action()
            }.onSuccess {
                appEventSink.emitFeedback(successFact)
            }.onFailure { error ->
                appEventSink.emitFeedback(
                    DownloadCacheFeedbackFacts.commandFailed(bookId, AbsLogSanitizer.compact(error.message))
                )
            }
        }
    }

    /**
     * Synchronize Playback Progress: Receives progress updates from the service sync channel.
     *
     * Implementation details:
     * 1. Evaluates whether the 3-second unplayed protection window remains active.
     * 2. If protected, maps the underlying `progressPercent` but forces `displayProgressPercent` to 0 to prevent UI flickering.
     * 3. If unprotected, updates both parameters concurrently to keep progress states aligned.
     */
    fun updatePlaybackProgress(bookId: String, progressPercent: Int) {
        val currentState = _uiState.value
        if (currentState.book?.bookId == bookId) {
            _uiState.update { state ->
                val startedAt = _playbackStartedAt.value
                val isProtected = startedAt != null && (SystemClock.elapsedRealtime() - startedAt) < UNPLAYED_PROTECTION_WINDOW_MS
                state.copy(
                    progressPercent = progressPercent,
                    displayProgressPercent = if (isProtected) 0 else progressPercent
                )
            }
        }
    }

    companion object {
        private const val UNPLAYED_PROTECTION_WINDOW_MS = 3_000L
    }
}
