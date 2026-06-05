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

/*
 * Mini To Player Target Scope (Full-player target visibility boundary)
 * Carries only the PlayerOverlay visibility scope used by mini2player cover and bounds destinations.
 * This keeps full-player shared elements tied to the full-screen player transition instead of
 * inheriting unrelated route or detail overlay visibility contexts.
 */
val LocalMini2PlayerTargetScope = staticCompositionLocalOf<AnimatedVisibilityScope?> { null }
