package com.viel.aplayer.ui.common.layout

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.WindowHeightSizeClass
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp

/**
 * Standardised Window Size Class: Defines device size categories for responsive UI layouts.
 *
 * Encapsulates core layout calculation logic derived from the official Android WindowSizeClass API.
 * Tracks screen boundaries across varying device form factors (e.g., phones, foldables, tablets) and orientations
 * to dynamically determine columns, padding, and layout structures.
 */
@Immutable
data class AppWindowSizeClass(
    /**
     * Width Bracket: Standardized width tier (Compact, Medium, or Expanded).
     */
    val widthSizeClass: WindowWidthSizeClass,
    /**
     * Height Bracket: Standardized height tier (Compact, Medium, or Expanded).
     */
    val heightSizeClass: WindowHeightSizeClass,
    /**
     * Orientation Flag: True if device is in landscape mode.
     */
    val isLandscape: Boolean,
    /**
     * Logical Width: Screen width mapped to density-independent pixels.
     */
    val screenWidthDp: Dp,
    /**
     * Logical Height: Screen height mapped to density-independent pixels.
     */
    val screenHeightDp: Dp
) {
    /**
     * Large Screen Check: Verify if device has a tablet-class display.
     *
     * Refactored to exclude devices with Compact height size class (height < 480dp).
     * This ensures standard landscape phones with high width resolution do not wrongly fall into tablet layouts
     * where stacked columns or side pane layouts cause severe layout overlapping.
     */
    val isTablet: Boolean
        get() = widthSizeClass != WindowWidthSizeClass.Compact && heightSizeClass != WindowHeightSizeClass.Compact

    /**
     * Wide Layout Check: Determine if wide configuration should be used.
     *
     * True if the screen is either tablet-sized or in landscape orientation.
     * Used to trigger side-by-side split layouts or grid card structures.
     */
    val isWideScreen: Boolean
        get() = isTablet || isLandscape

    /**
     * Grid Column Calculator: Adjust grid columns dynamically for book catalog lists.
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
     * Responsive Side Padding: Differentiate margins for spacing layout.
     */
    val screenHorizontalPadding: Dp
        get() = 24.dp
    //if (isWideScreen or isLandscape) 24.dp else 16.dp

    /**
     * Tablet Landscape Flag: Verify if dual-pane layouts are active.
     *
     * Evaluates to true only on tablet devices in landscape orientation with sufficient vertical height,
     * allowing side-by-side control views without vertical overlap.
     */
    val isLandscapeTablet: Boolean
        get() = isLandscape && isTablet && heightSizeClass != WindowHeightSizeClass.Compact

    companion object {
        /**
         * Standard Preset: Portrait Phone view.
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
         * Standard Preset: Landscape Phone view.
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
         * Standard Preset: Landscape Tablet view.
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
 * Composition Local Key: Exposes the adaptive layout helper contextually.
 *
 * Allows Compose widgets to easily retrieve the active `AppWindowSizeClass` parameters with minimal coupling.
 */
val LocalAppWindowSizeClass: ProvidableCompositionLocal<AppWindowSizeClass> = staticCompositionLocalOf {
    AppWindowSizeClass.PortraitPhone
}

/**
 * Find Activity Host: Recursively traverses the Context wrapper chain to retrieve the host Activity.
 *
 * Prevents crashes inside Compose Previews or unconfigured environments where context is not a direct Activity.
 */
private tailrec fun Context.findActivity(): Activity? {
    if (this is Activity) return this
    return if (this is ContextWrapper) {
        baseContext.findActivity()
    } else {
        null
    }
}

/**
 * App Window Size Class Remembers: Computes window classes inside the Compose composition.
 *
 * Integrates with Jetpack Compose calculateWindowSizeClass API to natively support multi-window / split-screen bounds.
 * Falls back to LocalConfiguration and calculateFromSize during compose previews.
 */
@OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
@Composable
fun rememberAppWindowSizeClass(): AppWindowSizeClass {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    
    val widthSizeClass: WindowWidthSizeClass
    val heightSizeClass: WindowHeightSizeClass
    val widthDp: Dp
    val heightDp: Dp

    if (activity != null) {
        val windowSize = calculateWindowSizeClass(activity)
        widthSizeClass = windowSize.widthSizeClass
        heightSizeClass = windowSize.heightSizeClass
        widthDp = configuration.screenWidthDp.dp
        heightDp = configuration.screenHeightDp.dp
    } else {
        widthDp = configuration.screenWidthDp.dp
        heightDp = configuration.screenHeightDp.dp
        val size = DpSize(widthDp, heightDp)
        val windowSize = WindowSizeClass.calculateFromSize(size)
        widthSizeClass = windowSize.widthSizeClass
        heightSizeClass = windowSize.heightSizeClass
    }

    return remember(widthSizeClass, heightSizeClass, isLandscape, widthDp, heightDp) {
        AppWindowSizeClass(
            widthSizeClass = widthSizeClass,
            heightSizeClass = heightSizeClass,
            isLandscape = isLandscape,
            screenWidthDp = widthDp,
            screenHeightDp = heightDp
        )
    }
}
