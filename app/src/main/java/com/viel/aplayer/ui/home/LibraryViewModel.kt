package com.viel.aplayer.ui.home

// Import globally defined one-off UI feedback events to decouple module-specific LibraryUiEvents.
import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.viel.aplayer.APlayerApplication
import com.viel.aplayer.R
import com.viel.aplayer.data.entity.BookWithProgress
import com.viel.aplayer.data.entity.ScanSessionEntity
import com.viel.aplayer.ui.common.UiEvent
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class LibraryViewModel(application: Application) : AndroidViewModel(application) {
    private val container = (application as APlayerApplication).container
    // Target Facade (Transitioned to high-level LibraryFacade gateway for domain isolation)
    private val libraryFacade = container.libraryFacade
    private val settingsRepository = container.settingsRepository

    /**
     * Library Root Deletion UseCase (Cross-domain coordinator to handle clean removal of library roots)
     */
    private val deleteLibraryRootUseCase = container.deleteLibraryRootUseCase

    private val _scanResultDialogState = MutableStateFlow<ScanSessionEntity?>(null)
    val scanResultDialogState: StateFlow<ScanSessionEntity?> = _scanResultDialogState.asStateFlow()

    private var lastCompletedSessionId: String? = null
    // VM Startup Timestamp (Filters completed scan sessions from preceding app lifecycles, preventing duplicate popups)
    private val viewModelStartTime = System.currentTimeMillis()

    // One-Off Event Stream (Utilizes a unified, global `UiEvent` channel instead of module-level definitions)
    // Enhances domain purity by removing dependency on feature-specific UI event classes.
    private val _uiEvents = MutableSharedFlow<UiEvent>(extraBufferCapacity = 1)
    val uiEvents: SharedFlow<UiEvent> = _uiEvents.asSharedFlow()

    // User Selection Filter (Initially null, indicating no explicit user action has occurred)
    // When null, the combine stream resolves filter attributes via a strict priority chain.
    // This blocks competing updates from asynchronous settings during cold starts, preventing filter chips animation flickering.
    private val _selectedFilter = MutableStateFlow<HomeFilter?>(null)

    private var isFirstLoad = true

    val uiState: StateFlow<LibraryUiState> = kotlinx.coroutines.flow.combine(
        libraryFacade.audiobooks,
        _selectedFilter,
        settingsRepository.settingsFlow
    ) { audiobooks, userSelection, appSettings ->
        // Centralized Filter Resolution (Dispatches final filter state once all input streams are ready)
        // Prevents intermediate visual state jumps in home filter chips.
        // Priority hierarchy: Explicit User Selection > Persisted Cache Settings > NotStarted Default.
        val activeFilter = if (userSelection != null) {
            // Priority 1: Direct user selection took precedence.
            userSelection
        } else {
            // Priority 2: Restore previous state from cache. Falls back to `NotStarted` on failure.
            try {
                HomeFilter.valueOf(appSettings.homeFilter)
            } catch (_: Exception) {
                HomeFilter.NotStarted
            }
        }

        // Flow Pipeline Calculations (Handles grouping, filtering, and sorting in backend thread flows)
        // Ensures that the Composable UI layers remain completely stateless and focus strictly on rendering tasks.

        // Segment 1: Apply chosen filter to book collection
        val filteredAudiobooks = audiobooks.filter { it.matchesFilter(activeFilter) }

        // Segment 2: Group filtered books by author to drive sectioned list views
        val groupedByAuthor = filteredAudiobooks.groupBy { it.book.author }

        // Segment 3: Filter recent book slots (Takes up to 10 for NotStarted, 5 for InProgress)
        val recentBooks = when (activeFilter) {
            HomeFilter.NotStarted -> audiobooks.filter { it.isNotStarted }
                .sortedByDescending { it.book.addedAt }
                .take(10)
            HomeFilter.InProgress -> audiobooks.filter { it.isInProgress && (it.progress?.lastPlayedAt ?: 0) > 0 }
                .sortedByDescending { it.progress?.lastPlayedAt ?: 0 }
                .take(5)
            else -> emptyList()
        }

        // Segment 4: Map resource title labels based on filter type
        val recentTitleRes = when (activeFilter) {
            HomeFilter.NotStarted -> R.string.recently_added_title
            HomeFilter.InProgress -> R.string.recently_played_title
            else -> 0
        }

        // Segment 5: Evaluate visibility rules for the "Recent" horizontal list
        val shouldShowRecentBooks = (activeFilter == HomeFilter.NotStarted || activeFilter == HomeFilter.InProgress) && recentBooks.isNotEmpty()

        LibraryUiState(
            audiobooks = audiobooks,
            selectedFilter = activeFilter,
            filteredAudiobooks = filteredAudiobooks,
            groupedByAuthor = groupedByAuthor,
            recentBooks = recentBooks,
            recentTitleRes = recentTitleRes,
            shouldShowRecentBooks = shouldShowRecentBooks,
            // Pass down glassmorphic mode properties to synchronize theme rendering across pages.
            glassEffectMode = appSettings.glassEffectMode,
            // Pass down themeMode properties (Synchronize app settings theme configuration down to LibraryUiState) Populate themeMode parameter.
            themeMode = appSettings.themeMode
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        // Starts with an empty `LibraryUiState`, deferring filter evaluation until input flows compile.
        initialValue = LibraryUiState()
    )

    /**
     * Filter Matching: Evaluates whether an audiobook aligns with the active filter query.
     *
     * Moved from Composable layout to VM domain to consolidate data calculations.
     */
    private fun BookWithProgress.matchesFilter(filter: HomeFilter): Boolean {
        return when (filter) {
            HomeFilter.NotStarted -> isNotStarted
            HomeFilter.InProgress -> isInProgress
            HomeFilter.Finished -> isFinished
        }
    }

    init {
        // Cold start scan queue (Submitted to LibraryFacade to keep VM isolated from WorkManager configurations)
        libraryFacade.scheduleLibrarySync("COLD_START")
        observeScanSessions()

        viewModelScope.launch {
            // Optimize First Load Filter (Determine and persist optimal home filter on first load asynchronously)
            // Monitors book updates and triggers home filter auto-selection only when new filter state diverges from cached preferences.
            libraryFacade.audiobooks.collect { books ->
                if (books.isNotEmpty() && isFirstLoad) {
                    isFirstLoad = false
                    val autoFilter = if (books.any { it.isInProgress }) {
                        HomeFilter.InProgress
                    } else {
                        HomeFilter.NotStarted
                    }
                    val currentSettings = settingsRepository.settingsFlow.first()
                    if (currentSettings.homeFilter != autoFilter.name) {
                        settingsRepository.updateHomeFilter(autoFilter.name)
                    }
                }
            }
        }
    }

    private fun observeScanSessions() {
        viewModelScope.launch {
            libraryFacade.observeLatestScanSession().collect { session ->
                if (session != null && session.id != lastCompletedSessionId) {
                    // Filter Stale Sessions (Apply timestamp boundaries to block historical toast events)
                    // Blocks popup alerts triggered by cached flow variables returning completed events from prior launches.
                    val completedAt = session.completedAt ?: 0L
                    if (completedAt > viewModelStartTime) {
                        if (session.pendingActionCount > 0) {
                            _scanResultDialogState.value = session
                        }
                    }
                    // Remember the completed session so the same result does not reopen the dialog.
                    lastCompletedSessionId = session.id
                }
            }
        }
    }

    fun dismissScanResultDialog() {
        _scanResultDialogState.value = null
    }

    fun deleteBook(bookId: String) {
        viewModelScope.launch {
            // Playback Termination Intercept (Stop active stream service if viewed item is deleted)
            val playbackManager = com.viel.aplayer.media.PlaybackManager.getInstance(getApplication())
            val currentPlayingId = playbackManager.getCurrentBookId()
            if (currentPlayingId == bookId) {
                playbackManager.stopPlayback()
            }

            // Query file existence and erase database reference via LibraryFacade gateway
            val fileExists = libraryFacade.checkPrimaryAudioFileExists(bookId)

            libraryFacade.deleteBook(bookId)

            // Dispatch feedback toast via the global `UiEvent` stream
            val fileStatus = if (fileExists) "源文件已保留" else "源文件已丢失或不存在"
            val message = "书籍已从媒体库移除\n$fileStatus"
            _uiEvents.tryEmit(UiEvent.ShowToast(message))
        }
    }

    // Update Reading State: Updates user progress status in database and dispatches feedback toasts.
    fun updateBookReadStatus(bookId: String, readStatus: String) {
        viewModelScope.launch {
            // Invoke read-status update on high-level facade
            libraryFacade.updateBookReadStatus(bookId, readStatus)
            val message = when (readStatus) {
                com.viel.aplayer.data.db.AudiobookSchema.ReadStatus.NOT_STARTED -> "已标记为：未开始"
                com.viel.aplayer.data.db.AudiobookSchema.ReadStatus.IN_PROGRESS -> "已标记为：进行中"
                com.viel.aplayer.data.db.AudiobookSchema.ReadStatus.FINISHED -> "已标记为：已完成"
                else -> "状态已更新"
            }
            _uiEvents.tryEmit(UiEvent.ShowToast(message))
        }
    }

    // Reconstruct Metadata cache: Rebuilds localized graphics cover cache and maps raw values asynchronously.
    fun forceRegenerateCoverAndMetadata(bookId: String) {
        viewModelScope.launch {
            _uiEvents.tryEmit(UiEvent.ShowToast("正在重建封面与元数据..."))
            // Re-render caching assets and tags via high-level facade
            libraryFacade.forceRegenerateCoverAndMetadata(bookId)
            _uiEvents.tryEmit(UiEvent.ShowToast("封面与元数据重建已完成"))
        }
    }

    fun setFilter(filter: HomeFilter) {
        // Apply manual filter selection.
        // Flushes choices to local states and updates preferences via SettingsRepository.
        _selectedFilter.value = filter
        viewModelScope.launch {
            settingsRepository.updateHomeFilter(filter.name)
        }
    }

    fun onLibraryRootSelected(uri: Uri) {
        // Delegate directory mapping, root import, and synchronization tasks to the LibraryFacade
        libraryFacade.addLibraryRootAndScheduleSync(uri)
    }

    // Remove Library Root: Revokes directory permission, handles safe teardown of playback streams, and deletes metadata.
    fun deleteLibraryRoot(root: com.viel.aplayer.data.entity.LibraryRootEntity) {
        viewModelScope.launch {
            // Execute deletion through high-level coordinator to prevent reverse dependencies.
            val playbackWasStopped = deleteLibraryRootUseCase.invoke(root)
            val message = if (playbackWasStopped) {
                "媒体库已移除，当前播放已停止"
            } else {
                "媒体库已移除"
            }
            android.widget.Toast.makeText(
                getApplication(),
                message,
                android.widget.Toast.LENGTH_SHORT
            ).show()
        }
    }



    fun clearSearchHistory() {
        viewModelScope.launch {
            // Clean queries array via high-level facade
            libraryFacade.clearHistory()
        }
    }

    fun triggerRescan() {
        // Dispatch manual rescan request queue
        libraryFacade.scheduleLibrarySync("USER")
    }
}
