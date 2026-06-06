package com.viel.aplayer.ui.home

import android.annotation.SuppressLint
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.viel.aplayer.ui.detail.DetailEntrySource
import com.viel.aplayer.ui.detail.DetailViewModel
import com.viel.aplayer.ui.player.PlayerViewModel

/**
 * HomeFilter Enum (Home Library Filter Options)
 *
 * Filter options enum for the library home screen.
 */
enum class HomeFilter {
    /** Reading in progress (playback progress > 0 and unfinished) */
    InProgress,
    /** Not started */
    NotStarted,
    /** Finished reading */
    Finished
}

/**
 * HomeScreen Container (Stateful Home Page Component)
 *
 * Stateful home container component, responsible for observing and syncing state from LibraryViewModel and PlayerViewModel inside the main navigation host.
 * Through logical architectural refactoring, HomeScreen is upgraded to a high-level business state binding container.
 * Specifically collects UI data state from ViewModels' StateFlows, and manages the horizontal scroll state of the recent list `recentListState` internally,
 * completely shielding the scroll state loss risk when grid scrolling dismisses it, and purifying system navigation and routing architecture.
 */
@SuppressLint("FrequentlyChangingValue")
@Composable
fun HomeScreen(
    modifier: Modifier = Modifier,
    libraryViewModel: LibraryViewModel,
    playerViewModel: PlayerViewModel,
    detailViewModel: DetailViewModel,
    searchViewModel: com.viel.aplayer.ui.search.SearchViewModel,
    canStartNavigation: () -> Boolean,
    navigateBack: () -> Unit,
    // Settings Navigation Event (To delegate settings launch routing to parent controller)
    // Abstract callback parameter to notify parent overlay scope when user requests setting screen.
    onNavigateToSettings: () -> Unit
) {
    val playerUiState by playerViewModel.uiState.collectAsStateWithLifecycle()
    val libraryUiState by libraryViewModel.uiState.collectAsStateWithLifecycle()
    val detailUiState by detailViewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // Manage Horizontal Scroll State (State Loss Prevention)
    // Maintain independent scroll state for home page "recent" horizontal scrolling list, placed at the outermost layer.
    // This prevents the state of this horizontal list from being destroyed/reset due to leaving the screen when the grid (LazyVerticalGrid) scrolls up and down.
    val recentListState = rememberLazyListState()

    // Track Initial Book State (Detect Data Mutations)
    // Use state variables to record the first book ID of the last render and a flag indicating whether scroll needs to be reset.
    // In composition phase, grid layout has not run, so `firstVisibleItemIndex` and `firstVisibleItemScrollOffset` still preserve the real scroll position of the previous frame.
    // This allows us to capture the "before change start position" state before multi-book batch insertion shifts layouts, avoiding status detection failure and race conditions caused by Compose default anchor rules.
    var prevFirstBookId by remember { mutableStateOf<String?>(null) }
    var shouldScrollToStart by remember { mutableStateOf(false) }

    val recentBooks = libraryUiState.recentBooks
    /*
     * Active Recent Detail Book Id (Recent source handoff selector)
     *
     * Activates only the Home recent source when the detail overlay was opened from Recent,
     * preventing the main list thumbnail for the same book from joining the same transition.
     */
    val activeRecentDetailBookId = if (
        detailUiState.isVisible &&
        detailUiState.entrySource == DetailEntrySource.HomeRecent
    ) {
        detailUiState.book?.book?.id
    } else {
        null
    }
    /*
     * Active List Detail Book Id (Main-list source handoff selector)
     *
     * Activates only the Home list source when the detail overlay was opened from the main
     * catalog list, leaving the Recent section stable even if it contains the same book.
     */
    val activeListDetailBookId = if (
        detailUiState.isVisible &&
        detailUiState.entrySource == DetailEntrySource.HomeList
    ) {
        detailUiState.book?.book?.id
    } else {
        null
    }
    val firstBookId = recentBooks.firstOrNull()?.book?.id
    if (firstBookId != prevFirstBookId) {
        // Data source first item changed (new book imported or filter switched)
        val wasAtStart = recentListState.firstVisibleItemIndex == 0 && recentListState.firstVisibleItemScrollOffset == 0
        if (wasAtStart) {
            shouldScrollToStart = true
        }
        prevFirstBookId = firstBookId
    }

    LaunchedEffect(shouldScrollToStart) {
        if (shouldScrollToStart) {
            // Scroll to First Item (Safe Viewport Reset)
            // After layout runs and the viewport offsets due to anchoring, LaunchedEffect asynchronously and safely resets viewport to the leftmost 0th item.
            // Thus, no matter how many books are imported at once, if the user was at the start, viewport will stay locked on the leftmost newest book.
            recentListState.scrollToItem(0)
            shouldScrollToStart = false
        }
    }

    HomeScreenContent(
        modifier = modifier,
        selectedFilter = libraryUiState.selectedFilter,
        groupedByAuthor = libraryUiState.groupedByAuthor,
        recentBooks = recentBooks,
        activeRecentDetailBookId = activeRecentDetailBookId,
        activeListDetailBookId = activeListDetailBookId,
        shouldShowRecentBooks = libraryUiState.shouldShowRecentBooks,
        recentTitleRes = libraryUiState.recentTitleRes,
        glassEffectMode = libraryUiState.glassEffectMode,
        isMiniPlayerVisible = playerUiState.hasActiveTrack,
        recentListState = recentListState,
        onFilterSelected = { libraryViewModel.setFilter(it) },
        onNavigateToDetail = { id: String, entrySource: DetailEntrySource ->
            val book = libraryUiState.audiobooks.find { it.book.id == id }
            detailViewModel.selectBook(
                book = book,
                entrySource = entrySource
            )
        },
        onNavigateToSearch = {
            if (canStartNavigation()) {
                searchViewModel.setVisible(true)
            }
        },
        onLoadBook = { id: String ->
            playerViewModel.loadBook(id)
        },
        onNavigateToPlayer = {
            playerViewModel.setFullPlayerVisible(true)
        },
        onLibraryRootSelected = { uri -> libraryViewModel.onLibraryRootSelected(uri) },
        // Settings Navigation Callback (To delegate settings launch routing to upper overlay controller)
        // Invokes abstract settings navigation trigger callback instead of hardcoding intent startup.
        onNavigateToSettings = {
            if (canStartNavigation()) {
                onNavigateToSettings()
            }
        },
        onUpdateReadStatus = { bookId, status ->
            libraryViewModel.updateBookReadStatus(bookId, status)
        },
        onForceRegenerate = { bookId ->
            libraryViewModel.forceRegenerateCoverAndMetadata(bookId)
        },
        onDeleteBook = { bookId ->
            playerViewModel.closePlayback(bookId)
            libraryViewModel.deleteBook(bookId)
        }
    )
}
