package com.viel.aplayer.ui.home

// UseCase Import Update: Align imports with the application usecase package for DeleteBookUseCase.
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.viel.aplayer.APlayerApplication
import com.viel.aplayer.R
import com.viel.aplayer.application.library.home.HomeBookItem
import com.viel.aplayer.application.library.home.HomeCatalogSortPolicy
import com.viel.aplayer.data.db.AudiobookSchema
import com.viel.aplayer.data.store.HomeBookStatusFilter
import com.viel.aplayer.data.store.HomeFilter
import com.viel.aplayer.data.store.HomeSortDirection
import com.viel.aplayer.data.store.HomeSortRule
import com.viel.aplayer.data.store.HomeViewStyle
import com.viel.aplayer.event.feedback.FeedbackMessages
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class LibraryViewModel(application: Application) : AndroidViewModel(application) {
    // Home Screen Dependency View (Resolve only home scene, settings, deletion use cases, and feedback needed by Home)
    // This keeps LibraryViewModel from seeing playback runtime, ABS worker, or VFS dependencies.
    private val homeDependencies = APlayerApplication.getHomeScreenDependencies(application)
    // Home Library Scene Surface (Consumes the home-scoped read model and commands instead of the full library bus)
    // This makes the home catalog dependency narrow while deeper projections can move into the read model later.
    private val homeLibraryReadModel = homeDependencies.homeLibraryReadModel
    private val homeLibraryUseCases = homeDependencies.homeLibraryUseCases
    private val settingsRepository = homeDependencies.settingsRepository
    // Application Event Sink (Routes home feedback through the process-wide UI event stream)
    // The home ViewModel no longer owns a local toast flow, so app-shell rendering stays centralized.
    private val appEventSink = homeDependencies.appEventSink

    /**
     * Book Deletion UseCase (Cross-domain coordinator to handle safe removal of individual books)
     */
    private val deleteBookUseCase = homeDependencies.deleteBookUseCase

    // User Selection Filter (Initially null, indicating no explicit user action has occurred)
    // When null, the combine stream resolves filter attributes via a strict priority chain.
    // This blocks competing updates from asynchronous settings during cold starts, preventing filter chips animation flickering.
    private val _selectedFilter = MutableStateFlow<HomeFilter?>(null)
    // User Book Status Filter (Initially null so DataStore can provide the restored dialog selection)
    // Explicit selections are kept in memory immediately, then persisted through SettingsRepository for future launches.
    private val _selectedBookStatusFilter = MutableStateFlow<HomeBookStatusFilter?>(null)
    // Home Dialog State Flow (Exposes dialog selection as a flow to combine with other UI states)
    // Holds the currently active dialog state in the ViewModel to prevent losing state during configuration changes.
    private val _homeDialogState = MutableStateFlow<HomeDialogState>(HomeDialogState.None)

    val uiState: StateFlow<LibraryUiState> = kotlinx.coroutines.flow.combine(
        kotlinx.coroutines.flow.combine(
            homeLibraryReadModel.audiobooks,
            homeLibraryReadModel.hasRegisteredLibraryRoots,
            _selectedFilter,
            _selectedBookStatusFilter,
            settingsRepository.settingsFlow
        ) { audiobooks, hasRegisteredLibraryRoots, userSelection, userBookStatusSelection, appSettings ->
            // Centralized Filter Resolution (Dispatches final filter state once all input streams are ready)
            // Prevents intermediate visual state jumps in home filter chips.
            // Priority hierarchy: Explicit User Selection > Persisted Cache Settings > NotStarted Default.
            // Centralized Filter Resolution (Dispatches final filter state once all input streams are ready)
            // Priority hierarchy: Explicit User Selection > Persisted Cache Settings.
            val activeFilter = userSelection ?: appSettings.homeFilter
            // Home Book Status Filter Resolution (Restore the dialog filter with a non-restrictive default)
            // BookStatus filtering is independent from read-progress chips, so it uses its own user override and DataStore key.
            val activeBookStatusFilter = userBookStatusSelection ?: appSettings.homeBookStatusFilter

            // Flow Pipeline Calculations (Handles grouping, filtering, and sorting in backend thread flows)
            // Ensures that the Composable UI layers remain completely stateless and focus strictly on rendering tasks.

            // Segment 1: Apply chosen BookStatus filter before read-progress filtering
            // This makes the Home view dialog constrain the entire catalog surface, including recent sections and grouped lists.
            val statusFilteredAudiobooks = audiobooks.filter { book ->
                activeBookStatusFilter.matches(book.status)
            }

            // Segment 2: Apply chosen read-progress filter to the already status-filtered book collection
            // Keeping the two filters sequential preserves the existing HomeFilter semantics while adding availability narrowing.
            val filteredAudiobooks = statusFilteredAudiobooks.filter { it.matchesFilter(activeFilter) }

            // Home Catalog Sort Application (Build the selected script-clustered order before grouping)
            // Sorting before groupBy preserves section order and item order through Kotlin's insertion-ordered LinkedHashMap result.
            val sortedAudiobooks = HomeCatalogSortPolicy.sort(
                books = filteredAudiobooks,
                sortRule = appSettings.homeSortRule,
                sortDirection = appSettings.homeSortDirection
            )

            // Home Catalog Grouping (Group by the same field selected in the sort rule)
            // This keeps the visual section title, section order, and user-selected pivot aligned across listgroup columns and cardgroup rows.
            val groupedAudiobooks = sortedAudiobooks.groupBy { book ->
                HomeCatalogSortPolicy.groupLabel(book, appSettings.homeSortRule)
            }

            // Segment 3: Filter recent book slots (Takes up to 10 for NotStarted, 5 for InProgress)
            val recentBooks = when (activeFilter) {
                HomeFilter.NotStarted -> statusFilteredAudiobooks.filter { it.isNotStarted }
                    .sortedByDescending { it.addedAt }
                    .take(10)
                HomeFilter.InProgress -> statusFilteredAudiobooks.filter { it.isInProgress && it.lastPlayedAt > 0 }
                    .sortedByDescending { it.lastPlayedAt }
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
                // Registered Root Propagation (Carry media-source presence into Home presentation state)
                // This keeps the empty-home add-library FAB keyed to real root registration instead of inferring setup state only from scanned books.
                hasRegisteredLibraryRoots = hasRegisteredLibraryRoots,
                selectedFilter = activeFilter,
                homeBookStatusFilter = activeBookStatusFilter,
                filteredAudiobooks = sortedAudiobooks,
                groupedAudiobooks = groupedAudiobooks,
                recentBooks = recentBooks,
                recentTitleRes = recentTitleRes,
                shouldShowRecentBooks = shouldShowRecentBooks,
                // Pass down glassmorphic mode properties to synchronize theme rendering across pages.
                glassEffectMode = appSettings.glassEffectMode,
                // Home View Preference Propagation (Forward persisted renderer selection to Home UI)
                // Keeps the ViewModel as the single settings consumer so HomeContent only receives ready-to-render state.
                homeViewStyle = appSettings.homeViewStyle,
                // Home Sort Preference Propagation (Forward persisted grouping rule to app bar controls)
                // The actual sorted/grouped catalog above is already derived from this same value.
                homeSortRule = appSettings.homeSortRule,
                // Home Sort Direction Propagation (Forward persisted in-cluster direction to app bar controls)
                // The sorted catalog above already applies this direction while leaving script cluster order fixed.
                homeSortDirection = appSettings.homeSortDirection,
                // Pass down themeMode properties (Synchronize app settings theme configuration down to LibraryUiState) Populate themeMode parameter.
                themeMode = appSettings.themeMode,
                // Pass down Dynamic Color Setting (Synchronize app settings dynamic color selection to LibraryUiState) Populate isDynamicColorEnabled parameter.
                isDynamicColorEnabled = appSettings.isDynamicColorEnabled
            )
        },
        _homeDialogState
    ) { baseState, dialogState ->
        // Combine Home Dialog State (Add dialog state to the main library UI state flow)
        // Aggregates the dialog state with the main page state so that the Composable receives a single unified state stream.
        baseState.copy(homeDialogState = dialogState)
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
    private fun HomeBookItem.matchesFilter(filter: HomeFilter): Boolean {
        return when (filter) {
            HomeFilter.NotStarted -> isNotStarted
            HomeFilter.InProgress -> isInProgress
            HomeFilter.Finished -> isFinished
        }
    }

    init {
        // Cold Start Scan Queue (Submitted to the home scene use case to hide WorkManager trigger details)
        homeLibraryUseCases.scheduleColdStartSync()
        // Home Filter Startup Policy (Preserve explicit and persisted category selection)
        // Cold start no longer rewrites HOME_FILTER from library contents, so in-progress books cannot override the user's saved Home category.
    }

    fun deleteBook(bookId: String) {
        viewModelScope.launch {
            // Book Deletion Coordination (Use the application use case to remove the book and stop playback safely)
            val fileExists = deleteBookUseCase.invoke(bookId)

            // Deletion Feedback Dispatch (Send home deletion status through the application event sink)
            // This keeps Toast rendering outside the ViewModel while avoiding another feature-local event stream.
            appEventSink.showToast(FeedbackMessages.homeBookDeleted(sourceFileKept = fileExists))
        }
    }

    // Update Reading State: Updates user progress status in database and dispatches feedback toasts.
    // Update Book Read Status: Change readStatus parameter type to type-safe ReadStatus enum.
    fun updateBookReadStatus(bookId: String, readStatus: AudiobookSchema.ReadStatus) {
        viewModelScope.launch {
            // Home Read Status Command (Routes manual status changes through the home scene use case)
            homeLibraryUseCases.updateReadStatus(bookId, readStatus)
            appEventSink.showToast(FeedbackMessages.homeReadStatusUpdated(readStatus))
        }
    }

    // Reconstruct Metadata cache: Rebuilds localized graphics cover cache and maps raw values asynchronously.
    fun forceRegenerateCoverAndMetadata(bookId: String) {
        viewModelScope.launch {
            appEventSink.showToast(FeedbackMessages.homeCoverMetadataRegenerationStarted())
            // Home Metadata Refresh Command (Keeps regeneration behind the home scene command surface)
            homeLibraryUseCases.regenerateCoverAndMetadata(bookId)
            appEventSink.showToast(FeedbackMessages.homeCoverMetadataRegenerationCompleted())
        }
    }

    fun setFilter(filter: HomeFilter) {
        // Apply manual filter selection.
        // Flushes choices to local states and updates preferences via SettingsRepository.
        _selectedFilter.value = filter
        viewModelScope.launch {
            // Update Home Filter (Passes the type-safe HomeFilter enum directly to repository updates)
            settingsRepository.updateHomeFilter(filter)
        }
    }

    fun setHomeBookStatusFilter(filter: HomeBookStatusFilter) {
        // Home Book Status Filter Update (Persist the dialog availability filter)
        // The in-memory override updates Home immediately while DataStore keeps the selection stable after restart.
        _selectedBookStatusFilter.value = filter
        viewModelScope.launch {
            // Update Home Book Status Filter (Passes the type-safe HomeBookStatusFilter enum directly to repository updates)
            settingsRepository.updateHomeBookStatusFilter(filter)
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
        // Home Sort Rule Update (Persist the user's selected catalog grouping and script-clustered order)
        // The ViewModel rebuilds filtered and grouped catalog sections from settingsFlow after the preference write completes.
        viewModelScope.launch {
            settingsRepository.updateHomeSortRule(rule)
        }
    }

    fun setHomeSortDirection(direction: HomeSortDirection) {
        viewModelScope.launch {
            // Home Sort Direction Update (Persist ascending or descending order inside each script cluster)
            // Script cluster order itself remains fixed in HomeCatalogSortPolicy so the setting only affects same-cluster comparisons.
            settingsRepository.updateHomeSortDirection(direction)
        }
    }

    // Show Book Actions Dialog (Show actions menu for a specific audiobook)
    // Sets the active dialog state to AudiobookActions with the selected book item.
    fun showBookActions(book: HomeBookItem) {
        _homeDialogState.value = HomeDialogState.AudiobookActions(book)
    }

    // Dismiss Home Dialog (Hide the currently shown home dialog)
    // Sets the active dialog state to None, closing any visible dialog.
    fun dismissDialog() {
        _homeDialogState.value = HomeDialogState.None
    }

}
