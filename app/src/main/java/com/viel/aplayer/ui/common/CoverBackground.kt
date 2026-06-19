package com.viel.aplayer.ui.common

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
import com.viel.aplayer.media.parser.ImageProcessor
import com.viel.aplayer.shared.settings.GlassEffectMode
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource

/**
 * Shared cover-backed ambience for playback, detail, and edit surfaces.
 *
 * The component owns the page color animation, optional Haze source registration, software cover
 * decoding for blurred ambience, and dominant-color extraction from the currently requested cover.
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
    val isBlur = glassEffectMode == GlassEffectMode.Haze
    val bgColor = MaterialTheme.colorScheme.background

    val fallbackColor = MaterialTheme.colorScheme.primaryContainer
    val finalColor = coverColor ?: fallbackColor

    val animatedBgColor by animateColorAsState(
        targetValue = finalColor,
        animationSpec = tween(300),
        label = "bg_color"
    )

    val backgroundBrush = remember(animatedBgColor, bgColor) {
        Brush.verticalGradient(
            colors = listOf(
                animatedBgColor.copy(alpha = 0.9f),
                bgColor.copy(alpha = 0.95f)
            )
        )
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .then(if (!isBlur) Modifier.background(backgroundBrush) else Modifier)
            .then(if (isBlur && hazeState != null) Modifier.hazeSource(hazeState) else Modifier)
    ) {
        /*
         * Blurred Backdrop Rendering (Draw the current decorative bitmap directly)
         *
         * The Haze source and page gradient remain stable on the parent Box, while the active
         * backdrop request owns its painter, color extraction, blur canvas, and darkening mask.
         */
        if (isBlur) {
            CoverBackgroundBackdropLayer(
                coverPath = coverPath,
                lastUpdated = lastUpdated,
                modifier = Modifier.fillMaxSize(),
                onColorExtracted = onColorExtracted
            )

            if (coverPath != null) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(bgColor.copy(alpha = 0.5f))
                )
            }
        }
    }
}

/**
 * Render the active loaded backdrop layer.
 *
 * The layer reports the dominant color only after Coil has produced a successful drawable, keeping
 * failed or empty requests from overwriting the page-level cover color.
 */
@Composable
private fun CoverBackgroundBackdropLayer(
    coverPath: String?,
    lastUpdated: Long,
    modifier: Modifier,
    onColorExtracted: (Color) -> Unit
) {
    val resolvedCoverPath = coverPath ?: return
    val backdropPainter = rememberCoverBackdropPainter(
        coverPath = resolvedCoverPath,
        lastUpdated = lastUpdated
    )

    /**
     * Extracts the page-level cover seed from the active software-decoded backdrop image.
     * This keeps foreground cover components display-only and prevents failed requests from
     * writing stale colors into the cover cache.
     */
    LaunchedEffect(resolvedCoverPath, lastUpdated, backdropPainter.state) {
        val successState = backdropPainter.state as? AsyncImagePainter.State.Success ?: return@LaunchedEffect
        val colorInt = ImageProcessor.getDominantColor(successState.result.drawable)
        ImageProcessor.putColorToCache(resolvedCoverPath, lastUpdated, colorInt)
        onColorExtracted(Color(colorInt))
    }

    CoverBackgroundBlurCanvas(
        backdropPainter = backdropPainter,
        modifier = modifier
    )
}

/**
 * Remember a software backdrop painter for one non-empty cover request.
 *
 * Keeping painter creation inside the backdrop layer ensures the request key follows the current
 * cover identity without holding a second outgoing painter.
 */
@Composable
private fun rememberCoverBackdropPainter(
    coverPath: String,
    lastUpdated: Long
): AsyncImagePainter {
    val context = LocalContext.current
    val bgRequest = remember(context, coverPath, lastUpdated) {
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
    return rememberAsyncImagePainter(model = bgRequest)
}

/**
 * Draw the blurred cover backdrop using the current painter.
 *
 * Canvas drawing keeps the blur layer full-screen and avoids creating a separate image layout node
 * whose intrinsic measurement could resize while the painter is resolving.
 */
@Composable
private fun CoverBackgroundBlurCanvas(
    backdropPainter: AsyncImagePainter,
    modifier: Modifier
) {
    Canvas(
        modifier = modifier
            .graphicsLayer {
                scaleX = 1.05f
                scaleY = 1.05f
            }
            .blur(64.dp)
    ) {
        val canvasSize = size
        val painterSize = backdropPainter.intrinsicSize
        if (painterSize.width.isFinite() && painterSize.width > 0 &&
            painterSize.height.isFinite() && painterSize.height > 0
        ) {
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
}
