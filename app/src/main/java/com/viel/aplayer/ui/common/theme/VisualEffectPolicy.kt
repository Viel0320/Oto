package com.viel.aplayer.ui.common.theme

import android.app.ActivityManager
import android.content.Context
import android.os.PowerManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.viel.aplayer.shared.settings.GlassEffectMode

/**
 * Visual Effect Environment (Captures runtime capability signals for expensive UI effects)
 *
 * Low-RAM devices and system power-save mode are treated as production constraints because Haze blur,
 * full-screen artwork sampling, and animated glass controls compete directly with playback stability.
 */
@Immutable
data class VisualEffectEnvironment(
    val isLowRamDevice: Boolean,
    val isPowerSaveMode: Boolean
)

/**
 * Visual Effect Policy (Resolves requested decoration settings into executable UI modes)
 *
 * The persisted user preference remains unchanged, while the runtime mode can degrade to Material when
 * the current device is unlikely to handle continuous blur effects smoothly.
 */
object VisualEffectPolicy {
    fun resolveGlassEffectMode(
        requestedMode: GlassEffectMode,
        environment: VisualEffectEnvironment
    ): GlassEffectMode =
        if (requestedMode == GlassEffectMode.Haze && environment.shouldDisableHaze) {
            GlassEffectMode.Material
        } else {
            requestedMode
        }

    private val VisualEffectEnvironment.shouldDisableHaze: Boolean
        get() = isLowRamDevice || isPowerSaveMode
}

/**
 * Remember Visual Effect Environment (Reads Android runtime capability flags once per app shell)
 *
 * These flags change rarely; resolving them at the top-level shell gives every screen a single consistent
 * answer without coupling individual composables to ActivityManager or PowerManager.
 */
@Composable
fun rememberVisualEffectEnvironment(): VisualEffectEnvironment {
    val context = LocalContext.current.applicationContext
    return remember(context) {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        VisualEffectEnvironment(
            isLowRamDevice = activityManager?.isLowRamDevice == true,
            isPowerSaveMode = powerManager?.isPowerSaveMode == true
        )
    }
}
