package com.viel.aplayer.ui.detail

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.viel.aplayer.data.store.GlassEffectMode
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.layerBackdrop

/**
 * DetailOverlay Floating Screen (Decoupled Detail Overlay Component)
 *
 * Details page overlay component, decoupling details page visibility logic and animations from the main App container.
 * Removed direct dependency on NavController, decoupling the search button behavior into onNavigateToSearch callback,
 * allowing DetailOverlay to launch the independent SearchActivity at the Activity level to keep the data communication stream consistent.
 */
@Composable
fun DetailOverlay(
    detailViewModel: DetailViewModel,
    canStartNavigation: () -> Boolean,
    onPlayBook: (String) -> Unit,
    onNavigateToSearch: (String) -> Unit,
    // Glass effect mode must be explicitly passed from the setting state by the App container; details overlay no longer declares a default value.
    glassEffectMode: GlassEffectMode,
    modifier: Modifier = Modifier,
    // Add optional backdrop parameter, receiving the shared Backdrop sampling state from the Activity (home page sampling source).
    backdrop: LayerBackdrop? = null,
    // Add exclusive detailBackdrop sampling source dedicated to capturing details page itself.
    detailBackdrop: LayerBackdrop? = null,
    // Add callback for editing book click, propagating upwards to host Activity
    onEditClick: (String) -> Unit = {},
) {
    val detailUiState by detailViewModel.uiState.collectAsStateWithLifecycle()

    AnimatedVisibility(
        visible = detailUiState.isVisible,
        enter = slideInVertically(initialOffsetY = { it }, animationSpec = tween(400)) + fadeIn(),
        exit = slideOutVertically(targetOffsetY = { it }, animationSpec = tween(400)) + fadeOut(),
        modifier = modifier
    ) {
        // Restore Detail Backdrop Mounting (Avoid Capture Glitches)
        // Restore the layerBackdrop mounting of the global detailBackdrop on the outer Box.
        // This allows the global sampling source to capture the full screen of the details page including text and buttons.
        // Paired with the 450ms delay switching in APlayerApp, it perfectly solves the incomplete screen capture during transitions.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .then(
                    if (glassEffectMode == GlassEffectMode.MiuixBlur && detailBackdrop != null) {
                        Modifier.layerBackdrop(detailBackdrop)
                    } else {
                        Modifier
                    }
                )
        ) {
            DetailScreen(
                // Fix M-19 (Stateless Straightforward UI Rendering)
                // UI rendering layer completely returns to the 100% stateless straightforward rendering principle.
                // Completely removed the high-frequency subscription to PlayerViewModel and forced over-authorization override of uiState.copy inside Composable,
                // ensuring the 3-second locking protection period data emitted by DetailViewModel via stateFlow reaches the UI layer intact and accurately without conflict.
                uiState = detailUiState,
                onBackClick = { detailViewModel.setVisible(false) },
                // Fix M-19 (Playback State VM Delegation)
                // Notify the ViewModel to start the protection period when clicking play, then trigger the actual playback logic. The Composable layer does not need to hold any progress lock state itself.
                onPlayPressed = { detailViewModel.onPlayPressed() },
                onSearchClick = { query ->
                    detailViewModel.setVisible(false)
                    if (canStartNavigation()) {
                        // Handle Search Callback (Notify External Host)
                        // When the search keyword is clicked, notify the external components to perform search navigation and close the current detail overlay.
                        onNavigateToSearch(query)
                    }
                },
                onPlayClick = {
                    detailUiState.book?.let { bookWithProgress ->
                        // Fix M-19 (Playback Lambda Binding)
                        // Click to play passes the book ID upwards through lambda, handled uniformly by PlayerViewModel in the host App container.
                        // This completely removes the redundant dependency on external ViewModels inside DetailOverlay.
                        onPlayBook(bookWithProgress.book.id)
                    }
                },
                // Details page dropdown menu and other overlays uniformly follow the Material/miuix-blur settings.
                glassEffectMode = glassEffectMode,
                // Further pass the backdrop down to DetailScreen to render its own cover Gaussian background.
                backdrop = backdrop,
                // Pass the full-screen sampling source containing foreground text and buttons of the details page to DetailScreen for high-fidelity blur sampling of child dialogs and dropdown menus.
                fullPageBackdrop = detailBackdrop,
                // Pass down the in-memory lambda callback for editing book metadata
                onEditClick = onEditClick
            )
        }
    }
}
