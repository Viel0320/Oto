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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.viel.aplayer.data.store.GlassEffectMode
import com.viel.aplayer.ui.motion.LocalAnimatedVisibilityScope
import com.viel.aplayer.ui.motion.LocalHomeRecent2DetailTargetScope
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

    // Align transition durations: Set DetailOverlay slide and fade enter/exit transition animations to 300ms for visual uniformity.
    AnimatedVisibility(
        visible = detailUiState.isVisible,
        enter = slideInVertically(initialOffsetY = { it }, animationSpec = tween(300)) + fadeIn(animationSpec = tween(300)),
        exit = slideOutVertically(targetOffsetY = { it }, animationSpec = tween(300)) + fadeOut(animationSpec = tween(300)),
        modifier = modifier
    ) {
        // Clear Detail Selection (Trigger cleanup when overlay is disposed from composition tree)
        // Invokes clearDetails() on the ViewModel when the animated content is completely removed.
        DisposableEffect(Unit) {
            onDispose {
                detailViewModel.clearDetails()
            }
        }

        /*
         * Detail Animated Visibility Scope (Destination shared-element scope)
         *
         * Exposes this overlay's AnimatedVisibilityScope to nested detail artwork so the
         * Home recent cover can morph into the detail cover during overlay enter and exit.
        */
        CompositionLocalProvider(
            /*
             * Home To Detail Target Scope Provider (Detail cover target isolation)
             *
             * Publishes the DetailOverlay visibility scope through the Home->Detail-specific
             * target local so detail covers never inherit full-player overlay visibility scopes.
             */
            LocalHomeRecent2DetailTargetScope provides this@AnimatedVisibility,
            LocalAnimatedVisibilityScope provides this@AnimatedVisibility
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
}
