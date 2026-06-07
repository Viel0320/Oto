package com.viel.aplayer.ui.detail

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.viel.aplayer.data.store.GlassEffectMode
import com.viel.aplayer.media.parser.ImageProcessor
import com.viel.aplayer.ui.common.theme.DynamicColorSchemeHelper
import com.viel.aplayer.ui.common.theme.LocalDarkTheme
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
        // Disable Auto-Clear Trigger: Removed the DisposableEffect that triggered clearDetails() on dispose to prevent clearing the details selection state.
        // DisposableEffect(Unit) {
        //     onDispose {
        //         detailViewModel.clearDetails()
        //     }
        // }

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
            val darkTheme = LocalDarkTheme.current
            val currentColorScheme = MaterialTheme.colorScheme
            val coverPath = detailUiState.book?.book?.coverPath

            // Reset Color State on Cover Path Changes: Re-initialize coverColor state whenever the coverPath changes using remember(coverPath) and load the cached color synchronously if available.
            var coverColor by remember(coverPath) {
                mutableStateOf<Color?>(ImageProcessor.getCachedColor(coverPath)?.let { Color(it) })
            }

            val detailColorScheme = remember(coverColor, darkTheme) {
                if (coverColor != null) {
                    DynamicColorSchemeHelper.generateColorSchemeFromSeed(coverColor!!, darkTheme, currentColorScheme)
                } else {
                    null
                }
            }

            val contentBlock = @Composable {
                // Detail App-Level Haze Source (Feed stable app chrome samplers with current Detail content)
                // MiniPlayerOverlay keeps a constant app-level HazeState to avoid rebinding flashes, so Detail must register into that same source while it is visible.
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .then(
                            if (glassEffectMode == GlassEffectMode.Haze && hazeState != null) {
                                Modifier.hazeSource(hazeState)
                            } else {
                                Modifier
                            }
                        )
                ) {
                    // Detail Local Haze Source (Preserve detail-owned inline glass controls)
                    // Detail page controls can keep local sampling, while dialogs and cross-page overlays are routed to the stable app-level HazeState.
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
                            // Detail Dialog Haze Routing (Use app-level source for floating surfaces)
                            // Detail registers its visible content into hazeState above, so menus and info dialogs can sample Detail without rebinding to detailHazeState.
                            hazeState = hazeState,
                            fullPageHazeState = hazeState,
                            // Pass down the in-memory lambda callback for editing book metadata
                            onEditClick = onEditClick,
                            coverColor = coverColor,
                            onColorExtracted = { coverColor = it }
                        )
                    }
                }
            }

            // Apply Local Theme (Nests the Composable content within the book-specific dynamic theme) Wraps DetailScreen inside MaterialTheme if seed color exists.
            if (detailColorScheme != null) {
                MaterialTheme(colorScheme = detailColorScheme, content = contentBlock)
            } else {
                contentBlock()
            }
        }
    }
}
