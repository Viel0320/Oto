package com.viel.aplayer.ui.home

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.viel.aplayer.APlayerApplication
import com.viel.aplayer.R
import com.viel.aplayer.application.library.LibraryReadStatus
import com.viel.aplayer.application.library.home.HomeBookItem
import com.viel.aplayer.application.library.home.HomeCatalogSortPolicy
import com.viel.aplayer.application.library.home.matchesHomeBookStatus
import com.viel.aplayer.event.feedback.FeedbackMessages
import com.viel.aplayer.shared.settings.AppSettings
import com.viel.aplayer.shared.settings.HomeBookStatusFilter
import com.viel.aplayer.shared.settings.HomeFilter
import com.viel.aplayer.shared.settings.HomeSortDirection
import com.viel.aplayer.shared.settings.HomeSortRule
import com.viel.aplayer.shared.settings.HomeViewStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class LibraryViewModel(application: Application) : AndroidViewModel(application) {
    // Home Screen Dependency View (Resolve only home scene, settings, deletion use cases, and feedback needed by Home)
    // This keeps LibraryViewModel from seeing playback runtime, ABS worker, or VFS dependencies.
    private val homeDependencies = APlayerApplication.getHomeScreenDependencies(application)
    // Home Library Scene Surface (Consumes the home-scoped read model and commands instead of the full library bus)
    // The read model only exposes raw projections; page-specific filtering, sorting, and grouping stay in this ViewModel.
    private val homeLibraryReadModel = homeDependencies.homeLibraryReadModel
    private val homeLibraryUseCases = homeDependencies.homeLibraryUseCases
    // Title: Settings Abstractions Binding (Bind LibraryViewModel to read and command abstractions)
    // Decouples Home UI calculations from the concrete AppSettingsRepository class.
    private val settingsReadModel = homeDependencies.settingsReadModel
    private val settingsCommands = homeDependencies.settingsCommands
    // Application Event Sink (Routes home feedback through the process-wide UI event stream)
    // The home ViewModel no longer owns a local toast flow, so app-shell rendering stays centralized.
    private val appEventSink = homeDependencies.appEventSink

    /**
     * Book Management Use Case (Cross-domain coordinator for cleanup-first book removal)
     * Home delegates destructive book actions here so cover cache, manual downloads, and soft deletion stay in one application workflow.
     */
    private val bookManagementUseCase = homeDependencies.bookManagementUseCase

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
            settingsReadModel.settingsFlow
        ) { audiobooks, hasRegisteredLibraryRoots, userSelection, userBookStatusSelection, appSettings ->
            buildLibraryUiState(
                audiobooks = audiobooks,
                hasRegisteredLibraryRoots = hasRegisteredLibraryRoots,
                userSelection = userSelection,
                userBookStatusSelection = userBookStatusSelection,
                appSettings = appSettings
            )
        }.flowOn(Dispatchers.Default),
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
     * Home Catalog UI State Builder (ViewModel-owned page organization)
     *
     * Applies transient chip/dialog selections, persisted Home settings, script-clustered sorting, grouping, and recent-section slicing after the read model has delivered raw Room-free projections.
     */
    private fun buildLibraryUiState(
        audiobooks: List<HomeBookItem>,
        hasRegisteredLibraryRoots: Boolean,
        userSelection: HomeFilter?,
        userBookStatusSelection: HomeBookStatusFilter?,
        appSettings: AppSettings
    ): LibraryUiState {
        val activeFilter = userSelection ?: appSettings.homeFilter
        val activeBookStatusFilter = userBookStatusSelection ?: appSettings.homeBookStatusFilter
        val statusFilteredAudiobooks = audiobooks.filter { book ->
            activeBookStatusFilter.matchesHomeBookStatus(book.status)
        }
        val filteredAudiobooks = statusFilteredAudiobooks.filter { book ->
            book.matchesFilter(activeFilter)
        }
        val sortedAudiobooks = HomeCatalogSortPolicy.sort(
            books = filteredAudiobooks,
            sortRule = appSettings.homeSortRule,
            sortDirection = appSettings.homeSortDirection
        )
        val groupedAudiobooks = sortedAudiobooks.groupBy { book ->
            HomeCatalogSortPolicy.groupLabel(book, appSettings.homeSortRule)
        }
        val recentBooks = when (activeFilter) {
            HomeFilter.NotStarted -> statusFilteredAudiobooks.filter { book -> book.isNotStarted }
                .sortedByDescending { book -> book.addedAt }
                .take(10)
            HomeFilter.InProgress -> statusFilteredAudiobooks.filter { book -> book.isInProgress && book.lastPlayedAt > 0 }
                .sortedByDescending { book -> book.lastPlayedAt }
                .take(5)
            HomeFilter.Finished -> emptyList()
        }
        val shouldShowRecentBooks = (
            activeFilter == HomeFilter.NotStarted ||
                activeFilter == HomeFilter.InProgress
            ) && recentBooks.isNotEmpty()

        return LibraryUiState(
            audiobooks = audiobooks,
            hasRegisteredLibraryRoots = hasRegisteredLibraryRoots,
            selectedFilter = activeFilter,
            homeBookStatusFilter = activeBookStatusFilter,
            filteredAudiobooks = sortedAudiobooks,
            groupedAudiobooks = groupedAudiobooks,
            recentBooks = recentBooks,
            recentTitleRes = when (activeFilter) {
                HomeFilter.NotStarted -> R.string.recently_added_title
                HomeFilter.InProgress -> R.string.recently_played_title
                HomeFilter.Finished -> 0
            },
            shouldShowRecentBooks = shouldShowRecentBooks,
            glassEffectMode = appSettings.glassEffectMode,
            homeViewStyle = appSettings.homeViewStyle,
            homeSortRule = appSettings.homeSortRule,
            homeSortDirection = appSettings.homeSortDirection,
            themeMode = appSettings.themeMode,
            isDynamicColorEnabled = appSettings.isDynamicColorEnabled,
            isAmoledEnabled = appSettings.isAmoledEnabled
        )
    }

    /**
     * Home Progress Filter Matching (ViewModel-local read-progress filtering)
     * Keeps chip selection behavior beside the catalog UI state builder so the read model remains a projection adapter instead of a Home page organizer.
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
            val fileExists = bookManagementUseCase.deleteBook(bookId)

            // Deletion Feedback Dispatch (Send home deletion status through the application event sink)
            // This keeps Toast rendering outside the ViewModel while avoiding another feature-local event stream.
            appEventSink.showToast(FeedbackMessages.homeBookDeleted(sourceFileKept = fileExists))
        }
    }

    // Update Reading State: Updates user progress status in database and dispatches feedback toasts.
    // Update Book Read Status: Change readStatus parameter type to type-safe ReadStatus enum.
    fun updateBookReadStatus(bookId: String, readStatus: LibraryReadStatus) {
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
            settingsCommands.updateHomeFilter(filter)
        }
    }

    fun setHomeBookStatusFilter(filter: HomeBookStatusFilter) {
        // Home Book Status Filter Update (Persist the dialog availability filter)
        // The in-memory override updates Home immediately while DataStore keeps the selection stable after restart.
        _selectedBookStatusFilter.value = filter
        viewModelScope.launch {
            // Update Home Book Status Filter (Passes the type-safe HomeBookStatusFilter enum directly to repository updates)
            settingsCommands.updateHomeBookStatusFilter(filter)
        }
    }

    fun setHomeViewStyle(style: HomeViewStyle) {
        // Home View Style Update (Persist the user's selected catalog renderer)
        // The settings flow drives recomposition, keeping listgroup/Cardgroup switching outside direct mutable UI state.
        viewModelScope.launch {
            settingsCommands.updateHomeViewStyle(style)
        }
    }

    fun setHomeSortRule(rule: HomeSortRule) {
        // Home Sort Rule Update (Persist the user's selected catalog grouping and script-clustered order)
        // The ViewModel rebuilds filtered and grouped catalog sections from settingsFlow after the preference write completes.
        viewModelScope.launch {
            settingsCommands.updateHomeSortRule(rule)
        }
    }

    fun setHomeSortDirection(direction: HomeSortDirection) {
        viewModelScope.launch {
            // Home Sort Direction Update (Persist ascending or descending order inside each script cluster)
            // Script cluster order itself remains fixed in HomeCatalogSortPolicy so the setting only affects same-cluster comparisons.
            settingsCommands.updateHomeSortDirection(direction)
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
