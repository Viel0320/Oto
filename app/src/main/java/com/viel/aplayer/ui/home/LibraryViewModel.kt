package com.viel.aplayer.ui.home

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.viel.aplayer.R
import com.viel.aplayer.application.library.LibraryReadStatus
import com.viel.aplayer.application.library.home.HomeBookItem
import com.viel.aplayer.application.library.home.HomeCatalogSortPolicy
import com.viel.aplayer.application.library.home.HomeLibraryReadModel
import com.viel.aplayer.application.library.home.HomeLibraryUseCases
import com.viel.aplayer.application.library.home.matchesHomeBookStatus
import com.viel.aplayer.application.library.settings.AppSettingsCommands
import com.viel.aplayer.application.library.settings.AppSettingsReadModel
import com.viel.aplayer.application.usecase.BookManagementUseCase
import com.viel.aplayer.event.AppEventSink
import com.viel.aplayer.event.feedback.BookManagementFeedbackFacts
import com.viel.aplayer.shared.settings.AppSettings
import com.viel.aplayer.shared.settings.HomeBookStatusFilter
import com.viel.aplayer.shared.settings.HomeFilter
import com.viel.aplayer.shared.settings.HomeSortDirection
import com.viel.aplayer.shared.settings.HomeSortRule
import com.viel.aplayer.shared.settings.HomeViewStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

class LibraryViewModel(
    private val homeLibraryReadModel: HomeLibraryReadModel,
    private val homeLibraryUseCases: HomeLibraryUseCases,
    private val settingsReadModel: AppSettingsReadModel,
    private val settingsCommands: AppSettingsCommands,
    private val appEventSink: AppEventSink,
    /**
     * Cross-domain coordinator for cleanup-first book removal.
     * Home delegates destructive book actions here so cover cache, manual downloads, and soft deletion stay in one application workflow.
     */
    private val bookManagementUseCase: BookManagementUseCase
) : ViewModel() {

    private val _selectedFilter = MutableStateFlow<HomeFilter?>(null)
    private val _selectedBookStatusFilter = MutableStateFlow<HomeBookStatusFilter?>(null)
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
        baseState.copy(homeDialogState = dialogState)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = LibraryUiState()
    )

    /**
     * ViewModel-owned page organization.
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
        val catalogOrganization = HomeCatalogSortPolicy.organize(
            books = filteredAudiobooks,
            sortRule = appSettings.homeSortRule,
            sortDirection = appSettings.homeSortDirection
        )
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
            filteredAudiobooks = catalogOrganization.sortedBooks,
            groupedAudiobooks = catalogOrganization.groupedBooks,
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
     * ViewModel-local read-progress filtering.
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
        viewModelScope.launch {
            delay(COLD_START_SCAN_DELAY_MS.milliseconds)
            homeLibraryUseCases.scheduleColdStartSync()
        }
        viewModelScope.launch {
            delay(COVER_RECOVERY_SWEEP_DELAY_MS.milliseconds)
            homeLibraryUseCases.recoverMissingCovers()
        }
    }

    fun deleteBook(bookId: String) {
        viewModelScope.launch {
            val fileExists = bookManagementUseCase.deleteBook(bookId)

            appEventSink.emitFeedback(
                BookManagementFeedbackFacts.bookDeleted(bookId, sourceFileKept = fileExists)
            )
        }
    }

    fun updateBookReadStatus(bookId: String, readStatus: LibraryReadStatus) {
        viewModelScope.launch {
            homeLibraryUseCases.updateReadStatus(bookId, readStatus)
            appEventSink.emitFeedback(
                BookManagementFeedbackFacts.readStatusChanged(bookId, readStatus)
            )
        }
    }

    /**
     * Submit the empty-state SAF picker result through the home command seam.
     * This keeps the app shell from creating the full SettingsViewModel just to register a local library root.
     */
    fun addLocalRootAndScheduleSync(uri: Uri) {
        homeLibraryUseCases.addLocalRootAndScheduleSync(uri)
    }

    fun forceRegenerateCoverAndMetadata(bookId: String) {
        viewModelScope.launch {
            appEventSink.emitFeedback(BookManagementFeedbackFacts.coverMetadataRegenerationStarted())
            homeLibraryUseCases.regenerateCoverAndMetadata(bookId)
            appEventSink.emitFeedback(BookManagementFeedbackFacts.coverMetadataRegenerationCompleted())
        }
    }

    fun setFilter(filter: HomeFilter) {
        _selectedFilter.value = filter
        viewModelScope.launch {
            settingsCommands.updateHomeFilter(filter)
        }
    }

    fun setHomeBookStatusFilter(filter: HomeBookStatusFilter) {
        _selectedBookStatusFilter.value = filter
        viewModelScope.launch {
            settingsCommands.updateHomeBookStatusFilter(filter)
        }
    }

    fun setHomeViewStyle(style: HomeViewStyle) {
        viewModelScope.launch {
            settingsCommands.updateHomeViewStyle(style)
        }
    }

    fun setHomeSortRule(rule: HomeSortRule) {
        viewModelScope.launch {
            settingsCommands.updateHomeSortRule(rule)
        }
    }

    fun setHomeSortDirection(direction: HomeSortDirection) {
        viewModelScope.launch {
            settingsCommands.updateHomeSortDirection(direction)
        }
    }

    fun showBookActions(book: HomeBookItem) {
        _homeDialogState.value = HomeDialogState.AudiobookActions(book)
    }

    fun dismissDialog() {
        _homeDialogState.value = HomeDialogState.None
    }

    private companion object {
        private const val COLD_START_SCAN_DELAY_MS = 2_000L
        private const val COVER_RECOVERY_SWEEP_DELAY_MS = 1_000L
    }

}
