package com.viel.aplayer.ui.common.theme

import android.app.WallpaperManager
import android.content.Context
import androidx.compose.ui.graphics.Color

// =====================================================================
// Wallpaper Seed Extraction
// Material 3 ColorScheme generation now lives in the MaterialKolor library
// (com.materialkolor.dynamicColorScheme), which implements Google's
// material-color-utilities (HCT) algorithm and supersedes the previous
// in-house HSL approximation. This helper only sources the system
// wallpaper's primary color to use as a generation seed.
// =====================================================================

object DynamicColorSchemeHelper {

    /**
     * Extracts the primary color from the system wallpaper.
     * Returns null if no colors are returned by the system.
     */
    // Accesses WallpaperManager.getWallpaperColors on Android 8.0+ to get the primary color without storage permissions.
    fun getWallpaperSeedColor(context: Context): Color? {
        try {
            val wallpaperManager = WallpaperManager.getInstance(context)
            val colors = wallpaperManager.getWallpaperColors(WallpaperManager.FLAG_SYSTEM)
            if (colors != null) {
                val primaryColor = colors.primaryColor
                return Color(primaryColor.toArgb())
            }
        } catch (e: Throwable) {
            // Fail-safe fallbacks: Suppress unexpected wallpaper manager crashes on custom ROMs or Unsupported Service in Preview
        }
        return null
    }
}
