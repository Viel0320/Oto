package com.viel.aplayer.ui.common.theme

import android.app.WallpaperManager
import android.content.Context
import androidx.compose.ui.graphics.Color

object DynamicColorSchemeHelper {

    /**
     * Extracts the primary color from the system wallpaper.
     * Returns null if no colors are returned by the system.
     */
    fun getWallpaperSeedColor(context: Context): Color? {
        try {
            val wallpaperManager = WallpaperManager.getInstance(context)
            val colors = wallpaperManager.getWallpaperColors(WallpaperManager.FLAG_SYSTEM)
            if (colors != null) {
                val primaryColor = colors.primaryColor
                return Color(primaryColor.toArgb())
            }
        } catch (e: Throwable) {
        }
        return null
    }
}
