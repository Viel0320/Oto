package com.viel.aplayer.ui.search

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.viel.aplayer.data.store.GlassEffectMode
import dev.chrisbanes.haze.HazeState

/**
 * Search Route (Stateful search route adapter)
 *
 * Owns SearchViewModel collection, query cleanup side effects, and event wiring before delegating
 * visual concerns to SearchOverlay and SearchScreen.
 */
@Composable
fun SearchRoute(
    modifier: Modifier = Modifier,
    searchViewModel: SearchViewModel,
    // Haze Route Input (Receives the app-level sampling source for cross-page blur)
    // Route wiring passes this value through without letting SearchScreen know where the source is owned.
    hazeState: HazeState? = null,
    glassEffectMode: GlassEffectMode,
    /*
     * Active Search Detail Book Id (Search result source visibility selector)
     *
     * Carries only the detail target opened from Search so the selected result thumbnail can
     * exit as the shared-element source while Home recent and list channels remain untouched.
     */
    activeSearchDetailBookId: String? = null,
    onNavigateToDetail: (String) -> Unit,
    onLoadBook: (String) -> Unit,
    onNavigateToPlayer: () -> Unit
) {
    val isVisible by searchViewModel.isVisible.collectAsStateWithLifecycle()
    val query by searchViewModel.query.collectAsStateWithLifecycle()
    val searchResults by searchViewModel.searchResults.collectAsStateWithLifecycle()
    val searchHistory by searchViewModel.searchHistory.collectAsStateWithLifecycle()

    SearchOverlay(
        visible = isVisible,
        hazeState = hazeState,
        glassEffectMode = glassEffectMode,
        modifier = modifier
    ) {
        // Search Query Disposal Effect (Clear transient query data when the overlay leaves composition)
        // Keeping this in SearchRoute prevents SearchScreen from owning lifecycle cleanup responsibilities.
        DisposableEffect(Unit) {
            onDispose {
                searchViewModel.clearQuery()
            }
        }

        SearchScreen(
            query = query,
            searchResults = searchResults,
            searchHistory = searchHistory,
            onQueryChange = searchViewModel::onQueryChange,
            onSearch = searchViewModel::search,
            onClearQuery = searchViewModel::clearQuery,
            onDeleteHistory = searchViewModel::deleteHistory,
            onClearHistory = searchViewModel::clearHistory,
            onBack = { searchViewModel.setVisible(false) },
            activeSearchDetailBookId = activeSearchDetailBookId,
            onNavigateToDetail = { id ->
                // Search History Save Effect (Persist query before leaving search results for details)
                // The route owns this persistence side effect so SearchScreen stays a pure callback surface.
                searchViewModel.saveSearchHistory(query.text)
                onNavigateToDetail(id)
            },
            onLoadBook = onLoadBook,
            onNavigateToPlayer = onNavigateToPlayer,
            hazeState = hazeState,
            glassEffectMode = glassEffectMode
        )
    }
}
