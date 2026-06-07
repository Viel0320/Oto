package com.viel.aplayer.ui.home

import android.annotation.SuppressLint
import androidx.compose.runtime.Composable
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
 * Specifically collects UI data state from ViewModels' StateFlows and delegates list-local scroll ownership to child rendering components,
 * keeping this container focused on business state binding and route-level interaction wiring.
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

    // Home Dialog State (Page-level modal event holder)
    // Keeps dialog selection in the Home container so the content renderer reports clicks without owning concrete dialog implementations.
    var homeDialogState by remember { mutableStateOf<HomeDialogState>(HomeDialogState.None) }
    // Home Content Haze State (Keep page-local sampling limited to the bookshelf surface)
    // The LazyGrid registers this state for page-local content blur and isolated previews while app chrome and Dialog windows prefer shell-provided sources.
    val homeContentHazeState = remember { HazeState() }
    // Home Dialog Haze Selection (Prefer the app-level backdrop for dialog windows)
    // Falls back to the page-local source only when previews or isolated hosts do not provide the outer app sampling state.
    val resolvedHomeDialogHazeState = homeDialogHazeState ?: homeContentHazeState

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
