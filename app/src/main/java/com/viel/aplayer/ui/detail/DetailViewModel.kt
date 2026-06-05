package com.viel.aplayer.ui.detail

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.viel.aplayer.APlayerApplication
import com.viel.aplayer.data.entity.BookWithProgress
import com.viel.aplayer.media.parser.ImageProcessor
import kotlinx.coroutines.Dispatchers
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
    // Business Facade (Decoupled high-level aggregate gateway)
    private val libraryFacade = (application as APlayerApplication).container.libraryFacade

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
     * Select Audiobook: Configures the details screen to focus on the selected book entity.
     *
     * Populates primary metadata fields immediately, then triggers asynchronous tasks to check
     * physical track availability via VFS and retrieve dominant cover colors.
     *
     * @param book The target audiobook with progress. Pass `null` to close the details pane.
     * @param entrySource The UI surface that triggered this detail selection, used only for motion channel routing.
     */
    fun selectBook(
        book: BookWithProgress?,
        entrySource: DetailEntrySource = DetailEntrySource.None
    ) {
        // Resource Cleanup (Cancel active database flow subscription to avoid leakage and crosstalk)
        bookObserveJob?.cancel()
        bookObserveJob = null
        val current = _uiState.value

        // Display Details (Render details pane for non-null target selection)
        if (book != null) {
            // Reset Protection State (Wipe timestamps to prevent crosstalk during selection changes)
            _playbackStartedAt.value = null
            _uiState.value = current.copy(
                book = book,
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
                progressPercent = book.progressPercent,
                // Synchronize Display progress (Align starting display values with selection progress)
                displayProgressPercent = book.progressPercent,
                // Clear Previous Path (Flush physical path reference to prevent transient UI flashing)
                fullSourcePath = ""
            )

            // Resolve Physical Path (Map and format VFS identifiers to a display path)
            // Launches an asynchronous scope to fetch book tracks, decode SAF references,
            // strip virtual directory prefixes, and map the clean path to UI display variables.
            viewModelScope.launch {
                // Retrieve Track list (Query complete book tracks via aggregate gateway)
                val files = libraryFacade.getAllFilesForBookSync(book.book.id)
                val fileName = when (book.book.sourceType) {
                    com.viel.aplayer.data.db.AudiobookSchema.SourceType.SINGLE_AUDIO -> {
                        // Single File Source (Default to first track display name)
                        files.firstOrNull()?.displayName.orEmpty()
                    }
                    com.viel.aplayer.data.db.AudiobookSchema.SourceType.CUE,
                    com.viel.aplayer.data.db.AudiobookSchema.SourceType.M3U8 -> {
                        // Manifest Book source (Prioritize the source manifest track, falling back to track index 0)
                        files.firstOrNull { it.fileRole == com.viel.aplayer.data.db.AudiobookSchema.FileRole.SOURCE_MANIFEST }?.displayName
                            ?: files.firstOrNull()?.displayName.orEmpty()
                    }
                    com.viel.aplayer.data.db.AudiobookSchema.SourceType.GENERATED_M3U8 -> {
                        // Aggregated Book source (Sort tracks and default to the first sorted track name)
                        files.minByOrNull { it.index }?.displayName.orEmpty()
                    }
                    else -> ""
                }

                // URL Decoding (Resolve percent encoding symbols inside SAF roots)
                val root = book.book.sourceRoot
                val decodedRoot = android.net.Uri.decode(root)

                // Prefix Stripping (Isolate local physical directories from virtual authority prefixes)
                // In SAF scopes, physical URLs can carry duplicate authority fragments (e.g., "primary:Audiobooks/document/primary:Audiobooks").
                // Locating the last instance of "primary:" cleans up virtual prefixes, leaving only the physical subpath.
                val primaryKey = "primary:"
                val startIndex = decodedRoot.lastIndexOf(primaryKey, ignoreCase = true)
                val cleanRoot = if (startIndex != -1) {
                    decodedRoot.substring(startIndex + primaryKey.length)
                } else {
                    decodedRoot
                }

                // Path Assembly (Concatenate cleaned root directory and resolved filename)
                val finalPath = if (cleanRoot.endsWith("/")) {
                    "$cleanRoot$fileName"
                } else if (cleanRoot.isNotEmpty() && fileName.isNotEmpty()) {
                    "$cleanRoot/$fileName"
                } else {
                    "$cleanRoot$fileName"
                }

                _uiState.update { state ->
                    state.copy(fullSourcePath = finalPath)
                }
            }

            viewModelScope.launch {
                // Request Tracking (Tag checker action with selected bookId to avoid crosstalk on screen shifts)
                val checkedBookId = book.book.id
                // Asynchronous Availability check (Verify VFS readability status of the selected book)
                val isAvailable = libraryFacade.checkDetailAvailability(checkedBookId)
                _uiState.update { state ->
                    // Atomic State Resolution (Apply availability results only if selected target is unchanged)
                    if (state.book?.book?.id == checkedBookId) {
                        state.copy(isAvailable = isAvailable)
                    } else {
                        state
                    }
                }
            }

            // Live Metadata Binding (Observe database flow for live details updating)
            // Binds database streams to UI values. Allows manual edits (e.g. from EditBookActivity) to show up instantly.
            bookObserveJob = viewModelScope.launch {
                libraryFacade.observeBookById(book.book.id).collect { updatedBook ->
                    if (updatedBook != null) {
                        _uiState.update { state ->
                            state.book?.let { currentBwp ->
                                state.copy(
                                    book = currentBwp.copy(book = updatedBook)
                                )
                            } ?: state
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

        // Dominant Color Optimization (Fetch cached values or run bitmap extractor asynchronously)
        if (book != null && (book.book.coverPath != current.book?.book?.coverPath
                    || current.backgroundColorArgb == ImageProcessor.DEFAULT_BACKGROUND_ARGB)) {
            viewModelScope.launch(Dispatchers.Default) {
                val cachedColor = libraryFacade.getBookById(book.book.id)?.backgroundColorArgb
                val backgroundColor = cachedColor ?: ImageProcessor.getDominantColor(book.book.coverPath)
                _uiState.value = _uiState.value.copy(backgroundColorArgb = backgroundColor)

                // Cache Dominant Color (Save computed colors back to database for future lookups)
                if (cachedColor == null) {
                    libraryFacade.updateBackgroundColor(book.book.id, backgroundColor)
                }
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

    // Clear Detail State (Reset selection and cancel database flow subscription on overlay dispose)
    // Resets the selected book metadata, cancels active database flow observers, and flushes progress parameters when closed.
    fun clearDetails() {
        bookObserveJob?.cancel()
        bookObserveJob = null
        _playbackStartedAt.value = null
        _uiState.update { state ->
            state.copy(
                book = null,
                /*
                 * Entry Source Clear (Overlay disposal cleanup)
                 *
                 * Drops the source marker after exit animation disposal so a future detail entry
                 * must declare its own source instead of reusing a completed transition.
                 */
                entrySource = DetailEntrySource.None,
                isAvailable = true,
                progressPercent = 0,
                displayProgressPercent = 0,
                backgroundColorArgb = ImageProcessor.DEFAULT_BACKGROUND_ARGB,
                fullSourcePath = ""
            )
        }
    }


    /**
     * Dismiss Selection on Delete: Closes the details panel immediately if the currently viewed book is deleted.
     *
     * @param bookId The unique identifier of the deleted audiobook.
     */
    fun dismissIfShowing(bookId: String) {
        if (_uiState.value.book?.book?.id == bookId) {
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
        if (currentState.book?.book?.id == bookId) {
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
