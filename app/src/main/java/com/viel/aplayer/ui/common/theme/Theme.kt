package com.viel.aplayer.ui.common.theme

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.materialkolor.PaletteStyle
import com.materialkolor.dynamicColorScheme
import com.materialkolor.ktx.animateColorScheme
// Import Window Size Class: Reference standard layout classes from the new layout package to support robust screen adaptation
import com.viel.aplayer.ui.common.layout.LocalAppWindowSizeClass
import com.viel.aplayer.ui.common.layout.rememberAppWindowSizeClass


// =====================================================================
// Complete DarkColorScheme (M-21 Fix)
// Filled in secondary, tertiary, error, and container roles when dynamicColor = false.
// =====================================================================
private val DarkColorScheme = darkColorScheme(
    primary = PrimaryDark,
    onPrimary = OnPrimaryDark,
    primaryContainer = PrimaryContainerDark,
    onPrimaryContainer = OnPrimaryContainerDark,
    secondary = SecondaryDark,
    onSecondary = OnSecondaryDark,
    secondaryContainer = SecondaryContainerDark,
    onSecondaryContainer = OnSecondaryContainerDark,
    tertiary = TertiaryDark,
    onTertiary = OnTertiaryDark,
    tertiaryContainer = TertiaryContainerDark,
    onTertiaryContainer = OnTertiaryContainerDark,
    error = ErrorDark,
    onError = OnErrorDark,
    errorContainer = ErrorContainerDark,
    onErrorContainer = OnErrorContainerDark,
    background = SurfaceDark,
    surface = SurfaceDark,
    surfaceVariant = SurfaceContainerDark,
    onBackground = OnSurfaceDark,
    onSurface = OnSurfaceDark,
    onSurfaceVariant = OnSurfaceVariantDark,
    outline = OutlineDark
)

// =====================================================================
// Complete LightColorScheme (M-21 Fix)
// Filled in secondary, tertiary, error, and container roles when dynamicColor = false.
// =====================================================================
private val LightColorScheme = lightColorScheme(
    primary = PrimaryLight,
    onPrimary = OnPrimaryLight,
    primaryContainer = PrimaryContainerLight,
    onPrimaryContainer = OnPrimaryContainerLight,
    secondary = SecondaryLight,
    onSecondary = OnSecondaryLight,
    secondaryContainer = SecondaryContainerLight,
    onSecondaryContainer = OnSecondaryContainerLight,
    tertiary = TertiaryLight,
    onTertiary = OnTertiaryLight,
    tertiaryContainer = TertiaryContainerLight,
    onTertiaryContainer = OnTertiaryContainerLight,
    error = ErrorLight,
    onError = OnErrorLight,
    errorContainer = ErrorContainerLight,
    onErrorContainer = OnErrorContainerLight,
    background = SurfaceLight,
    surface = SurfaceLight,
    surfaceVariant = SurfaceVariantLight,
    onBackground = OnSurfaceLight,
    onSurface = OnSurfaceLight,
    onSurfaceVariant = OnSurfaceVariantLight,
    outline = OutlineLight
)

// Dark Theme Context (CompositionLocal to expose active dark mode state globally) StaticCompositionLocalOf holds boolean state.
val LocalDarkTheme = androidx.compose.runtime.staticCompositionLocalOf { false }

// AMOLED Context (CompositionLocal to expose the pure-black dark mode preference to cover-seeded themes)
// Cover/detail/edit screens read this to pass isAmoled into their own MaterialKolor scheme generation.
val LocalAmoled = androidx.compose.runtime.staticCompositionLocalOf { false }

// Haze State Context: CompositionLocal to expose global hazeState for blur overlays.
// Details: Declare LocalHazeState static CompositionLocal to hold the top-level HazeState reference.
val LocalHazeState = androidx.compose.runtime.staticCompositionLocalOf<dev.chrisbanes.haze.HazeState?> { null }

/**
 * Applies the app-wide Material theme while keeping appearance mode ownership outside glass rendering.
 * Dynamic color derives from the wallpaper seed regardless of the active glass effect mode.
 */
@Composable
fun APlayerTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // Dynamic color is available on Android 12+
    dynamicColor: Boolean = true,
    // AMOLED dark theme: forces pure-black surfaces in dark mode (OLED power saving). No-op in light mode.
    amoled: Boolean = false,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    // Resolve Color Scheme (Retrieve either system-derived wallpaper scheme or static fallback schemes)
    // Dynamically queries wallpaper seed color on API 26+ and constructs custom ColorScheme via HSL space scaling, falling back to static themes on failure.
    val colorScheme = androidx.compose.runtime.remember(darkTheme, dynamicColor, amoled) {
        val fallback = if (darkTheme) DarkColorScheme else LightColorScheme
        val seed = if (dynamicColor) DynamicColorSchemeHelper.getWallpaperSeedColor(context) else null
        when {
            // Wallpaper-seeded app theme uses TonalSpot, matching Android's own Material You default.
            seed != null -> dynamicColorScheme(
                seedColor = seed,
                isDark = darkTheme,
                isAmoled = amoled,
                style = PaletteStyle.TonalSpot
            )
            // No dynamic seed: force pure-black surfaces on the static dark fallback when AMOLED is on.
            amoled && darkTheme -> fallback.toAmoled()
            else -> fallback
        }
    }

    // Theme Color Diagnostics (Log active theme properties and extracted key colors for debugging)
    // Logs the darkTheme, dynamicColor states, and hex values of the primary and background colors to help diagnose Monet dynamic tinting issues.
    android.util.Log.d(
        "APlayerTheme",
        "APlayerTheme applied: darkTheme=$darkTheme, dynamicColor=$dynamicColor, systemSdk=${android.os.Build.VERSION.SDK_INT}, " +
                "primary=#${java.lang.Long.toHexString(colorScheme.primary.value.toLong())}, " +
                "background=#${java.lang.Long.toHexString(colorScheme.background.value.toLong())}"
    )
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            // Get StatusBar Window Control (Safe Activity Host Retrieval)
            // Use findActivity to recursively retrieve the Activity host to get status bar Window control.
            // This avoids crash risks caused by casting view.context to Activity in Compose Preview, Glance app widget, or minor embedded views where it is not an Activity.
            val activity = view.context.findActivity()
            if (activity != null) {
                val window = activity.window
                WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
            }
        }
    }

    // WindowClass Adaptation Setup (Adaptive Setup & Sharing)
    // Perceive changes in current window/physical screen configuration and pixel size, and adaptively derive corresponding AppWindowSizeClass instance.
    // Use CompositionLocalProvider to provide this instance as a global LocalAppWindowSizeClass, allowing all sub-Composables under the UI tree and all Compose Previews to share adaptive logic seamlessly.
    val windowClass = rememberAppWindowSizeClass()
    CompositionLocalProvider(
        LocalAppWindowSizeClass provides windowClass,
        LocalDarkTheme provides darkTheme,
        LocalAmoled provides amoled
    ) {
        MaterialTheme(
            // Full-screen dynamic tint: animate the whole-app scheme so wallpaper/seed and light/dark changes transition smoothly instead of snapping.
            colorScheme = animateColorScheme(colorScheme),
            typography = Typography,
            content = content
        )
    }
}

/**
 * Find Activity Host (Recursive Context Traversal)
 *
 * Recursively search up the ContextWrapper wrapper chain until the true host Activity instance is found. Returns null if not found.
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
 * Pure-Black AMOLED Transform (Force the static dark fallback's surfaces to black for OLED power saving)
 *
 * Only used for the static dark fallback; MaterialKolor handles AMOLED directly for wallpaper/cover-seeded schemes.
 */
private fun ColorScheme.toAmoled(): ColorScheme = copy(
    background = Color.Black,
    surface = Color.Black,
    surfaceContainerLowest = Color.Black,
    surfaceContainerLow = Color(0xFF0A0A0A),
    surfaceContainer = Color(0xFF101010),
    surfaceContainerHigh = Color(0xFF161616),
    surfaceContainerHighest = Color(0xFF1C1C1C),
    surfaceDim = Color.Black
)
