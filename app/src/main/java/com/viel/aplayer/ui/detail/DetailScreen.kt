package com.viel.aplayer.ui.detail

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.viel.aplayer.data.store.GlassEffectMode
import top.yukonga.miuix.kmp.blur.LayerBackdrop

/**
 * DetailScreen Bridge (L2 Container Component)
 *
 * L2 container level component DetailScreen.
 * Serves purely as a controller for state transmission and event bridging, containing no direct visual rendering logic.
 * It passes the incoming DetailUiState as-is to the L3 pure rendering component DetailContent (no longer dismantling into flat parameters,
 * as DetailContent and its lower-level Layout sub-skeletons already use DetailUiState as their state contract).
 * This fulfills Compose official three-layer architecture specification (L1 Overlay -> L2 Screen -> L3 Layout/Content),
 * and decouples UI rendering from domain state logic.
 */
@Composable
fun DetailScreen(
    uiState: DetailUiState, // Input UI state model of the detail page
    onBackClick: () -> Unit, // Callback triggered when the back button is clicked or user drags down to dismiss
    modifier: Modifier = Modifier,
    // Fix M-19: Add onPlayPressed parameter, executed before clicking play to sink the 3-second play protection state into ViewModel
    onPlayPressed: () -> Unit = {},
    onPlayClick: () -> Unit = {}, // Callback to confirm triggering audio playback
    onMoreClick: () -> Unit = {}, // Callback for clicking top-right more control button
    onSearchClick: (String) -> Unit = {}, // Callback for tag click navigating to search
    // Glass effect mode passed from outside
    glassEffectMode: GlassEffectMode,
    // Shared miuix-blur background sampling source
    backdrop: LayerBackdrop? = null,
    // Sampling source for full foreground capture
    fullPageBackdrop: LayerBackdrop? = null,
    // Callback for launching edit metadata overlay
    onEditClick: (String) -> Unit = {},
) {
    // Fix L-10 (Direct UI State Transmission)
    // Directly pass the complete DetailUiState to L3 rendering layer, replacing the redundant round-trip of dismantling into flat parameters here and repackaging inside DetailContent.
    DetailContent(
        uiState = uiState,
        onBackClick = onBackClick,
        modifier = modifier,
        onPlayPressed = onPlayPressed,
        onPlayClick = onPlayClick,
        onMoreClick = onMoreClick,
        onSearchClick = onSearchClick,
        glassEffectMode = glassEffectMode,
        backdrop = backdrop,
        fullPageBackdrop = fullPageBackdrop,
        onEditClick = onEditClick
    )
}
