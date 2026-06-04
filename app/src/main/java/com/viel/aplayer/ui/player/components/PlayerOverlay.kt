package com.viel.aplayer.ui.player.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.viel.aplayer.data.store.GlassEffectMode
import com.viel.aplayer.ui.navigation.PlayerNavigationActions
import com.viel.aplayer.ui.player.PlayerActions
import com.viel.aplayer.ui.player.PlayerScreen
import com.viel.aplayer.ui.player.PlayerViewModel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop

/**
 * Player overlay component (PlayerOverlay).
 *
 * This component has been completely refactored to strip away the mini player related Popup window and high-frequency isolation containers,
 * solely bearing the pure, single-responsibility rendering visibility and Z-index wrapper coordination of the full-screen player (Full Player).
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

    Box(modifier = modifier.fillMaxSize()) {
        // Full screen player layer
        //
        // Instantiate the playerBackdrop sampling source dedicated to the full screen player itself,
        // mount it on the outermost wrapping Box to collect real-time layout data of the entire player (including foreground text and all control buttons),
        // and pass it through to PlayerScreen to allow the internal chapter list sheet component (ChapterListSheet) to achieve a truly clear blur effect.
        val playerBackdrop = rememberLayerBackdrop()
        AnimatedVisibility(
            visible = isFullPlayerVisible,
            enter = slideInVertically(initialOffsetY = { it }, animationSpec = tween(400)),
            exit = slideOutVertically(targetOffsetY = { it }, animationSpec = tween(400))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .then(
                        if (glassEffectMode == GlassEffectMode.MiuixBlur) {
                            Modifier.layerBackdrop(playerBackdrop)
                        } else {
                            Modifier
                        }
                    )
            ) {
                PlayerScreen(
                    viewModel = playerViewModel,
                    actions = playerActions,
                    navigationActions = playerNavigationActions,
                    // The full screen player internally manages creating the miuix-blur effect for the chapter list, so only the mode is passed through here.
                    glassEffectMode = glassEffectMode,
                    // Pass the full-page sampling source of the player itself to achieve seamless, high-quality frosted glass blur.
                    fullPageBackdrop = playerBackdrop
                )
            }
        }
    }
}
