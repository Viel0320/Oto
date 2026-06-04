package com.viel.aplayer.ui.common.theme

import android.content.res.Configuration
import androidx.compose.material3.windowsizeclass.WindowHeightSizeClass
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Window Class Spec: Defines device size categories for responsive UI layouts.
 *
 * Encapsulates core layout calculation logic derived from the Android WindowSizeClass API.
 * Tracks screen boundaries across varying device form factors (e.g., phones, foldables, tablets) and orientations
 * to dynamically determine columns, padding, and layout structures, eliminating hardcoded checks.
 */
@Immutable
data class WindowClass(
    /**
     * Width Bracket (Standardized width tier: Compact, Medium, or Expanded)
     */
    val widthSizeClass: WindowWidthSizeClass,
    /**
     * Height Bracket (Standardized height tier: Compact, Medium, or Expanded)
     */
    val heightSizeClass: WindowHeightSizeClass,
    /**
     * Orientation Flag (True if device is in landscape mode)
     */
    val isLandscape: Boolean,
    /**
     * Logical Width (Physical screen width mapped to density-independent pixels)
     */
    val screenWidthDp: Dp,
    /**
     * Logical Height (Physical screen height mapped to density-independent pixels)
     */
    val screenHeightDp: Dp
) {
    /**
     * Large Screen Check (Verify if device has a tablet-class display)
     *
     * Returns true if width size class is Medium (>= 600dp) or Expanded (>= 840dp),
     * matching the official guidelines for tablets and foldable devices.
     */
    val isTablet: Boolean
        get() = widthSizeClass != WindowWidthSizeClass.Compact

    /**
     * Wide Layout Check (Determine if wide configuration should be used)
     *
     * True if the screen is either tablet-sized or a standard phone in landscape orientation.
     * Used to trigger side-by-side split layouts or grid card structures.
     */
    val isWideScreen: Boolean
        get() = isTablet || isLandscape

    /**
     * Grid Column Calculator (Adjust grid columns dynamically for book catalog lists)
     *
     * Calculations base:
     * 1. Renders 3 columns if the device is a tablet and width is Expanded (>= 840dp, e.g., landscape tablet).
     * 2. Renders 2 columns for other wide screens (e.g., standard phone in landscape or portrait tablet).
     * 3. Falls back to 1 column for standard vertical phone viewport (Compact width).
     */
    val columnsCount: Int
        get() = when {
            isTablet && widthSizeClass == WindowWidthSizeClass.Expanded -> 3
            isWideScreen -> 2
            else -> 1
        }

    /**
     * Responsive Side Padding (Differentiate margins for spacing layout)
     *
     * Returns 24.dp for wide screens and 16.dp for compact viewports, optimizing visual breathing room.
     */
    val screenHorizontalPadding: Dp
        get() = if (isWideScreen) 24.dp else 16.dp

    /**
     * Tablet Landscape Flag (Verify if dual-pane layouts are active)
     *
     * Evaluates to true only on tablet devices in landscape orientation with sufficient vertical height,
     * allowing side-by-side control views without vertical overlap.
     */
    // Fix Tablet Landscape Layout Classification (Exclude compact vertical screen size class)
    // Exclude devices with Compact height size class (height < 480dp) from tablet landscape category.
    // This ensures standard landscape phones with high width resolution do not wrongly fall into tablet layouts
    // where left-column stacking causes severe overlap.
    val isTabletLandscape: Boolean
        get() = isTablet && isLandscape && heightSizeClass != WindowHeightSizeClass.Compact

    companion object {
        /**
         * Standard Preset: Portrait Phone view.
         *
         * Configured with Compact width (360dp), Medium height (800dp), and vertical orientation.
         */
        val PortraitPhone = WindowClass(
            widthSizeClass = WindowWidthSizeClass.Compact,
            heightSizeClass = WindowHeightSizeClass.Medium,
            isLandscape = false,
            screenWidthDp = 360.dp,
            screenHeightDp = 800.dp
        )

        /**
         * Standard Preset: Landscape Phone view.
         *
         * Configured with Medium width (720dp), Compact height (360dp), and horizontal orientation.
         */
        val LandscapePhone = WindowClass(
            widthSizeClass = WindowWidthSizeClass.Medium,
            heightSizeClass = WindowHeightSizeClass.Compact,
            isLandscape = true,
            screenWidthDp = 720.dp,
            screenHeightDp = 360.dp
        )

        /**
         * Standard Preset: Landscape Tablet view.
         *
         * Configured with Expanded width (1280dp), Medium height (800dp), and horizontal orientation.
         */
        val TabletLandscape = WindowClass(
            widthSizeClass = WindowWidthSizeClass.Expanded,
            heightSizeClass = WindowHeightSizeClass.Medium,
            isLandscape = true,
            screenWidthDp = 1280.dp,
            screenHeightDp = 800.dp
        )
    }
}

/**
 * Composition Local Key: Exposes the adaptive layout helper contextually.
 *
 * Allows Compose widgets to easily retrieve the active `WindowClass` parameters with minimal coupling.
 */
val LocalWindowClass: ProvidableCompositionLocal<WindowClass> = staticCompositionLocalOf {
    // Safe Fallback Default (Prevent preview layout crashes)
    // Defaults to `PortraitPhone` to prevent potential NullPointerExceptions inside Compose Previews or unconfigured scopes.
    WindowClass.PortraitPhone
}

/**
 * Window Class Remembers: Computes window classes inside the Compose composition.
 *
 * Observes physical screen dimension and orientation transitions.
 * Matches standard sizing tiers:
 * - Width: Compact (<600dp), Medium (<840dp), Expanded (>=840dp).
 * - Height: Compact (<480dp), Medium (<900dp), Expanded (>=900dp).
 */
@Composable
fun rememberWindowClass(): WindowClass {
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val widthDp = configuration.screenWidthDp
    val heightDp = configuration.screenHeightDp

    val widthSizeClass = when {
        widthDp < 600 -> WindowWidthSizeClass.Compact
        widthDp < 840 -> WindowWidthSizeClass.Medium
        else -> WindowWidthSizeClass.Expanded
    }

    val heightSizeClass = when {
        heightDp < 480 -> WindowHeightSizeClass.Compact
        heightDp < 900 -> WindowHeightSizeClass.Medium
        else -> WindowHeightSizeClass.Expanded
    }

    return remember(widthSizeClass, heightSizeClass, isLandscape, widthDp, heightDp) {
        WindowClass(
            widthSizeClass = widthSizeClass,
            heightSizeClass = heightSizeClass,
            isLandscape = isLandscape,
            screenWidthDp = widthDp.dp,
            screenHeightDp = heightDp.dp
        )
    }
}
