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
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource

/**
 * DetailOverlay Floating Screen (Decoupled Detail Overlay Component)
 *
 * Details page overlay component, decoupling details page visibility logic and animations from the main App container.
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
    // Setup Haze State Arguments (Map parameters to HazeState values) Changed backdrop and detailBackdrop from LayerBackdrop to HazeState.
    hazeState: HazeState? = null,
    detailHazeState: HazeState? = null,
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
        // Setup Box Modifier (Mount Haze state modifier on details container Box) Apply Haze modifier conditionally on the container Box.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .then(
                    if (glassEffectMode == GlassEffectMode.Haze && detailHazeState != null) {
                        Modifier.hazeSource(detailHazeState)
                    } else {
                        Modifier
                    }
                )
        ) {
            DetailScreen(
                uiState = detailUiState,
                onBackClick = { detailViewModel.setVisible(false) },
                onPlayPressed = { detailViewModel.onPlayPressed() },
                onSearchClick = { query ->
                    if (canStartNavigation()) {
                        // In-Place Search Activation (Do not close detail screen when searching tags)
                        // Directly launch search overlay on top of the detail screen without setting details visibility to false,
                        // keeping user context intact.
                        onNavigateToSearch(query)
                    }
                },
                onPlayClick = {
                    detailUiState.book?.let { bookWithProgress ->
                        // Click to play passes the book ID upwards through lambda, handled uniformly by PlayerViewModel in the host App container.
                        onPlayBook(bookWithProgress.book.id)
                    }
                },
                // Details page dropdown menu and other overlays uniformly follow the Material/Haze settings.
                glassEffectMode = glassEffectMode,
                // Setup DetailScreen Haze Parameters (Map details background and full-page blur sources) Replaced Miuix backdrop with hazeState and detailHazeState.
                hazeState = hazeState,
                fullPageHazeState = detailHazeState,
                // Pass down the in-memory lambda callback for editing book metadata
                onEditClick = onEditClick
            )
        }
    }
}
