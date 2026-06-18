package com.viel.aplayer.ui.detail

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.viel.aplayer.data.db.AudiobookSchema
import com.viel.aplayer.shared.settings.GlassEffectMode
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
    // Update Read Status: Update readStatus parameter type to ReadStatus enum.
    onUpdateReadStatus: (String, AudiobookSchema.ReadStatus) -> Unit = { _, _ -> },
    // Detail Action Regeneration Bridge (Forward action-dialog metadata refresh)
    // DetailScreen only relays the command so library regeneration stays outside UI rendering.
    onForceRegenerate: (String) -> Unit = {},
    // Detail Action Delete Bridge (Forward action-dialog deletion)
    // Deletion cleanup stays centralized in the app shell instead of being performed inside Detail UI components.
    onDeleteBook: (String) -> Unit = {},
    // Detail Download Start Bridge (Forward manual cache starts after route-level permission preflight)
    // DetailScreen stays stateless and does not inspect Android notification permission or download runtime details.
    onDownloadBook: (String) -> Unit = {},
    // Detail Download Pause Bridge (Forward selected-book pause commands)
    // The application controller maps this to per-file stop reasons so other downloads keep running.
    onPauseDownload: (String) -> Unit = {},
    // Detail Download Resume Bridge (Forward selected-book resume commands)
    // Resume clears per-file stop reasons through the same book-level controller boundary.
    onResumeDownload: (String) -> Unit = {},
    // Detail Download Delete Bridge (Forward manual cache deletion commands)
    // Removing cache state remains separate from deleting the audiobook itself.
    onDeleteDownload: (String) -> Unit = {},
    // Glass effect mode passed from outside
    glassEffectMode: GlassEffectMode,
    // Detail HazeState Arguments (Separate inline controls from floating app surfaces)
    // hazeState is the stable app-level sampler, while fullPageHazeState is forwarded to menus and dialogs that must not rebind to a page-local source.
    fullPageHazeState: HazeState? = null,
    // Dynamic Cover Color (Propagate dynamic cover color for backdrop blending)
    // Accepts the active cover color extracted by the page CoverBackground.
    coverColor: androidx.compose.ui.graphics.Color?,
    // Color Extracted Callback (Notify parent overlay about extracted cover color)
    // Callback triggered when CoverBackground extracts a dominant color from its software backdrop image.
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
        onDownloadBook = onDownloadBook,
        onPauseDownload = onPauseDownload,
        onResumeDownload = onResumeDownload,
        onDeleteDownload = onDeleteDownload,
        glassEffectMode = glassEffectMode,
        fullPageHazeState = fullPageHazeState,
        coverColor = coverColor,
        onColorExtracted = onColorExtracted
    )
}
