package com.viel.oto.ui.search

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
import com.viel.oto.shared.settings.GlassEffectMode
import dev.chrisbanes.haze.HazeState

/**
 * Stateless animated overlay shell.
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
    BackHandler(enabled = visible, onBack = onBack)

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
            color = if (isBlur) Color.Transparent else MaterialTheme.colorScheme.background
        ) {
            content()
        }
    }
}
