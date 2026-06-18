package com.viel.aplayer.ui.common

// Setup Haze Integration (Import dev.chrisbanes.haze libraries) Import HazeState and haze modifier for Compose-based blur.
// Import Canvas and Blur APIs (Allow drawing blurred cover images natively)
// Import Compose Canvas, draw.blur, geometry.Size, and Coil's rememberAsyncImagePainter to replace AsyncImage with Canvas-based blur.
import android.graphics.Bitmap
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
 * Backdrop crossfade identity.
 *
 * The blurred background follows the same asset identity as the visible cover, so book changes and
 * same-book cover regeneration both receive a short bitmap transition instead of an abrupt swap.
 */
private data class CoverBackdropCrossfadeKey(
    val coverPath: String?,
    val lastUpdated: Long
)

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

    val backdropCrossfadeKey = remember(coverPath, lastUpdated) {
        CoverBackdropCrossfadeKey(coverPath, lastUpdated)
    }
    var settledBackdropKey by remember { mutableStateOf(backdropCrossfadeKey) }
    var incomingBackdropKey by remember { mutableStateOf<CoverBackdropCrossfadeKey?>(null) }
    var incomingBackdropReady by remember { mutableStateOf(false) }
    val incomingBackdropAlpha = remember { Animatable(1f) }

    LaunchedEffect(backdropCrossfadeKey) {
        if (backdropCrossfadeKey == settledBackdropKey) {
            incomingBackdropKey = null
            incomingBackdropReady = false
            incomingBackdropAlpha.snapTo(1f)
        } else {
            incomingBackdropKey = backdropCrossfadeKey
            incomingBackdropReady = backdropCrossfadeKey.coverPath == null
            incomingBackdropAlpha.snapTo(0f)
        }
    }

    LaunchedEffect(incomingBackdropKey, incomingBackdropReady) {
        val readyKey = incomingBackdropKey ?: return@LaunchedEffect
        if (!incomingBackdropReady) return@LaunchedEffect
        incomingBackdropAlpha.animateTo(
            targetValue = 1f,
            animationSpec = tween(300)
        )
        settledBackdropKey = readyKey
        incomingBackdropKey = null
        incomingBackdropReady = false
        incomingBackdropAlpha.snapTo(1f)
    }

    // Setup Layout Modifier (Apply haze to background Box) Link haze modifier to background container if in Haze mode.
    Box(
        modifier = modifier
            .fillMaxSize()
            .then(if (!isBlur) Modifier.background(backgroundBrush) else Modifier)
            .then(if (isBlur && hazeState != null) Modifier.hazeSource(hazeState) else Modifier)
    ) {
        /*
         * Blurred Backdrop Crossfade (Animate decorative bitmap changes in place)
         *
         * The Haze source and page gradient remain stable on the parent Box, while each backdrop
         * target owns its painter, color extraction, blur canvas, and darkening mask during fade.
         */
        if (isBlur) {
            val activeIncomingKey = incomingBackdropKey
            val settledAlpha = if (activeIncomingKey != null && activeIncomingKey.coverPath == null) {
                1f - incomingBackdropAlpha.value
            } else {
                1f
            }

            CoverBackgroundBackdropLayer(
                targetBackdrop = settledBackdropKey,
                activeTarget = backdropCrossfadeKey,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        alpha = settledAlpha
                    },
                onBackdropReady = {},
                onColorExtracted = onColorExtracted
            )

            if (activeIncomingKey != null) {
                CoverBackgroundBackdropLayer(
                    targetBackdrop = activeIncomingKey,
                    activeTarget = backdropCrossfadeKey,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            alpha = incomingBackdropAlpha.value
                        },
                    onBackdropReady = { readyKey ->
                        if (incomingBackdropKey == readyKey) {
                            incomingBackdropReady = true
                        }
                    },
                    onColorExtracted = onColorExtracted
                )
            }

            if (settledBackdropKey.coverPath != null || activeIncomingKey?.coverPath != null) {
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
 * Render one loaded backdrop layer.
 *
 * The layer reports readiness only after Coil has produced a successful drawable, preventing the
 * visible blur transition from spending its duration on an empty painter.
 */
@Composable
private fun CoverBackgroundBackdropLayer(
    targetBackdrop: CoverBackdropCrossfadeKey,
    activeTarget: CoverBackdropCrossfadeKey,
    modifier: Modifier,
    onBackdropReady: (CoverBackdropCrossfadeKey) -> Unit,
    onColorExtracted: (Color) -> Unit
) {
    val coverPath = targetBackdrop.coverPath ?: return
    val backdropPainter = rememberCoverBackdropPainter(
        coverPath = coverPath,
        lastUpdated = targetBackdrop.lastUpdated
    )

    /**
     * Extracts the page-level cover seed from the active software-decoded backdrop image.
     * This keeps foreground cover components display-only and blocks outgoing images from
     * writing stale colors while they are still fading out.
     */
    LaunchedEffect(targetBackdrop, activeTarget, backdropPainter.state) {
        val successState = backdropPainter.state as? AsyncImagePainter.State.Success ?: return@LaunchedEffect
        onBackdropReady(targetBackdrop)
        if (targetBackdrop != activeTarget) return@LaunchedEffect
        val colorInt = ImageProcessor.getDominantColor(successState.result.drawable)
        ImageProcessor.putColorToCache(coverPath, targetBackdrop.lastUpdated, colorInt)
        onColorExtracted(Color(colorInt))
    }

    CoverBackgroundBlurCanvas(
        backdropPainter = backdropPainter,
        modifier = modifier
    )
}

/**
 * Remember a software backdrop painter for one non-empty crossfade target.
 *
 * Keeping painter creation inside the target content lets Compose keep the outgoing painter alive
 * only for the fade duration, instead of replacing it before the transition can be drawn.
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
 * Draw the blurred cover backdrop using the current target painter.
 *
 * Canvas drawing keeps the blur layer full-screen and avoids creating a separate image layout node
 * whose intrinsic measurement could resize during a crossfade.
 */
@Composable
private fun CoverBackgroundBlurCanvas(
    backdropPainter: AsyncImagePainter,
    modifier: Modifier
) {
    // Render Canvas-based Blur Background (Draw cover on canvas and apply native blur modifier)
    // Use rememberAsyncImagePainter to load cover and render it via Canvas with Modifier.blur to implement hardware-accelerated cover background blur.
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
}
