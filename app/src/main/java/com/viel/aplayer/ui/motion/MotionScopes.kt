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
