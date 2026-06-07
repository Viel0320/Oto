package com.viel.aplayer.ui.home

// Import globally defined one-off UI feedback events to decouple module-specific LibraryUiEvents.
// UseCase Import Update: Align imports with the domain usecase package for DeleteBookUseCase.
import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.viel.aplayer.APlayerApplication
import com.viel.aplayer.R
import com.viel.aplayer.data.entity.BookWithProgress
import com.viel.aplayer.data.store.HomeSortRule
import com.viel.aplayer.data.store.HomeViewStyle
import com.viel.aplayer.ui.common.UiEvent
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.Collator
import java.util.Locale

class LibraryViewModel(application: Application) : AndroidViewModel(application) {
    private val container = (application as APlayerApplication).container
    // Target Facade (Transitioned to high-level LibraryFacade gateway for domain isolation)
    private val libraryFacade = container.libraryFacade
    private val settingsRepository = container.settingsRepository

    /**
     * Library Root Deletion UseCase (Cross-domain coordinator to handle clean removal of library roots)
     */
    private val deleteLibraryRootUseCase = container.deleteLibraryRootUseCase

    /**
     * Book Deletion UseCase (Cross-domain coordinator to handle safe removal of individual books)
     */
    private val deleteBookUseCase = container.deleteBookUseCase

    // One-Off Event Stream (Utilizes a unified, global `UiEvent` channel instead of module-level definitions)
    // Enhances domain purity by removing dependency on feature-specific UI event classes.
    private val _uiEvents = MutableSharedFlow<UiEvent>(extraBufferCapacity = 1)
    val uiEvents: SharedFlow<UiEvent> = _uiEvents.asSharedFlow()

    // User Selection Filter (Initially null, indicating no explicit user action has occurred)
    // When null, the combine stream resolves filter attributes via a strict priority chain.
    // This blocks competing updates from asynchronous settings during cold starts, preventing filter chips animation flickering.
    private val _selectedFilter = MutableStateFlow<HomeFilter?>(null)

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

        // Home Catalog Sort Application (Build the selected descending pinyin order before grouping)
        // Sorting before groupBy preserves section order and item order through Kotlin's insertion-ordered LinkedHashMap result.
        val sortedAudiobooks = filteredAudiobooks.sortedByHomePreference(appSettings.homeSortRule)

        // Home Catalog Grouping (Group by the same field selected in the sort rule)
        // This keeps the visual section title, section order, and user-selected pivot aligned across listgroup columns and cardgroup rows.
        val groupedAudiobooks = sortedAudiobooks.groupBy { it.homeGroupLabel(appSettings.homeSortRule) }

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
            filteredAudiobooks = sortedAudiobooks,
            groupedAudiobooks = groupedAudiobooks,
            recentBooks = recentBooks,
            recentTitleRes = recentTitleRes,
            shouldShowRecentBooks = shouldShowRecentBooks,
            // Pass down glassmorphic mode properties to synchronize theme rendering across pages.
            glassEffectMode = appSettings.glassEffectMode,
            // Home View Preference Propagation (Forward persisted renderer selection to Home UI)
            // Keeps the ViewModel as the single settings consumer so HomeScreenContent only receives ready-to-render state.
            homeViewStyle = appSettings.homeViewStyle,
            // Home Sort Preference Propagation (Forward persisted grouping rule to app bar controls)
            // The actual sorted/grouped catalog above is already derived from this same value.
            homeSortRule = appSettings.homeSortRule,
            // Pass down themeMode properties (Synchronize app settings theme configuration down to LibraryUiState) Populate themeMode parameter.
            themeMode = appSettings.themeMode,
            // Pass down Dynamic Color Setting (Synchronize app settings dynamic color selection to LibraryUiState) Populate isDynamicColorEnabled parameter.
            isDynamicColorEnabled = appSettings.isDynamicColorEnabled
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

    // Home Sort Key Selection (Resolve the metadata field used by the selected catalog pivot)
    // Blank values are kept blank here so the group label helper can consistently map them to the same visible fallback section.
    private fun BookWithProgress.homeSortKey(sortRule: HomeSortRule): String {
        return when (sortRule) {
            HomeSortRule.Author -> book.author.trim()
            HomeSortRule.Narrator -> book.narrator.trim()
            HomeSortRule.Series -> book.series.trim()
        }
    }

    // Home Group Label Resolution (Convert blank metadata into one stable visible section)
    // This prevents empty author, narrator, or series values from producing invisible headers in the catalog.
    private fun BookWithProgress.homeGroupLabel(sortRule: HomeSortRule): String {
        return homeSortKey(sortRule).ifBlank { "Unknown" }
    }

    // Home Pinyin Descending Sort (Apply locale-aware ordering for author, narrator, and series pivots)
    // Locale.CHINA Collator provides pinyin-friendly ordering for Chinese names while PRIMARY strength keeps Latin case differences from splitting adjacent values.
    private fun List<BookWithProgress>.sortedByHomePreference(sortRule: HomeSortRule): List<BookWithProgress> {
        val collator = Collator.getInstance(Locale.CHINA).apply {
            strength = Collator.PRIMARY
        }
        return sortedWith { left, right ->
            val primary = collator.compare(right.homeGroupLabel(sortRule), left.homeGroupLabel(sortRule))
            if (primary != 0) {
                primary
            } else {
                val title = collator.compare(right.book.title.trim(), left.book.title.trim())
                if (title != 0) {
                    title
                } else {
                    right.book.id.compareTo(left.book.id)
                }
            }
        }
    }

    init {
        // Cold start scan queue (Submitted to LibraryFacade to keep VM isolated from WorkManager configurations)
        libraryFacade.scheduleLibrarySync("COLD_START")
        // Home Filter Startup Policy (Preserve explicit and persisted category selection)
        // Cold start no longer rewrites HOME_FILTER from library contents, so in-progress books cannot override the user's saved Home category.
    }

    fun deleteBook(bookId: String) {
        viewModelScope.launch {
            // Coordinate Book Deletion: Use the unified domain DeleteBookUseCase to cleanly remove the book and stop playback.
            val fileExists = deleteBookUseCase.invoke(bookId)

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

    fun setHomeViewStyle(style: HomeViewStyle) {
        // Home View Style Update (Persist the user's selected catalog renderer)
        // The settings flow drives recomposition, keeping listgroup/cardgroup switching outside direct mutable UI state.
        viewModelScope.launch {
            settingsRepository.updateHomeViewStyle(style)
        }
    }

    fun setHomeSortRule(rule: HomeSortRule) {
        // Home Sort Rule Update (Persist the user's selected catalog grouping and descending pinyin order)
        // The ViewModel rebuilds filtered and grouped catalog sections from settingsFlow after the preference write completes.
        viewModelScope.launch {
            settingsRepository.updateHomeSortRule(rule)
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
