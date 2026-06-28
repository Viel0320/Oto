package com.viel.oto.ui.common.theme

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
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
import com.viel.oto.shared.model.GlassEffectMode
import com.viel.oto.ui.common.layout.LocalAppWindowSizeClass
import com.viel.oto.ui.common.layout.rememberAppWindowSizeClass

/**
 * Static dark fallback palette used when wallpaper dynamic color is disabled or unavailable.
 */
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
/**
 * Static light fallback palette used when wallpaper dynamic color is disabled or unavailable.
 */
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

val LocalDarkTheme = androidx.compose.runtime.staticCompositionLocalOf { false }

val LocalAmoled = androidx.compose.runtime.staticCompositionLocalOf { false }

val LocalGlassEffectMode = androidx.compose.runtime.staticCompositionLocalOf { GlassEffectMode.Material }

val LocalIsBlur = androidx.compose.runtime.staticCompositionLocalOf { false }

val LocalHazeState = androidx.compose.runtime.staticCompositionLocalOf<dev.chrisbanes.haze.HazeState?> { null }

/**
 * Applies the app-wide Material theme while keeping appearance mode ownership outside glass rendering.
 * Dynamic color derives from the wallpaper seed regardless of the active glass effect mode.
 */
@Composable
fun OtoTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    amoled: Boolean = false,
    glassEffectMode: GlassEffectMode = GlassEffectMode.Material,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val colorScheme = androidx.compose.runtime.remember(darkTheme, dynamicColor, amoled) {
        val fallback = if (darkTheme) DarkColorScheme else LightColorScheme
        val seed = if (dynamicColor) DynamicColorSchemeHelper.getWallpaperSeedColor(context) else null
        when {
            seed != null -> dynamicColorScheme(
                seedColor = seed,
                isDark = darkTheme,
                isAmoled = amoled,
                style = PaletteStyle.TonalSpot
            )
            amoled && darkTheme -> fallback.toAmoled()
            else -> fallback
        }
    }

    android.util.Log.d(
        "OtoTheme",
        "OtoTheme applied: darkTheme=$darkTheme, dynamicColor=$dynamicColor, systemSdk=${android.os.Build.VERSION.SDK_INT}, " +
                "primary=#${java.lang.Long.toHexString(colorScheme.primary.value.toLong())}, " +
                "background=#${java.lang.Long.toHexString(colorScheme.background.value.toLong())}"
    )
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val activity = view.context.findActivity()
            if (activity != null) {
                val window = activity.window
                WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
            }
        }
    }

    val windowClass = rememberAppWindowSizeClass()
    CompositionLocalProvider(
        LocalAppWindowSizeClass provides windowClass,
        LocalDarkTheme provides darkTheme,
        LocalAmoled provides amoled,
        LocalGlassEffectMode provides glassEffectMode,
        LocalIsBlur provides (glassEffectMode == GlassEffectMode.Haze)
    ) {
        OtoExpressiveThemeLayer(
            colorScheme = colorScheme,
            content = content
        )
    }
}

/**
 * Shared Expressive theme wrapper reused by all theme entry points (main + cover-seed locals).
 *
 * Inherits Expressive default shapes and typography; only colorScheme and motionScheme are explicit.
 */
@Composable
internal fun OtoExpressiveThemeLayer(
    colorScheme: ColorScheme,
    content: @Composable () -> Unit
) {
    MaterialExpressiveTheme(
        colorScheme = animateColorScheme(colorScheme),
        motionScheme = MotionScheme.expressive(),
        content = content
    )
}

/**
 * Recursive Context Traversal.
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
 * Force the static dark fallback's surfaces to black for OLED power saving.
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
