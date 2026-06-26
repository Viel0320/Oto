package com.viel.oto.ui.home

import android.annotation.SuppressLint
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.viel.oto.application.library.home.toDetailBookItem
import com.viel.oto.shared.settings.HomeBookStatusFilter
import com.viel.oto.shared.settings.HomeSortDirection
import com.viel.oto.shared.settings.HomeSortRule
import com.viel.oto.shared.settings.HomeViewStyle
import com.viel.oto.ui.common.uiPerformanceTrace
import com.viel.oto.ui.detail.DetailEntrySource
import com.viel.oto.ui.detail.DetailViewModel
import com.viel.oto.ui.home.components.HomeAppBar
import com.viel.oto.ui.home.components.HomeDialogHost
import com.viel.oto.ui.home.components.HomeViewPreferenceDialog
import com.viel.oto.ui.navigation.DetailOpenRequest
import com.viel.oto.ui.player.PlaybackViewModel
import com.viel.oto.ui.player.PlayerSettingsViewModel
import dev.chrisbanes.haze.HazeState

@SuppressLint("FrequentlyChangingValue")
@Composable
fun HomeScreen(
    modifier: Modifier = Modifier,
    libraryViewModel: LibraryViewModel,
    playbackViewModel: PlaybackViewModel,
    settingsViewModel: PlayerSettingsViewModel,
    detailViewModel: DetailViewModel,
    canStartNavigation: () -> Boolean = { true },
    onOpenDetail: (DetailOpenRequest) -> Unit = {},
    onNavigateToSettings: () -> Unit = {},
    onNavigateToSearch: () -> Unit = {},
    onAddLibraryRequested: () -> Unit = {},
    onEditBookRequested: (String) -> Unit = {},
    onHomeViewStyleSelected: (HomeViewStyle) -> Unit = {},
    onHomeSortRuleSelected: (HomeSortRule) -> Unit = {},
    onHomeSortDirectionSelected: (HomeSortDirection) -> Unit = {},
    onHomeBookStatusFilterSelected: (HomeBookStatusFilter) -> Unit = {},
) {
    val playerUiState by playbackViewModel.uiState.collectAsStateWithLifecycle()
    val libraryUiState by libraryViewModel.uiState.collectAsStateWithLifecycle()
    val detailUiState by detailViewModel.uiState.collectAsStateWithLifecycle()

    val detailBookItems = remember(libraryUiState.audiobooks) {
        libraryUiState.audiobooks.associate { it.id to it.toDetailBookItem() }
    }

    // Single HazeState shared by HomeContent (hazeSource) and the top bar / dialogs (hazeEffect).
    val homeContentHazeState = remember { HazeState() }

    var homeTopBarScrollToTopRequest by remember { mutableIntStateOf(0) }
    var isHomeViewPreferenceDialogVisible by remember { mutableStateOf(false) }

    val recentBooks = libraryUiState.recentBooks
    val activeRecentDetailBookId = if (
        detailUiState.isVisible &&
        detailUiState.entrySource == DetailEntrySource.HomeRecent
    ) {
        detailUiState.book?.bookId
    } else {
        null
    }
    val activeListDetailBookId = if (
        detailUiState.isVisible &&
        detailUiState.entrySource == DetailEntrySource.HomeList
    ) {
        detailUiState.book?.bookId
    } else {
        null
    }
    val homeTraceState = "filter=${libraryUiState.selectedFilter},books=${libraryUiState.audiobooks.size}," +
        "groups=${libraryUiState.groupedAudiobooks.size},recent=${recentBooks.size}," +
        "dialog=${libraryUiState.homeDialogState.javaClass.simpleName},mini=${playerUiState.hasActiveTrack}"

    Box(
        modifier = modifier
            .fillMaxSize()
            .uiPerformanceTrace(
                node = "HomeScreen",
                route = "Home",
                state = homeTraceState
            )
    ) {
        HomeContent(
            modifier = Modifier.fillMaxSize(),
            selectedFilter = libraryUiState.selectedFilter,
            groupedAudiobooks = libraryUiState.groupedAudiobooks,
            recentBooks = recentBooks,
            activeRecentDetailBookId = activeRecentDetailBookId,
            activeListDetailBookId = activeListDetailBookId,
            shouldShowRecentBooks = libraryUiState.shouldShowRecentBooks,
            recentTitleRes = libraryUiState.recentTitleRes,
            glassEffectMode = libraryUiState.glassEffectMode,
            homeViewStyle = libraryUiState.homeViewStyle,
            homeHazeState = homeContentHazeState,
            homeTopBarScrollToTopRequest = homeTopBarScrollToTopRequest,
            isMiniPlayerVisible = playerUiState.hasActiveTrack,
            shouldShowAddLibraryFab = libraryUiState.selectedFilter != null &&
                (!libraryUiState.hasRegisteredLibraryRoots || libraryUiState.audiobooks.isEmpty()),
            onAddLibraryRequested = onAddLibraryRequested,
            onFilterSelected = { libraryViewModel.setFilter(it) },
            onNavigateToDetail = { id: String, entrySource: DetailEntrySource ->
                val book = detailBookItems[id]
                onOpenDetail(
                    DetailOpenRequest(
                        book = book,
                        entrySource = entrySource
                    )
                )
            },
            onLoadBook = { id: String ->
                playbackViewModel.loadBook(id)
            },
            onNavigateToPlayer = {
                val isMiniPlayerVisible = playerUiState.hasActiveTrack &&
                    !settingsViewModel.settingsState.value.isMiniPlayerHidden
                if (isMiniPlayerVisible) {
                    settingsViewModel.openFullPlayerFromMini()
                } else {
                    settingsViewModel.openFullPlayerFromDirect()
                }
            },
            onBookActionsRequested = { homeBook ->
                libraryViewModel.showBookActions(homeBook)
            }
        )

        HomeAppBar(
            glassEffectMode = libraryUiState.glassEffectMode,
            hazeState = homeContentHazeState,
            onNavigateToSearch = {
                if (canStartNavigation()) {
                    onNavigateToSearch()
                }
            },
            onHomeViewOptionsClick = {
                if (canStartNavigation()) {
                    isHomeViewPreferenceDialogVisible = true
                }
            },
            onNavigateToSettings = {
                if (canStartNavigation()) {
                    onNavigateToSettings()
                }
            },
            onTitleDoubleTap = {
                homeTopBarScrollToTopRequest += 1
            },
            onHeightChanged = { }
        )

        if (isHomeViewPreferenceDialogVisible) {
            HomeViewPreferenceDialog(
                selectedViewStyle = libraryUiState.homeViewStyle,
                selectedSortRule = libraryUiState.homeSortRule,
                selectedSortDirection = libraryUiState.homeSortDirection,
                selectedBookStatusFilter = libraryUiState.homeBookStatusFilter,
                hazeState = homeContentHazeState,
                glassEffectMode = libraryUiState.glassEffectMode,
                onViewStyleSelected = onHomeViewStyleSelected,
                onSortRuleSelected = onHomeSortRuleSelected,
                onSortDirectionSelected = onHomeSortDirectionSelected,
                onBookStatusFilterSelected = onHomeBookStatusFilterSelected,
                onDismissRequest = {
                    isHomeViewPreferenceDialogVisible = false
                }
            )
        }

        HomeDialogHost(
            state = libraryUiState.homeDialogState,
            hazeState = homeContentHazeState,
            glassEffectMode = libraryUiState.glassEffectMode,
            onDismissRequest = {
                libraryViewModel.dismissDialog()
            },
            onEditBook = onEditBookRequested,
            onUpdateReadStatus = { bookId, status ->
                libraryViewModel.updateBookReadStatus(bookId, status)
            },
            onForceRegenerate = { bookId ->
                libraryViewModel.forceRegenerateCoverAndMetadata(bookId)
            },
            onDeleteBook = { bookId ->
                playbackViewModel.closePlayback(bookId)
                libraryViewModel.deleteBook(bookId)
            }
        )
    }
}
