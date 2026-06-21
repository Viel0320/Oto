package com.viel.aplayer.ui.motion

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.runtime.staticCompositionLocalOf

@OptIn(ExperimentalSharedTransitionApi::class)
val LocalSharedTransitionScope = staticCompositionLocalOf<SharedTransitionScope?> { null }

val LocalAnimatedVisibilityScope = staticCompositionLocalOf<AnimatedVisibilityScope?> { null }

val LocalHomeRecent2DetailSourceScope = staticCompositionLocalOf<AnimatedVisibilityScope?> { null }

val LocalHomeRecent2DetailTargetScope = staticCompositionLocalOf<AnimatedVisibilityScope?> { null }

val LocalMini2PlayerSourceScope = staticCompositionLocalOf<AnimatedVisibilityScope?> { null }

/**
 * Mismatch bridge payload.
 *
 * Captures the mini-player cover identity together with the exact thumbnail request inputs visible
 * before expansion. When the full-player target turns out to be a different book, the target replays
 * this thumbnail (same Coil cache key as the mini source) as a continuous bridge frame while the
 * shared element flies, then fades to its own original artwork after the transition settles.
 */
data class Mini2PlayerSourceCover(
    val bookId: String,
    val coverPath: String?,
    val coverLastUpdated: Long
)

val LocalMini2PlayerSourceCover = staticCompositionLocalOf<Mini2PlayerSourceCover?> { null }

val LocalMini2PlayerTargetScope = staticCompositionLocalOf<AnimatedVisibilityScope?> { null }
