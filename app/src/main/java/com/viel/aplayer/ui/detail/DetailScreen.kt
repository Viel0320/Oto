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
    onSearchClick: (String) -> Unit = {}, // Callback for tag click navigating to search
    // Detail Action Edit Bridge (Forward action-dialog edit intents)
    // The screen remains a stateless bridge and leaves edit overlay ownership to the route/app shell.
    onEditBook: (String) -> Unit = {},
    // Detail Action Read Status Bridge (Forward action-dialog read-status updates)
    // Keeping the callback here lets DetailContent report selected ids without depending on LibraryViewModel.
    onUpdateReadStatus: (String, String) -> Unit = { _, _ -> },
    // Detail Action Regeneration Bridge (Forward action-dialog metadata refresh)
    // DetailScreen only relays the command so library regeneration stays outside UI rendering.
    onForceRegenerate: (String) -> Unit = {},
    // Detail Action Delete Bridge (Forward action-dialog deletion)
    // Deletion cleanup stays centralized in the app shell instead of being performed inside Detail UI components.
    onDeleteBook: (String) -> Unit = {},
    // Glass effect mode passed from outside
    glassEffectMode: GlassEffectMode,
    // Detail HazeState Arguments (Separate inline controls from floating app surfaces)
    // hazeState is the stable app-level sampler, while fullPageHazeState is forwarded to menus and dialogs that must not rebind to a page-local source.
    hazeState: HazeState? = null,
    fullPageHazeState: HazeState? = null,
    // Dynamic Cover Color (Propagate dynamic cover color for backdrop blending)
    // Accepts the active cover color extracted from Coil bitmap memory.
    coverColor: androidx.compose.ui.graphics.Color?,
    // Color Extracted Callback (Notify parent overlay about extracted cover color)
    // Callback triggered when Coil successfully loads the cover and extracts its dominant color.
    onColorExtracted: (androidx.compose.ui.graphics.Color) -> Unit,
) {
    // Pass Complete Parameters to L3 content (Forward layout modifiers) Bridges states to DetailContent view.
    DetailContent(
        uiState = uiState,
        onBackClick = onBackClick,
        modifier = modifier,
        onPlayPressed = onPlayPressed,
        onPlayClick = onPlayClick,
        onSearchClick = onSearchClick,
        onEditBook = onEditBook,
        onUpdateReadStatus = onUpdateReadStatus,
        onForceRegenerate = onForceRegenerate,
        onDeleteBook = onDeleteBook,
        glassEffectMode = glassEffectMode,
        fullPageHazeState = fullPageHazeState,
        coverColor = coverColor,
        onColorExtracted = onColorExtracted
    )
}
