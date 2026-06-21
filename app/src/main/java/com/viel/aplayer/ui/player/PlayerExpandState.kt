package com.viel.aplayer.ui.player

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Single source of truth for the mini <-> full player morph.
 *
 * [fraction] is the continuous expansion amount: 0f = mini (collapsed), 1f = full (expanded).
 * The whole player is one surface that is lerp-ed by this value, so cover, bounds, corner radius
 * and content alpha all move in lockstep — that is what produces the "one piece" feel.
 *
 * PERFORMANCE CONTRACT: read [fraction].value ONLY inside draw/layout-phase lambdas
 * (graphicsLayer {}, Modifier.layout {}, drawWithCache {}). Use [fractionProvider] to pass it
 * down as a deferred read. Reading it in a composable body would recompose the player subtree
 * every animation frame, which is exactly the cost this design avoids.
 */
@Stable
class PlayerExpandState(
    initial: Float,
    private val scope: CoroutineScope,
) {
    val fraction: Animatable<Float, *> = Animatable(initial)

    /** Deferred accessor: hand this to children so they read the value in their draw/layout phase. */
    fun fractionProvider(): () -> Float = { fraction.value }

    val isSettled: Boolean
        get() = !fraction.isRunning

    /** false only when fully collapsed. */
    val isExpanding: Boolean
        get() = fraction.value > COMPOSE_THRESHOLD

    fun animateToTarget(target: Float) {
        scope.launch {
            fraction.animateTo(
                targetValue = target,
                animationSpec = spring(
                    dampingRatio = SPRING_DAMPING,
                    stiffness = Spring.StiffnessMediumLow,
                ),
            )
        }
    }

    /** Finger-following drag step. [deltaFraction] is already expressed in fraction units (0..1). */
    suspend fun snapBy(deltaFraction: Float) {
        fraction.snapTo((fraction.value + deltaFraction).coerceIn(0f, 1f))
    }

    /** Decide the resting state on drag release from current position and fling velocity. */
    fun settle(velocityFractionPerSec: Float) {
        val target = when {
            velocityFractionPerSec > VELOCITY_THRESHOLD -> 1f
            velocityFractionPerSec < -VELOCITY_THRESHOLD -> 0f
            fraction.value >= 0.5f -> 1f
            else -> 0f
        }
        animateToTarget(target)
    }

    private companion object {
        const val SPRING_DAMPING = 0.9f
        const val VELOCITY_THRESHOLD = 1.2f
        const val COMPOSE_THRESHOLD = 0.001f
    }
}

/**
 * Creates and remembers a [PlayerExpandState] that follows [expanded]:
 * toggling the boolean springs the fraction toward 1f (full) or 0f (mini).
 * A drag gesture can drive [PlayerExpandState.snapBy]/[PlayerExpandState.settle] independently.
 */
@Composable
fun rememberPlayerExpandState(expanded: Boolean): PlayerExpandState {
    val scope = rememberCoroutineScope()
    val state = remember { PlayerExpandState(if (expanded) 1f else 0f, scope) }
    LaunchedEffect(expanded) {
        state.animateToTarget(if (expanded) 1f else 0f)
    }
    return state
}
