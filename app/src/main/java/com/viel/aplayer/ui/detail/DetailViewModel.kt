package com.viel.aplayer.ui.detail

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.viel.aplayer.APlayerApplication
import com.viel.aplayer.data.db.AudiobookSchema
import com.viel.aplayer.data.entity.BookFileEntity
import com.viel.aplayer.data.entity.BookWithProgress
import com.viel.aplayer.data.entity.LibraryRootEntity
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

            // Resolve User-Facing Source Location (Build a friendly source indicator from VFS metadata)
            // The detail page should not expose raw SAF tree fragments, remote URLs, or ABS playback API paths; it displays the registered library name plus the selected file's relative path instead.
            viewModelScope.launch {
                val selectedBookId = book.book.id
                val files = libraryFacade.getAllFilesForBookSync(book.book.id)
                // Library Root Snapshot (Resolve the selected book's registered source root)
                // Reading roots through the facade keeps DetailViewModel on the domain gateway boundary while giving the display formatter a user-owned source label.
                val root = libraryFacade.getCachedLibraryRoots().firstOrNull { it.id == book.book.rootId }
                    ?: libraryFacade.getAllRootsOnce().firstOrNull { it.id == book.book.rootId }
                val finalPath = buildFriendlySourceLocation(book, files, root)

                _uiState.update { state ->
                    // Detail Source Race Guard (Ignore stale path work after the selected book changes)
                    // Source formatting runs asynchronously, so the book id is checked before applying the display label to prevent cross-book path flashes.
                    if (state.book?.book?.id == selectedBookId) {
                        state.copy(fullSourcePath = finalPath)
                    } else {
                        state
                    }
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

    /**
     * Friendly Source Location Builder (Maps storage metadata into a user-readable breadcrumb)
     *
     * Uses a typed library prefix plus LibraryRootEntity.displayName as the stable source label and BookFileEntity.sourcePath as the VFS-relative location, avoiding raw content URIs, server URLs, and internal ABS content endpoints.
     */
    private fun buildFriendlySourceLocation(
        book: BookWithProgress,
        files: List<BookFileEntity>,
        root: LibraryRootEntity?
    ): String {
        val displayFile = selectDisplayFile(book.book.sourceType, files)
        val playableFileCount = files.count { it.fileRole == AudiobookSchema.FileRole.AUDIO }
        val rootLabel = root?.displayName?.takeIf { it.isNotBlank() }
            ?: book.book.sourceRoot.takeIf { it.isNotBlank() }
            ?: "Library"
        val sourceScheme = resolveSourceDisplayScheme(root, book.book.sourceType)
        val segments = buildList {
            // Detail Source Type Prefix (Expose the library protocol before the user-facing source name)
            // Prefixing with SAF://, WEBDAV://, or ABS:// makes the same displayName understandable across local, remote, and server-backed libraries.
            add("$sourceScheme://${rootLabel.toSourceSchemeLabel()}")
            if (sourceScheme == "ABS") {
                // ABS Source Privacy (Hide remote stream API paths)
                // Audiobookshelf track content URLs are implementation details, so the indicator names the library and title instead of rendering content endpoints.
                add(book.book.title.ifBlank { displayFile?.displayName.orEmpty() })
            } else {
                val pathSegments = displayFile?.sourcePath.orEmpty().toDisplayPathSegments(displayFile?.displayName)
                // Legacy Source Path De-Duplication (Avoid repeating the library name in older imported records)
                // Current VFS paths are relative to the root, but some historical records may already include the root folder name as their first segment.
                addAll(pathSegments.dropLeadingDuplicate(rootLabel))
            }
        }.filter { it.isNotBlank() }
        // Detail Source Path Separator (Render breadcrumbs as path-like slashes)
        // The source indicator already uses protocol prefixes, so slash separators should stay compact without extra surrounding spaces.
        val baseLocation = segments.joinToString("/")
        return if (playableFileCount > 1) {
            "$baseLocation · $playableFileCount tracks"
        } else {
            baseLocation
        }
    }

    /**
     * Detail Source File Selector (Chooses the file that best represents the book source)
     *
     * Manifest-based books display their manifest file when available, while generated playlists and regular books display the first playable audio track.
     */
    private fun selectDisplayFile(sourceType: String, files: List<BookFileEntity>): BookFileEntity? {
        val playableFiles = files
            .filter { it.fileRole == AudiobookSchema.FileRole.AUDIO }
            .sortedBy { it.index }
        return when (sourceType) {
            AudiobookSchema.SourceType.CUE,
            AudiobookSchema.SourceType.M3U8 -> {
                files.firstOrNull { it.fileRole == AudiobookSchema.FileRole.SOURCE_MANIFEST }
                    ?: playableFiles.firstOrNull()
                    ?: files.minByOrNull { it.index }
            }
            AudiobookSchema.SourceType.GENERATED_M3U8 -> playableFiles.firstOrNull() ?: files.minByOrNull { it.index }
            else -> playableFiles.firstOrNull() ?: files.minByOrNull { it.index }
        }
    }

    /**
     * VFS Path Segment Formatter (Converts a VFS-relative path into breadcrumb segments)
     *
     * Decodes percent-encoded names, normalizes slash direction, and falls back to the file displayName when the source path is empty.
     */
    private fun String.toDisplayPathSegments(fallbackDisplayName: String?): List<String> {
        val normalizedPath = android.net.Uri.decode(this)
            .replace('\\', '/')
            .trim()
            .trim('/')
        val pathSegments = normalizedPath
            .split('/')
            .map { it.trim() }
            .filter { it.isNotBlank() }
        return pathSegments.ifEmpty {
            fallbackDisplayName?.takeIf { it.isNotBlank() }?.let(::listOf) ?: emptyList()
        }
    }

    /**
     * Duplicate Root Segment Filter (Removes repeated root names from display breadcrumbs)
     *
     * Keeps the source indicator compact when legacy data stores paths like "Audiobooks/Book/file.mp3" while the root display label is already "Audiobooks".
     */
    private fun List<String>.dropLeadingDuplicate(rootLabel: String): List<String> =
        if (firstOrNull()?.equals(rootLabel, ignoreCase = true) == true) drop(1) else this

    /**
     * Detail Source Scheme Resolver (Uses the library root sourceType as the display protocol)
     *
     * LibraryRootEntity.sourceType already stores SAF, WEBDAV, or ABS; BookEntity.sourceType is only a fallback because local books store content forms such as SINGLE_AUDIO, CUE, or M3U8.
     */
    private fun resolveSourceDisplayScheme(root: LibraryRootEntity?, bookSourceType: String): String =
        root?.sourceType?.takeIf { it.isNotBlank() } ?: when {
            bookSourceType == AudiobookSchema.SourceType.ABS_REMOTE -> "ABS"
            else -> "SAF"
        }

    /**
     * Source Scheme Label Normalizer (Keeps protocol-prefixed labels compact)
     *
     * Removes leading and trailing slashes from persisted labels so values like "/audiobooks" render as "webdav://audiobooks".
     */
    private fun String.toSourceSchemeLabel(): String =
        trim().trim('/').ifBlank { "Library" }


    companion object {
        // Protection Time Constant (Central source of truth for debouncing initial playback transitions)
        // Keeps VM delay windows and service sync controls synchronized to prevent timing drifts.
        private const val UNPLAYED_PROTECTION_WINDOW_MS = 3_000L
    }
}
