package com.viel.oto.ui.detail

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.viel.oto.shared.settings.GlassEffectMode
import com.viel.oto.ui.motion.LocalAnimatedVisibilityScope
import com.viel.oto.ui.motion.LocalHomeRecent2DetailTargetScope
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource

/**
 * Stateless animated overlay shell.
 *
 * Owns only overlay animation, shared-element scope publication, and haze source registration.
 * Route-level state and screen callbacks are injected through [content].
 *
 * @param hazeState App-level sampler that receives the visible Detail surface when Haze mode is active.
 */
@Composable
fun DetailOverlay(
    visible: Boolean,
    glassEffectMode: GlassEffectMode,
    modifier: Modifier = Modifier,
    hazeState: HazeState? = null,
    onTransitionIdleChanged: (Boolean) -> Unit = {},
    content: @Composable () -> Unit
) {
    val detailVisibilityState = remember { MutableTransitionState(false) }

    val isChangingTarget = detailVisibilityState.currentState != visible ||
        detailVisibilityState.targetState != visible
    if (detailVisibilityState.targetState != visible) {
        detailVisibilityState.targetState = visible
    }

    LaunchedEffect(visible) {
        if (isChangingTarget) {
            onTransitionIdleChanged(false)
        }
    }

    LaunchedEffect(detailVisibilityState.isIdle) {
        onTransitionIdleChanged(detailVisibilityState.isIdle)
    }

    AnimatedVisibility(
        visibleState = detailVisibilityState,
        enter = slideInVertically(initialOffsetY = { it }, animationSpec = tween(300)) + fadeIn(animationSpec = tween(300)),
        exit = slideOutVertically(targetOffsetY = { it }, animationSpec = tween(300)) + fadeOut(animationSpec = tween(300)),
        modifier = modifier
    ) {
        CompositionLocalProvider(
            LocalHomeRecent2DetailTargetScope provides this@AnimatedVisibility,
            LocalAnimatedVisibilityScope provides this@AnimatedVisibility
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
            ) {
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
                    content()
                }
            }
        }
    }
}
