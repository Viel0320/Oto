package com.viel.aplayer.ui.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
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
import com.viel.aplayer.ui.motion.LocalMini2PlayerTargetScope
import com.viel.aplayer.ui.navigation.PlayerNavigationActions
import com.viel.aplayer.ui.settings.FullPlayerOpenSource
import dev.chrisbanes.haze.HazeState
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Player overlay component (PlayerOverlay).
 *
 * This component has been completely refactored to strip away the mini player related Popup window and Z-index coordination of the full-screen player.
 */
@Composable
fun PlayerOverlay(
    modifier: Modifier = Modifier,
    playerViewModel: PlayerViewModel,
    playerActions: PlayerActions,
    playerNavigationActions: PlayerNavigationActions,
    // Glass effect mode must be explicitly passed from the settings state by the App container.
    glassEffectMode: GlassEffectMode,
    // App-Level Haze Source (Stable sampler shared by app chrome, Search, and dialogs)
    // When provided by APlayerApp, the expanded player registers into this long-lived source so foreground overlays do not rebind or sample stale pages.
    appHazeState: HazeState? = null

) {
    // Only listen to the player visibility (low-frequency signal)
    val isFullPlayerVisible by remember(playerViewModel) {
        playerViewModel.settingsState.map { it.isFullPlayerVisible }.distinctUntilChanged()
    }.collectAsStateWithLifecycle(initialValue = false)

    // Listen to metadataState to extract dynamic color
    val metadata by playerViewModel.metadataState.collectAsStateWithLifecycle()
    // Player Floating Surface State (Collect modal inputs at the overlay layer)
    // Chapter sheets and bookmark dialogs are rendered outside PlayerScreen, so PlayerOverlay owns the lightweight state reads required to feed those surfaces.
    val progressState by playerViewModel.playbackProgressState.collectAsStateWithLifecycle()
    val settings by playerViewModel.settingsState.collectAsStateWithLifecycle()

    Box(modifier = modifier.fillMaxSize()) {
        // Full screen player layer
        //
        // Player Fallback Haze Source (Support isolated previews and tests without an app shell)
        // Production callers pass appHazeState from APlayerApp, while this fallback keeps PlayerOverlay renderable when hosted alone.
        val fallbackPlayerHazeState = remember { HazeState() }
        // Player Resolved Haze Source (Keep effect consumers bound to one stable state)
        // The app-level state is preferred so Search, dialogs, and player glass surfaces share a single sampling target across overlay transitions.
        val resolvedPlayerHazeState = appHazeState ?: fallbackPlayerHazeState
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
                 * Publishes the full-player visibility scope only when the current visibility
                 * change was requested by MiniPlayer, so direct playback entries render normally.
                 */
                LocalMini2PlayerTargetScope provides if (
                    settings.fullPlayerOpenSource == FullPlayerOpenSource.MiniPlayer
                ) {
                    this@AnimatedVisibility
                } else {
                    null
                },
                LocalAnimatedVisibilityScope provides this@AnimatedVisibility
            ) {
                val darkTheme = LocalDarkTheme.current
                val currentColorScheme = MaterialTheme.colorScheme
                val coverPath = metadata.coverPath

                // Reset Color State on Cover Path Changes: Re-initialize coverColor state whenever the coverPath changes using remember(coverPath) and load the cached color synchronously if available.
                var coverColor by remember(coverPath) {
                    mutableStateOf<Color?>(ImageProcessor.getCachedColor(coverPath)?.let { Color(it) })
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
                    ) {
                        PlayerScreen(
                            viewModel = playerViewModel,
                            actions = playerActions,
                            navigationActions = playerNavigationActions,
                            // Player Glass Haze Routing (Forward the stable sampler without wrapping the whole player tree)
                            // PlayerScreen registers only its backdrop layer into this HazeState, keeping controls and modal surfaces outside the source subtree.
                            glassEffectMode = glassEffectMode,
                            hazeState = resolvedPlayerHazeState,
                            coverColor = coverColor,
                            onColorExtracted = { coverColor = it },
                            // Overlay-Owned Floating Surfaces (Prevent player modal surfaces from living inside the sampled page tree)
                            // PlayerOverlay renders these surfaces as siblings after PlayerScreen so modal glass does not sample itself.
                            renderFloatingSurfaces = false
                        )

                        // Overlay Player Floating Surface Host (Keep player modal surfaces outside the sampled content tree)
                        // The chapter sheet and bookmark dialog use the app-level HazeState while PlayerScreen registers only its backdrop into that source.
                        PlayerFloatingSurfaceHost(
                            currentPosition = progressState.elapsedMs,
                            totalDuration = progressState.durationMs,
                            metadata = metadata,
                            settings = settings,
                            actions = playerActions,
                            hazeState = resolvedPlayerHazeState,
                            glassEffectMode = glassEffectMode
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
