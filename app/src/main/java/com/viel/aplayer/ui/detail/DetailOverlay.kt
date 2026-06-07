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
import androidx.compose.ui.Modifier
import com.viel.aplayer.data.store.GlassEffectMode
import com.viel.aplayer.ui.motion.LocalAnimatedVisibilityScope
import com.viel.aplayer.ui.motion.LocalHomeRecent2DetailTargetScope
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource

/**
 * Detail Overlay (Stateless animated overlay shell)
 *
 * Owns only overlay animation, shared-element scope publication, and haze source registration.
 * Route-level state and screen callbacks are injected through [content].
 */
@Composable
fun DetailOverlay(
    visible: Boolean,
    glassEffectMode: GlassEffectMode,
    modifier: Modifier = Modifier,
    hazeState: HazeState? = null,
    detailHazeState: HazeState? = null,
    content: @Composable () -> Unit
) {
    // Detail Overlay Animation (Keep transition policy separate from DetailRoute state collection)
    // A fixed 300ms duration preserves the existing enter/exit behavior while making this wrapper stateless.
    AnimatedVisibility(
        visible = visible,
        enter = slideInVertically(initialOffsetY = { it }, animationSpec = tween(300)) + fadeIn(animationSpec = tween(300)),
        exit = slideOutVertically(targetOffsetY = { it }, animationSpec = tween(300)) + fadeOut(animationSpec = tween(300)),
        modifier = modifier
    ) {
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
            // Detail App-Level Haze Source (Feed stable app chrome samplers with current Detail content)
            // MiniPlayerOverlay keeps a constant app-level HazeState to avoid rebinding flashes, so Detail registers into that same source while visible.
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
                // Detail page controls can keep local sampling, while dialogs and cross-page overlays route to the stable app-level HazeState.
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
                    content()
                }
            }
        }
    }
}
