package com.viel.aplayer.ui.common

// Setup Haze Integration (Import dev.chrisbanes.haze libraries) Import HazeState and haze modifier for Compose-based blur.
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import com.viel.aplayer.ui.common.theme.LocalDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil.compose.AsyncImage
import com.viel.aplayer.data.store.GlassEffectMode
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource

/**
 * Globally shared background cover strong blur ambience component, applicable to both playback and details pages.
 * 1. Automatically handles smooth color animations of the background dominant color.
 * 2. Mounts the Haze state modifier in Haze mode and renders a 64.dp strong-blurred cover.
 * 3. Automatically adapts light and dark theme masks to ensure visibility of foreground UI elements.
 */
@Composable
fun CoverBackground(
    coverPath: String?,
    lastUpdated: Long,
    backgroundColorArgb: Int,
    glassEffectMode: GlassEffectMode,
    // Setup HazeState Parameter (Map backdrop from LayerBackdrop to HazeState) Changed backdrop to hazeState.
    hazeState: HazeState?,
    modifier: Modifier = Modifier
) {
    // Setup Glass Effect Flag (Check active theme style) Check if Haze mode is configured.
    val isBlur = glassEffectMode == GlassEffectMode.Haze
    // Theme Aware Contrast Check (Query active theme state via LocalDarkTheme instead of system defaults) Use LocalDarkTheme.current.
    val isDark = LocalDarkTheme.current
    val bgColor = MaterialTheme.colorScheme.background

    // Smoothly transition the background dominant color to ensure a seamless visual connection when switching books.
    val animatedBgColor by animateColorAsState(
        targetValue = Color(backgroundColorArgb),
        animationSpec = tween(300),
        label = "bg_color"
    )

    // Calculate the background gradient brush when frosted glass mode is disabled.
    val backgroundBrush = remember(animatedBgColor, bgColor) {
        Brush.verticalGradient(
            colors = listOf(
                animatedBgColor.copy(alpha = 0.9f),
                bgColor.copy(alpha = 0.95f)
            )
        )
    }

    // Setup Layout Modifier (Apply haze to background Box) Link haze modifier to background container if in Haze mode.
    Box(
        modifier = modifier
            .fillMaxSize()
            .then(if (!isBlur) Modifier.background(backgroundBrush) else Modifier)
            .then(if (isBlur && hazeState != null) Modifier.hazeSource(hazeState) else Modifier)
    ) {
        // Render the full-screen cover blurred background only when in Haze mode.
        if (isBlur && coverPath != null) {
            val context = LocalContext.current
            val bgRequest = remember(coverPath, lastUpdated) {
                CoverImageRequestFactory.build(
                    context = context,
                    sourcePath = coverPath,
                    lastUpdated = lastUpdated,
                    variant = CoverImageVariant.Backdrop,
                    scene = "cover-backdrop",
                    allowHardware = false
                )
            }

            AsyncImage(
                model = bgRequest,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                // Render Clear Background (Do not pre-blur image under Haze)
                // Let the background image render clearly. Haze will dynamically blur it using hazeChild on the upper layer.
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = 1.02f
                        scaleY = 1.02f
                    }
            )

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(bgColor.copy(alpha = if (isDark) 0.46f else 0.24f))
            )
            // Overlay an adaptive theme mask layer.
//            Box(
//                modifier = Modifier
//                    .fillMaxSize()
//                    .background(bgColor.copy(alpha = if (isDark) 0.62f else 0.74f))
//            )

            // Bottom gradient deepening layer.
//            Box(
//                modifier = Modifier
//                    .fillMaxSize()
//                    .background(
//                        Brush.verticalGradient(
//                            colors = listOf(
//                                Color.Transparent,
//                                bgColor.copy(alpha = if (isDark) 0.46f else 0.34f)
//                            )
//                        )
//                    )
//            )
        }
    }
}
