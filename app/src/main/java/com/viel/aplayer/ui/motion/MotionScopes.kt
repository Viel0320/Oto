package com.viel.aplayer.ui.motion

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.runtime.staticCompositionLocalOf

/*
 * Shared Element Transition Context Scope (CompositionLocal scoping mechanism)
 * Provide static composition locals for both SharedTransitionScope and AnimatedVisibilityScope
 * to propagate transition parameters downward without cluttering intermediate function signatures.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
val LocalSharedTransitionScope = staticCompositionLocalOf<SharedTransitionScope?> { null }

/*
 * Animated Visibility Context Scope (CompositionLocal scoping mechanism)
 * Propagates the active AnimatedVisibilityScope down the hierarchy to components that require
 * synchronization with system exit/entry transition states.
 */
val LocalAnimatedVisibilityScope = staticCompositionLocalOf<AnimatedVisibilityScope?> { null }

/*
 * Home To Detail Source Scope (Recent-card source visibility boundary)
 * Carries only the Home route visibility scope used by home2DetailCover source covers.
 * Keeping this separate prevents Home recent-card artwork from accidentally using mini-player
 * or full-player overlay visibility states when those overlays are mounted as siblings.
 */
val LocalHomeRecent2DetailSourceScope = staticCompositionLocalOf<AnimatedVisibilityScope?> { null }

/*
 * Home To Detail Target Scope (Detail overlay target visibility boundary)
 * Carries only the DetailOverlay visibility scope used by home2DetailCover destination covers.
 * This isolates Detail cover motion from player overlay scopes that may appear above the detail layer.
 */
val LocalHomeRecent2DetailTargetScope = staticCompositionLocalOf<AnimatedVisibilityScope?> { null }

/*
 * Mini To Player Source Scope (Mini-player source visibility boundary)
 * Carries only the MiniPlayerOverlay visibility scope used by mini2player cover and bounds sources.
 * The source scope remains independent of Home and Detail transitions even when the mini-player
 * is displayed over those layers.
 */
val LocalMini2PlayerSourceScope = staticCompositionLocalOf<AnimatedVisibilityScope?> { null }

/**
 * Mini To Player Source Cover Snapshot (Mismatch bridge payload)
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

/*
 * Mini To Player Source Cover (Artwork bridge payload)
 * Carries the mini-player cover snapshot captured before expansion so the full-player target can
 * tell whether it is morphing from the same book and, when it is not, bridge through the source
 * thumbnail instead of hard-cutting to its own artwork.
 */
val LocalMini2PlayerSourceCover = staticCompositionLocalOf<Mini2PlayerSourceCover?> { null }

/*
 * Mini To Player Target Scope (Full-player target visibility boundary)
 * Carries only the PlayerOverlay visibility scope used by mini2player cover and bounds destinations.
 * This keeps full-player shared elements tied to the full-screen player transition instead of
 * inheriting unrelated route or detail overlay visibility contexts.
 */
val LocalMini2PlayerTargetScope = staticCompositionLocalOf<AnimatedVisibilityScope?> { null }
