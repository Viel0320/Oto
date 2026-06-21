package com.viel.aplayer.ui.home

import android.annotation.SuppressLint
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.viel.aplayer.application.library.home.toDetailBookItem
import com.viel.aplayer.ui.common.uiPerformanceTrace
import com.viel.aplayer.ui.detail.DetailEntrySource
import com.viel.aplayer.ui.detail.DetailViewModel
import com.viel.aplayer.ui.home.components.HomeDialogHost
import com.viel.aplayer.ui.navigation.DetailOpenRequest
import com.viel.aplayer.ui.player.PlaybackViewModel
import com.viel.aplayer.ui.player.PlayerSettingsViewModel
import dev.chrisbanes.haze.HazeState

@SuppressLint("FrequentlyChangingValue")
@Composable
fun HomeScreen(
    modifier: Modifier = Modifier,
    libraryViewModel: LibraryViewModel,
    playbackViewModel: PlaybackViewModel,
    settingsViewModel: PlayerSettingsViewModel,
    detailViewModel: DetailViewModel,
    onOpenDetail: (DetailOpenRequest) -> Unit = {},
    homeDialogHazeState: HazeState? = null,
    homeTopBarScrollToTopRequest: Int = 0,
    onAddLibraryRequested: () -> Unit = {},
    onEditBookRequested: (String) -> Unit = {},
) {
    val playerUiState by playbackViewModel.uiState.collectAsStateWithLifecycle()
    val libraryUiState by libraryViewModel.uiState.collectAsStateWithLifecycle()
    val detailUiState by detailViewModel.uiState.collectAsStateWithLifecycle()

    val detailBookItems = remember(libraryUiState.audiobooks) {
        libraryUiState.audiobooks.associate { it.id to it.toDetailBookItem() }
    }

    val homeContentHazeState = remember { HazeState() }
    val resolvedHomeDialogHazeState = homeDialogHazeState ?: homeContentHazeState

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
    HomeContent(
        modifier = modifier.uiPerformanceTrace(
            node = "HomeScreen",
            route = "Home",
            state = homeTraceState
        ),
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

    HomeDialogHost(
        state = libraryUiState.homeDialogState,
        hazeState = resolvedHomeDialogHazeState,
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
