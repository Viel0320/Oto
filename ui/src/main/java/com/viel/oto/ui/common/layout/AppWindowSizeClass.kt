package com.viel.oto.ui.common.layout

import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.WindowHeightSizeClass
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp

/**
 * Defines device size categories for responsive UI layouts.
 *
 * Wraps Android WindowSizeClass tiers with app-specific orientation and dimension facts used by
 * catalog columns, screen padding, and split-layout decisions.
 */
@Immutable
data class AppWindowSizeClass(
    /**
     * Standard width tier reported by the Android WindowSizeClass API.
     */
    val widthSizeClass: WindowWidthSizeClass,
    /**
     * Standard height tier reported by the Android WindowSizeClass API.
     */
    val heightSizeClass: WindowHeightSizeClass,
    /**
     * True when the current window container is wider than it is tall.
     */
    val isLandscape: Boolean,
    /**
     * Current screen width in density-independent pixels.
     */
    val screenWidthDp: Dp,
    /**
     * Current screen height in density-independent pixels.
     */
    val screenHeightDp: Dp
) {
    /**
     * Treats a device as tablet-class only when both width and height leave room for expanded layouts.
     *
     * Landscape phones can have a Medium or Expanded width while still having Compact height, so height
     * is part of the check to avoid overlapping tablet panes on short displays.
     */
    val isTablet: Boolean
        get() = widthSizeClass != WindowWidthSizeClass.Compact && heightSizeClass != WindowHeightSizeClass.Compact

    /**
     * Determines whether wide layouts such as split panes or multi-column grids can be used.
     *
     * True if the screen is either tablet-sized or in landscape orientation.
     * Used to trigger side-by-side split layouts or grid card structures.
     */
    val isWideScreen: Boolean
        get() = isTablet || isLandscape

    /**
     * Calculates catalog grid columns from tablet and landscape breakpoints.
     *
     * Calculations base:
     * 1. Renders 3 columns if the device is an expanded landscape tablet (width >= 840dp).
     * 2. Renders 2 columns for other wide screens (e.g., standard phone in landscape or portrait tablet).
     * 3. Falls back to 1 column for standard vertical phone viewports.
     */
    val columnsCount: Int
        get() = when {
            isTablet && widthSizeClass == WindowWidthSizeClass.Expanded -> 3
            isWideScreen -> 2
            else -> 1
        }

    /**
     * Shared horizontal screen padding used by app-level layouts.
     */
    val screenHorizontalPadding: Dp
        get() = 24.dp

    /**
     * True when dual-pane tablet layouts have enough horizontal and vertical room.
     *
     * Evaluates to true only on tablet devices in landscape orientation with sufficient vertical height,
     * allowing side-by-side control views without vertical overlap.
     */
    val isLandscapeTablet: Boolean
        get() = isLandscape && isTablet && heightSizeClass != WindowHeightSizeClass.Compact

    companion object {
        /**
         * Preview preset for a portrait phone viewport.
         *
         * Configured with Compact width (360dp), Medium height (800dp), and vertical orientation.
         */
        val PortraitPhone = AppWindowSizeClass(
            widthSizeClass = WindowWidthSizeClass.Compact,
            heightSizeClass = WindowHeightSizeClass.Medium,
            isLandscape = false,
            screenWidthDp = 360.dp,
            screenHeightDp = 800.dp
        )

        /**
         * Preview preset for a landscape phone viewport.
         *
         * Configured with Medium width (720dp), Compact height (360dp), and horizontal orientation.
         */
        val LandscapePhone = AppWindowSizeClass(
            widthSizeClass = WindowWidthSizeClass.Medium,
            heightSizeClass = WindowHeightSizeClass.Compact,
            isLandscape = true,
            screenWidthDp = 720.dp,
            screenHeightDp = 360.dp
        )

        /**
         * Preview preset for a landscape tablet viewport.
         *
         * Configured with Expanded width (1280dp), Medium height (800dp), and horizontal orientation.
         */
        val LandscapeTablet = AppWindowSizeClass(
            widthSizeClass = WindowWidthSizeClass.Expanded,
            heightSizeClass = WindowHeightSizeClass.Medium,
            isLandscape = true,
            screenWidthDp = 1280.dp,
            screenHeightDp = 800.dp
        )
    }
}

/**
 * Exposes the active adaptive layout helper through composition.
 *
 * Allows Compose widgets to easily retrieve the active `AppWindowSizeClass` parameters with minimal coupling.
 */
val LocalAppWindowSizeClass: ProvidableCompositionLocal<AppWindowSizeClass> = staticCompositionLocalOf {
    AppWindowSizeClass.PortraitPhone
}

/**
 * Computes the app window class inside the Compose composition.
 *
 * Uses the Compose window container size so adaptive decisions track the actual composition bounds instead of
 * Android configuration values that can be rounded or include different inset behavior across target SDK levels.
 */
@OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
@Composable
fun rememberAppWindowSizeClass(): AppWindowSizeClass {
    val density = LocalDensity.current
    val containerSize = LocalWindowInfo.current.containerSize
    val windowDpSize = remember(containerSize, density) {
        with(density) {
            DpSize(
                width = containerSize.width.toDp(),
                height = containerSize.height.toDp()
            )
        }
    }
    val windowSize = remember(windowDpSize) { WindowSizeClass.calculateFromSize(windowDpSize) }
    val widthDp = windowDpSize.width
    val heightDp = windowDpSize.height
    val isLandscape = containerSize.width > containerSize.height

    return remember(windowSize.widthSizeClass, windowSize.heightSizeClass, isLandscape, widthDp, heightDp) {
        AppWindowSizeClass(
            widthSizeClass = windowSize.widthSizeClass,
            heightSizeClass = windowSize.heightSizeClass,
            isLandscape = isLandscape,
            screenWidthDp = widthDp,
            screenHeightDp = heightDp
        )
    }
}
