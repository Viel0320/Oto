package com.viel.aplayer.ui.player.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.viel.aplayer.data.store.GlassEffectMode
import com.viel.aplayer.ui.common.theme.DynamicColorSchemeHelper
import com.viel.aplayer.ui.common.theme.LocalDarkTheme
import com.viel.aplayer.ui.motion.LocalAnimatedVisibilityScope
import com.viel.aplayer.ui.motion.LocalMini2PlayerTargetScope
import com.viel.aplayer.ui.navigation.PlayerNavigationActions
import com.viel.aplayer.ui.player.PlayerActions
import com.viel.aplayer.ui.player.PlayerScreen
import com.viel.aplayer.ui.player.PlayerViewModel
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Player overlay component (PlayerOverlay).
 *
 * This component has been completely refactored to strip away the mini player related Popup window and Z-index coordination of the full-screen player.
 */
@Composable
fun PlayerOverlay(
    playerViewModel: PlayerViewModel,
    playerActions: PlayerActions,
    playerNavigationActions: PlayerNavigationActions,
    // Glass effect mode must be explicitly passed from the settings state by the App container.
    glassEffectMode: GlassEffectMode,
    modifier: Modifier = Modifier
) {
    // Only listen to the player visibility (low-frequency signal)
    val isFullPlayerVisible by remember(playerViewModel) {
        playerViewModel.settingsState.map { it.isFullPlayerVisible }.distinctUntilChanged()
    }.collectAsStateWithLifecycle(initialValue = false)

    // Listen to metadataState to extract dynamic color
    val metadata by playerViewModel.metadataState.collectAsStateWithLifecycle()

    Box(modifier = modifier.fillMaxSize()) {
        // Full screen player layer
        //
        // Instantiate the playerBackdrop sampling source dedicated to the full screen player itself,
        // mount it on the outermost wrapping Box to collect real-time layout data of the entire player.
        // Setup Haze State (Manage overlay-wide blur capturing state)
        val playerHazeState = remember { HazeState() }
        // Align transition durations: Set PlayerOverlay slide enter and exit transition animations to 300ms for uniform UX speed.
        AnimatedVisibility(
            visible = isFullPlayerVisible,
            enter = slideInVertically(initialOffsetY = { it }, animationSpec = tween(300)),
            exit = slideOutVertically(targetOffsetY = { it }, animationSpec = tween(300))
        ) {
            /*
             * Propagate Full Visibility Scope (Local visibility tracking)
             * Bind this AnimatedVisibilityScope to LocalAnimatedVisibilityScope CompositionLocal
             * to let PlayerScreen match shared elements and perform dynamic corner radius morphing.
             */
            CompositionLocalProvider(
                /*
                 * Mini To Player Target Scope Provider (Full-player target isolation)
                 *
                 * Publishes the full-player visibility scope through the mini2player-specific
                 * target local so PlayerScreen shared elements do not consume route/detail scopes.
                 */
                LocalMini2PlayerTargetScope provides this@AnimatedVisibility,
                LocalAnimatedVisibilityScope provides this@AnimatedVisibility
            ) {
                val darkTheme = LocalDarkTheme.current
                val currentColorScheme = MaterialTheme.colorScheme
                val coverPath = metadata.coverPath

                // Reset Color State on Cover Path Changes: Re-initialize coverColor state whenever the coverPath changes using remember(coverPath) and load the cached color synchronously if available.
                var coverColor by remember(coverPath) {
                    mutableStateOf<Color?>(com.viel.aplayer.media.parser.ImageProcessor.getCachedColor(coverPath)?.let { Color(it) })
                }

                val playerColorScheme = remember(coverColor, darkTheme) {
                    if (coverColor != null) {
                        DynamicColorSchemeHelper.generateColorSchemeFromSeed(coverColor!!, darkTheme, currentColorScheme)
                    } else {
                        null
                    }
                }

                val contentBlock = @Composable {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .then(
                                if (glassEffectMode == GlassEffectMode.Haze) {
                                    // Setup PlayerOverlay Haze (Apply haze modifier to container to make it a blur source)
                                    Modifier.hazeSource(playerHazeState)
                                } else {
                                    Modifier
                                }
                            )
                    ) {
                        PlayerScreen(
                            viewModel = playerViewModel,
                            actions = playerActions,
                            navigationActions = playerNavigationActions,
                            // The full screen player internally manages creating the Haze blur effect for the chapter list, so only the mode is passed through here.
                            glassEffectMode = glassEffectMode,
                            // Setup dropdown menu blur (Pass HazeState to the drop-down menu to render glassmorphism)
                            hazeState = playerHazeState,
                            coverColor = coverColor,
                            onColorExtracted = { coverColor = it }
                        )
                    }
                }

                // Apply Local Theme (Nests the Composable content within the book-specific dynamic theme) Wraps PlayerScreen inside MaterialTheme if seed color exists.
                if (playerColorScheme != null) {
                    MaterialTheme(colorScheme = playerColorScheme, content = contentBlock)
                } else {
                    contentBlock()
                }
            }
        }
    }
}
