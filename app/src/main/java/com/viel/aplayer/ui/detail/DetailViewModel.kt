package com.viel.aplayer.ui.detail

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.viel.aplayer.APlayerApplication
import com.viel.aplayer.application.library.detail.DetailBookItem
import com.viel.aplayer.application.library.detail.DetailSnapshot
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
class DetailViewModel(application: Application) : AndroidViewModel(application) {
    // Detail Scene Dependency View (Resolve only detail-specific read and command interfaces)
    // This keeps source lookup, availability refresh, and live metadata observation behind the detail scene boundary.
    private val detailDependencies = APlayerApplication.getDetailScreenDependencies(application)
    private val detailBookReadModel = detailDependencies.detailBookReadModel
    private val detailBookCommands = detailDependencies.detailBookCommands

    // Relational Observer Job (Tracks database updates for the selected book)
    private var bookObserveJob: kotlinx.coroutines.Job? = null


    private val _uiState = MutableStateFlow(DetailUiState())
    val uiState: StateFlow<DetailUiState> = _uiState.asStateFlow()

    // =====================================================================
    // Unplayed Protection Configuration (VM-level state persistence across configurations)
    // Previously, `isUnplayedProtectionActive` and the 3000ms delay resided inside `DetailScreen`.
    // That allowed configuration shifts (e.g., rotation, dark theme toggles) to erase the protection state.
    // Downshifting this to the ViewModel ensures that `playbackStartedAt` is retained across config changes,
    // exposing a debounced `displayProgressPercent` property inside `uiState` for UI rendering.
    // =====================================================================

    /** Playback Click Timestamp (Elapse clock offset; null indicates inactive protection) */
    private val _playbackStartedAt = MutableStateFlow<Long?>(null)

    /**
     * Start Playback Protection (Hold display progress during initial playback kick-off)
     *
     * Initiated when playing a book with 0% progress.
     * Starts a 3-second protection window where `displayProgressPercent` is forced to 0.
     * This avoids flickering transitions between "Start Listening" and "Continue at X%" buttons on slow startup streams.
     */
    fun onPlayPressed() {
        if (_uiState.value.progressPercent == 0) {
            // Lock Display progress (Force display progress to 0 during startup protection)
            _playbackStartedAt.value = SystemClock.elapsedRealtime()
            _uiState.update { it.copy(displayProgressPercent = 0) }
            viewModelScope.launch {
                // Unified Delay Timing (Align VM delay with the duration gate)
                // References the single constant `UNPLAYED_PROTECTION_WINDOW_MS` to prevent mismatch issues.
                delay(UNPLAYED_PROTECTION_WINDOW_MS.milliseconds)
                _playbackStartedAt.value = null
                // Terminate Protection (Restore display progress to match actual progress values)
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
        // Resource Cleanup (Cancel active database flow subscription to avoid leakage and crosstalk)
        bookObserveJob?.cancel()
        bookObserveJob = null
        val current = _uiState.value

        // Display Details (Render details pane for non-null target selection)
        if (book != null) {
            // Detail Selection Snapshot (Lift the scene item into state with detail-owned async fields)
            // Source location and availability are resolved after selection, so the initial snapshot starts with optimistic defaults.
            val snapshot = DetailSnapshot(item = book)
            // Reset Protection State (Wipe timestamps to prevent crosstalk during selection changes)
            _playbackStartedAt.value = null
            _uiState.value = current.copy(
                book = snapshot,
                /*
                 * Entry Source Capture (Shared-element route identity)
                 *
                 * Stores the opening surface together with the selected book so Home recent and
                 * Home list artwork never subscribe to the same detail target at the same time.
                 */
                entrySource = entrySource,
                isVisible = true,
                // Optimistic Accessibility (Assume available initially to prevent blocking play during VFS evaluation)
                isAvailable = true,
                progressPercent = snapshot.progressPercent,
                // Synchronize Display progress (Align starting display values with selection progress)
                displayProgressPercent = snapshot.progressPercent,
                // Clear Previous Path (Flush physical path reference to prevent transient UI flashing)
                fullSourcePath = ""
            )

            // Resolve User-Facing Source Location (Build a friendly source indicator from VFS metadata)
            // The detail page should not expose raw SAF tree fragments, remote URLs, or ABS playback API paths; it displays the registered library name plus the selected file's relative path instead.
            viewModelScope.launch {
                val selectedBookId = snapshot.bookId
                val finalPath = detailBookReadModel.resolveSourceLocation(snapshot)

                _uiState.update { state ->
                    val currentSnapshot = state.book ?: return@update state
                    // Detail Source Race Guard (Ignore stale path work after the selected book changes)
                    // Source formatting runs asynchronously, so the book id is checked before applying the display label to prevent cross-book path flashes.
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
                // Request Tracking (Tag checker action with selected bookId to avoid crosstalk on screen shifts)
                val checkedBookId = snapshot.bookId
                // Detail Availability Status Refresh (Verifies VFS readability and persists the latest book/file status)
                // The detail command name makes the state-refreshing side effect explicit to the detail UI flow.
                val isAvailable = detailBookCommands.refreshAvailability(checkedBookId)
                _uiState.update { state ->
                    val currentSnapshot = state.book ?: return@update state
                    // Atomic State Resolution (Apply availability results only if selected target is unchanged)
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

            // Live Metadata Binding (Observe database flow for live details updating)
            // Binds database streams to UI values. Allows manual edits (e.g. from EditBookActivity) to show up instantly.
            bookObserveJob = viewModelScope.launch {
                detailBookReadModel.observeLiveSnapshot(snapshot).collect { updatedSnapshot ->
                    _uiState.update { state ->
                        val currentSnapshot = state.book ?: return@update state
                        if (currentSnapshot.bookId == updatedSnapshot.bookId) {
                            // Live Snapshot Merge (Keep detail-local source and availability fields during metadata refreshes)
                            // The module emits updated book rows, while the ViewModel preserves async source and availability results already applied to the selected snapshot.
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
        } else {
            // Reset Layout variables (Clear screen attributes and disable visibility state)
            _playbackStartedAt.value = null
            _uiState.update { state ->
                state.copy(
                    isVisible = false,
                    /*
                     * Entry Source Reset (Closed detail source cleanup)
                     *
                     * Clears motion origin when callers close by selecting null, preventing the
                     * next non-Home detail opening from inheriting a stale Home source.
                     */
                    entrySource = DetailEntrySource.None,
                    fullSourcePath = ""
                )
            }
        }

        // Deprecated: Color extraction was completely removed from ViewModel since it is handled by the UI layer
    }

    /**
     * Adjust Pane Visibility: Sets the visibility status of the details view.
     *
     * @param visible Pass `false` to close the details pane (leaves selection intact for exit animations).
     */
    fun setVisible(visible: Boolean) {
        _uiState.update { it.copy(isVisible = visible) }
    }

    // Clear Detail State (Reset selection and cancel database flow subscription on overlay dispose)
    // Resets the selected book metadata, cancels active database flow observers, and flushes progress parameters when closed.
    fun clearDetails() {
        // Disabled Auto-Clear: Commented out the active state flushing logic to prevent clearing the selected book state when the detail view is closed.
        /*
        bookObserveJob?.cancel()
        bookObserveJob = null
        _playbackStartedAt.value = null
        _uiState.update { state ->
            state.copy(
                book = null,
                entrySource = DetailEntrySource.None,
                isAvailable = true,
                progressPercent = 0,
                displayProgressPercent = 0,
                backgroundColorArgb = ImageProcessor.DEFAULT_BACKGROUND_ARGB,
                fullSourcePath = ""
            )
        }
        */
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
                    /*
                     * Entry Source Delete Cleanup (Deleted selection isolation)
                     *
                     * Clears the motion origin with the deleted book so hidden Home sources do
                     * not keep targeting a detail item that no longer exists.
                     */
                    entrySource = DetailEntrySource.None
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
        // Protection Time Constant (Central source of truth for debouncing initial playback transitions)
        // Keeps VM delay windows and service sync controls synchronized to prevent timing drifts.
        private const val UNPLAYED_PROTECTION_WINDOW_MS = 3_000L
    }
}
