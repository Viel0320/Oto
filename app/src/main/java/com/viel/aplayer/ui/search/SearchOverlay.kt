package com.viel.aplayer.ui.search

// Setup SearchOverlay Imports (Lifecycles & MiuixBlur)
// Import lifecycle-aware collectAsStateWithLifecycle extension to replace collectAsState,
// ensuring data observation is automatically blocked when the Activity is in STOPPED background state, preventing useless background calculations.
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.viel.aplayer.data.store.GlassEffectMode
import dev.chrisbanes.haze.HazeState

/**
 * SearchOverlay Setup (In-Activity Stateless Search Overlay)
 *
 * Newly designed in-activity non-independent search overlay (Stateful Overlay).
 * Wrapped in AnimatedVisibility with vertical slide-in/slide-out and elegant fade-in/fade-out animations.
 * Can directly share the same appBackdrop sampling source with the bottom home page to achieve a premium frosted glass visual effect.
 */
@Composable
fun SearchOverlay(
    modifier: Modifier = Modifier,
    searchViewModel: SearchViewModel,
    // Setup Haze State (Transition backdrop reference to HazeState)
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
    // Collect Visible State (Lifecycle-Aware Flow Collection)
    // Collect the visibility state of the overlay using collectAsStateWithLifecycle() to automatically cut off redundant data observation when app goes to background.
    val isVisible by searchViewModel.isVisible.collectAsStateWithLifecycle()

    // Adjust Animations (Adapt for Frosted Glass Visuals)
    // When the global setting has enabled MiuixBlur mode, we limit the overlay's entrance/exit animations to pure fade-in/fade-out.
    // This effectively avoids edge clipping or rendering flicker of the Gaussian blur sampling layer during fast slide-in/slide-out transitions.
    // In regular non-miuix-blur mode, continue using original slide-in/slide-out animations.
    // Determine Glass Blur Status (Enable blur only if in Haze mode and state is provided)
    val isBlur = glassEffectMode == GlassEffectMode.Haze && hazeState != null
    // Align transition durations: Set all SearchOverlay slide and fade enter/exit animations to 300ms for UI consistency.
    AnimatedVisibility(
        visible = isVisible,
        enter = if (isBlur) {
            fadeIn(animationSpec = tween(300))
        } else {
            slideInVertically(initialOffsetY = { it }, animationSpec = tween(300)) + fadeIn(animationSpec = tween(300))
        },
        exit = if (isBlur) {
            fadeOut(animationSpec = tween(300))
        } else {
            slideOutVertically(targetOffsetY = { it }, animationSpec = tween(300)) + fadeOut(animationSpec = tween(300))
        },
        modifier = modifier
    ) {
        // Reset Search Query (Wipe search text input and results on overlay dispose)
        // Resets the input query text field to empty when the search overlay is completely disposed from composition.
        DisposableEffect(Unit) {
            onDispose {
                searchViewModel.clearQuery()
            }
        }

        Surface(
            modifier = Modifier.fillMaxSize(),
            // If newly named MiuixBlur is enabled, set the outer Surface container background to transparent so rendering engine can reveal underlying content
            color = if (glassEffectMode == GlassEffectMode.Haze && hazeState != null) Color.Transparent else MaterialTheme.colorScheme.background
        ) {
            SearchScreen(
                onBack = { searchViewModel.setVisible(false) },
                activeSearchDetailBookId = activeSearchDetailBookId,
                onNavigateToDetail = onNavigateToDetail,
                onLoadBook = onLoadBook,
                onNavigateToPlayer = onNavigateToPlayer,
                viewModel = searchViewModel,
                hazeState = hazeState,
                glassEffectMode = glassEffectMode
            )
        }
    }
}

/**
 * SearchScreen Adaptor (Stateful Adapter Screen)
 * 
 * Stateful screen adapter for search container (Stateful Screen).
 * Responsible for collecting query text input, search history, and search results from SearchViewModel,
 * and transparently passing them intact to the stateless UI component SearchContent.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    modifier: Modifier = Modifier,
    onBack: () -> Unit,
    /*
     * Active Search Detail Book Id (Search result source visibility selector)
     *
     * Forwarded from the overlay host so stateless SearchContent can hide only the selected
     * search-result cover during the Search -> Detail shared-element handoff.
     */
    activeSearchDetailBookId: String? = null,
    onNavigateToDetail: (String) -> Unit,
    onLoadBook: (String) -> Unit,
    onNavigateToPlayer: () -> Unit,
    viewModel: SearchViewModel,
    // Setup Haze State (Transition backdrop reference to HazeState)
    hazeState: HazeState? = null,
    glassEffectMode: GlassEffectMode
) {
    // Collect Query and Results (Optimize Resource Usage in Background)
    // Collect query, searchResults, and searchHistory from ViewModel using collectAsStateWithLifecycle().
    // This immediately pauses flatMapLatest and combine operations on the database when the app is STOPPED (backgrounded),
    // dramatically optimizing battery and memory footprint.
    val query by viewModel.query.collectAsStateWithLifecycle()
    val searchResults by viewModel.searchResults.collectAsStateWithLifecycle()
    val searchHistory by viewModel.searchHistory.collectAsStateWithLifecycle()

    // Pass collected business data and callbacks completely statelessly to the decoupled stateless UI page.
    SearchContent(
        query = query,
        searchResults = searchResults,
        searchHistory = searchHistory,
        commandSuggestions = commandSuggestionsFor(query),
        onQueryChange = { viewModel.onQueryChange(it) },
        onSearch = { viewModel.search(it) },
        onClearQuery = { viewModel.clearQuery() },
        onDeleteHistory = { viewModel.deleteHistory(it) },
        onClearHistory = { viewModel.clearHistory() },
        onBack = onBack,
        activeSearchDetailBookId = activeSearchDetailBookId,
        onNavigateToDetail = { id ->
            viewModel.saveSearchHistory(query.text)
            onNavigateToDetail(id)
        },
        onLoadBook = onLoadBook,
        onNavigateToPlayer = onNavigateToPlayer,
        autoFocus = true,
        hazeState = hazeState,
        glassEffectMode = glassEffectMode,
        modifier = modifier
    )
}
