package com.viel.aplayer.ui.detail

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.viel.aplayer.data.store.GlassEffectMode
import dev.chrisbanes.haze.HazeState

/**
 * DetailScreen Bridge (L2 Container Component)
 *
 * L2 container level component DetailScreen.
 * Serves purely as a controller for state transmission and event bridging, containing no direct visual rendering logic.
 */
@Composable
fun DetailScreen(
    uiState: DetailUiState, // Input UI state model of the detail page
    onBackClick: () -> Unit, // Callback triggered when the back button is clicked or user drags down to dismiss
    modifier: Modifier = Modifier,
    onPlayPressed: () -> Unit = {},
    onPlayClick: () -> Unit = {}, // Callback to confirm triggering audio playback
    onMoreClick: () -> Unit = {}, // Callback for clicking top-right more control button
    onSearchClick: (String) -> Unit = {}, // Callback for tag click navigating to search
    // Glass effect mode passed from outside
    glassEffectMode: GlassEffectMode,
    // Setup HazeState Arguments (Map backdrop parameters to HazeState) Changed LayerBackdrop parameter to HazeState.
    hazeState: HazeState? = null,
    fullPageHazeState: HazeState? = null,
    // Callback for launching edit metadata overlay
    onEditClick: (String) -> Unit = {},
) {
    // Pass Complete Parameters to L3 content (Forward layout modifiers) Bridges states to DetailContent view.
    DetailContent(
        uiState = uiState,
        onBackClick = onBackClick,
        modifier = modifier,
        onPlayPressed = onPlayPressed,
        onPlayClick = onPlayClick,
        onMoreClick = onMoreClick,
        onSearchClick = onSearchClick,
        glassEffectMode = glassEffectMode,
        hazeState = hazeState,
        fullPageHazeState = fullPageHazeState,
        onEditClick = onEditClick
    )
}
