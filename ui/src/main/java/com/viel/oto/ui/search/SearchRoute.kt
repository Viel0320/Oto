package com.viel.oto.ui.search

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.viel.oto.shared.model.GlassEffectMode
import com.viel.oto.ui.common.uiPerformanceTrace
import dev.chrisbanes.haze.HazeState

/**
 * Stateful search route adapter.
 *
 * Owns SearchViewModel collection, query cleanup side effects, and event wiring before delegating
 * visual concerns to SearchOverlay and SearchScreen.
 */
@Composable
fun SearchRoute(
    modifier: Modifier = Modifier,
    searchViewModel: SearchViewModel,
    hazeState: HazeState? = null,
    glassEffectMode: GlassEffectMode,
    activeSearchDetailBookId: String? = null,
    onNavigateToDetail: (String) -> Unit,
    onLoadBook: (String) -> Unit,
    onNavigateToPlayer: () -> Unit
) {
    val isVisible by searchViewModel.isVisible.collectAsStateWithLifecycle()
    val query by searchViewModel.query.collectAsStateWithLifecycle()
    val searchResults by searchViewModel.searchResults.collectAsStateWithLifecycle()
    val searchHistory by searchViewModel.searchHistory.collectAsStateWithLifecycle()
    val dismissSearchOverlay = { searchViewModel.setVisible(false) }
    val searchTraceState = "visible=$isVisible,queryLength=${query.text.length}," +
        "results=${searchResults.size},history=${searchHistory.size},activeDetail=${activeSearchDetailBookId != null}"

    SearchOverlay(
        visible = isVisible,
        onBack = dismissSearchOverlay,
        hazeState = hazeState,
        glassEffectMode = glassEffectMode,
        modifier = modifier.uiPerformanceTrace(
            node = "SearchRoute",
            route = "Search",
            state = searchTraceState
        )
    ) {
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
            onBack = dismissSearchOverlay,
            activeSearchDetailBookId = activeSearchDetailBookId,
            onNavigateToDetail = { id ->
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
