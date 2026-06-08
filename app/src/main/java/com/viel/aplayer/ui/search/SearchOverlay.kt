package com.viel.aplayer.ui.search

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.viel.aplayer.data.store.GlassEffectMode
import dev.chrisbanes.haze.HazeState

/**
 * Search Overlay (Stateless animated overlay shell)
 *
 * Owns only visibility animation and full-screen surface styling. State collection and query effects
 * are handled by SearchRoute, while SearchScreen renders the passed state.
 */
@Composable
fun SearchOverlay(
    visible: Boolean,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    hazeState: HazeState? = null,
    glassEffectMode: GlassEffectMode,
    content: @Composable () -> Unit
) {
    // Search Overlay Back Handling (Dismiss overlay state from Android system back)
    // The search surface is rendered above Home instead of as a Nav3 destination, so the overlay must consume system back while it is visible.
    BackHandler(enabled = visible, onBack = onBack)

    // Search Overlay Animation Policy (Adapt transition style for frosted glass rendering)
    // Haze mode uses fade-only transitions to avoid blur sampler clipping during slide movement.
    val isBlur = glassEffectMode == GlassEffectMode.Haze && hazeState != null
    AnimatedVisibility(
        visible = visible,
        enter = if (isBlur) {
            fadeIn(animationSpec = tween(300))
        } else {
            slideInVertically(initialOffsetY = { it }, animationSpec = tween(300)) + fadeIn(animationSpec = tween(300))
        },
        exit = if (isBlur) {
            fadeOut(animationSpec = tween(300))
        } else {
            slideOutVertically(targetOffsetY = { it }, animationSpec = tween(300)) + fadeOut(animationSpec = tween(300))
        },
        modifier = modifier
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            // Transparent Haze Surface (Reveal the sampled background only when Haze is active)
            // Non-Haze mode keeps the Material background so the route behaves like a full-screen page.
            color = if (isBlur) Color.Transparent else MaterialTheme.colorScheme.background
        ) {
            content()
        }
    }
}
