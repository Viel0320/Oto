package com.viel.aplayer.ui.common

// Setup Haze Integration (Import dev.chrisbanes.haze libraries) Import HazeState and haze modifier for Compose-based blur.
// Import Canvas and Blur APIs (Allow drawing blurred cover images natively)
// Import Compose Canvas, draw.blur, geometry.Size, and Coil's rememberAsyncImagePainter to replace AsyncImage with Canvas-based blur.
import android.graphics.Bitmap
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImagePainter
import coil.compose.rememberAsyncImagePainter
import com.viel.aplayer.data.store.GlassEffectMode
import com.viel.aplayer.media.parser.ImageProcessor
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource

/**
 * Globally shared background cover strong blur ambience component, applicable to both playback and details pages.
 * 1. Automatically handles smooth color animations of the background dominant color.
 * 2. Mounts the Haze state modifier in Haze mode and renders a 64.dp strong-blurred cover.
 * 3. Produces the page-level cover color from the same software backdrop bitmap used by the background.
 */
@Composable
fun CoverBackground(
    coverPath: String?,
    lastUpdated: Long,
    coverColor: Color?,
    glassEffectMode: GlassEffectMode,
    hazeState: HazeState?,
    modifier: Modifier = Modifier,
    onColorExtracted: (Color) -> Unit = {}
) {
    // Setup Glass Effect Flag (Check active theme style) Check if Haze mode is configured.
    val isBlur = glassEffectMode == GlassEffectMode.Haze
    val bgColor = MaterialTheme.colorScheme.background

    val fallbackColor = MaterialTheme.colorScheme.primaryContainer
    val finalColor = coverColor ?: fallbackColor

    // Smoothly transition the background dominant color to ensure a seamless visual connection when switching books.
    val animatedBgColor by animateColorAsState(
        targetValue = finalColor,
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

    val context = LocalContext.current
    val backdropPainter = if (coverPath != null) {
        val bgRequest = remember(coverPath, lastUpdated) {
            CoverImageRequestFactory.build(
                context = context,
                sourcePath = coverPath,
                lastUpdated = lastUpdated,
                variant = CoverImageVariant.Backdrop,
                scene = "cover-backdrop",
                allowHardware = false,
                bitmapConfig = Bitmap.Config.RGB_565
            )
        }
        rememberAsyncImagePainter(model = bgRequest)
    } else {
        null
    }

    /**
     * Extracts the page-level cover seed from the already software-decoded backdrop image.
     * This keeps foreground cover components display-only and centralizes color invalidation here.
     */
    LaunchedEffect(coverPath, lastUpdated, backdropPainter?.state) {
        val successState = backdropPainter?.state as? AsyncImagePainter.State.Success ?: return@LaunchedEffect
        val colorInt = ImageProcessor.getDominantColor(successState.result.drawable)
        ImageProcessor.putColorToCache(coverPath, lastUpdated, colorInt)
        onColorExtracted(Color(colorInt))
    }

    // Setup Layout Modifier (Apply haze to background Box) Link haze modifier to background container if in Haze mode.
    Box(
        modifier = modifier
            .fillMaxSize()
            .then(if (!isBlur) Modifier.background(backgroundBrush) else Modifier)
            .then(if (isBlur && hazeState != null) Modifier.hazeSource(hazeState) else Modifier)
    ) {
        // Render the full-screen cover blurred background only when in Haze mode.
        if (isBlur && backdropPainter != null) {
            // Render Canvas-based Blur Background (Draw cover on canvas and apply native blur modifier)
            // Use rememberAsyncImagePainter to load cover and render it via Canvas with Modifier.blur to implement hardware-accelerated cover background blur.
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = 1.05f
                        scaleY = 1.05f
                    }
                    .blur(64.dp)
            ) {
                val canvasSize = size
                val painterSize = backdropPainter.intrinsicSize
                if (painterSize.width.isFinite() && painterSize.width > 0 && 
                    painterSize.height.isFinite() && painterSize.height > 0) {
                    val srcRatio = painterSize.width / painterSize.height
                    val dstRatio = canvasSize.width / canvasSize.height
                    val scale = if (srcRatio > dstRatio) {
                        canvasSize.height / painterSize.height
                    } else {
                        canvasSize.width / painterSize.width
                    }
                    val drawWidth = painterSize.width * scale
                    val drawHeight = painterSize.height * scale
                    val dx = (canvasSize.width - drawWidth) / 2f
                    val dy = (canvasSize.height - drawHeight) / 2f

                    translate(left = dx, top = dy) {
                        with(backdropPainter) {
                            draw(size = Size(drawWidth, drawHeight))
                        }
                    }
                } else {
                    with(backdropPainter) {
                        draw(size = canvasSize)
                    }
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(bgColor.copy(alpha = 0.5f))
            )
        }
    }
}
