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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.viel.aplayer.ui.detail.DetailEntrySource
import com.viel.aplayer.ui.detail.DetailViewModel
import com.viel.aplayer.ui.home.components.HomeDialogHost
import com.viel.aplayer.ui.player.PlayerViewModel
import dev.chrisbanes.haze.HazeState

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
    // Home Dialog Backdrop Source (Allow app shell to provide the cross-layer blur source)
    // Dialogs are rendered above the page content, so they should sample the app-level backdrop when one is available instead of the LazyGrid-local fallback source.
    homeDialogHazeState: HazeState? = null,
    // Home Top Bar Height (Reserve space for the NavHost-owned overlay header)
    // APlayerNavHost measures the extracted HomeAppBar and passes its height back so Home content can keep stable top padding without owning the chrome component.
    homeTopBarHeightPx: Int = 0,
    // Home Top Bar Scroll Request (Consume title double-tap events from the NavHost-owned header)
    // The header lives outside Home content, so scroll-to-top is bridged as an incrementing event instead of sharing LazyGridState upward.
    homeTopBarScrollToTopRequest: Int = 0,
) {
    val playerUiState by playerViewModel.uiState.collectAsStateWithLifecycle()
    val libraryUiState by libraryViewModel.uiState.collectAsStateWithLifecycle()
    val detailUiState by detailViewModel.uiState.collectAsStateWithLifecycle()
    val scanResult by libraryViewModel.scanResultDialogState.collectAsStateWithLifecycle()

    // Home Dialog State (Page-level modal event holder)
    // Keeps dialog selection in the Home container so the content renderer reports clicks without owning concrete dialog implementations.
    var homeDialogState by remember { mutableStateOf<HomeDialogState>(HomeDialogState.None) }
    // Home Content Haze State (Keep page-local sampling limited to the bookshelf surface)
    // The LazyGrid registers this state for page-local content blur and isolated previews while app chrome and Dialog windows prefer shell-provided sources.
    val homeContentHazeState = remember { HazeState() }
    // Home Dialog Haze Selection (Prefer the app-level backdrop for dialog windows)
    // Falls back to the page-local source only when previews or isolated hosts do not provide the outer app sampling state.
    val resolvedHomeDialogHazeState = homeDialogHazeState ?: homeContentHazeState

    // Home Scan Result Dialog Sync (Route completed import feedback into the page dialog host)
    // Scan completion originates in LibraryViewModel, but the concrete dialog is mounted by HomeDialogHost so Home-owned dialogs share the same template and app-level blur source.
    LaunchedEffect(scanResult, homeDialogState) {
        val session = scanResult
        when {
            session != null && homeDialogState == HomeDialogState.None -> {
                homeDialogState = HomeDialogState.ScanResult(session)
            }
            session == null && homeDialogState is HomeDialogState.ScanResult -> {
                homeDialogState = HomeDialogState.None
            }
        }
    }

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
        groupedAudiobooks = libraryUiState.groupedAudiobooks,
        recentBooks = recentBooks,
        activeRecentDetailBookId = activeRecentDetailBookId,
        activeListDetailBookId = activeListDetailBookId,
        shouldShowRecentBooks = libraryUiState.shouldShowRecentBooks,
        recentTitleRes = libraryUiState.recentTitleRes,
        glassEffectMode = libraryUiState.glassEffectMode,
        homeViewStyle = libraryUiState.homeViewStyle,
        homeHazeState = homeContentHazeState,
        homeTopBarHeightPx = homeTopBarHeightPx,
        homeTopBarScrollToTopRequest = homeTopBarScrollToTopRequest,
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
        onLoadBook = { id: String ->
            playerViewModel.loadBook(id)
        },
        onNavigateToPlayer = {
            playerViewModel.setFullPlayerVisible(true)
        },
        onLibraryRootSelected = { uri -> libraryViewModel.onLibraryRootSelected(uri) },
        onBookActionsRequested = { bookWithProgress ->
            // Home Dialog Request (Route long-press actions to the page dialog host)
            // Stores only the selected audiobook payload and lets HomeDialogHost derive the concrete dialog tree.
            homeDialogState = HomeDialogState.AudiobookActions(bookWithProgress)
        }
    )

    HomeDialogHost(
        state = homeDialogState,
        hazeState = resolvedHomeDialogHazeState,
        glassEffectMode = libraryUiState.glassEffectMode,
        onDismissRequest = {
            // Home Dialog Dismissal (Return the page dialog host to idle)
            // Centralizes dismissal so first-level and nested confirmation dialogs clear through the same page-owned state.
            homeDialogState = HomeDialogState.None
        },
        onDismissScanResult = {
            // Scan Result Dismissal (Clear both host state and source ViewModel state)
            // Keeps the derived dialog host and LibraryViewModel event state synchronized so the completed scan summary does not reopen.
            homeDialogState = HomeDialogState.None
            libraryViewModel.dismissScanResultDialog()
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
