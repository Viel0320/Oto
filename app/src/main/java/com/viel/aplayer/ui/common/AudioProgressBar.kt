package com.viel.aplayer.ui.common

// Import Resolution (Brings Modifier.semantics extension into scope to build accessibility semantics tree)
// Added semantics import to fix unresolved reference semantics error.
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.viel.aplayer.R
import com.viel.aplayer.data.store.GlassEffectMode
import com.viel.aplayer.ui.common.theme.APlayerTheme

@Composable
fun AudioProgressBar(
    progress: () -> Float, // Optimization: Use Lambda to prevent high-frequency recompositions.
    onProgressChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    bufferedProgress: () -> Float = { 0f },
    color: Color = MaterialTheme.colorScheme.primary,
    showKnob: Boolean = true,
    markers: List<Float> = emptyList(),
    // Accessibility Semantics Toggle (Skip the progress semantics node for decorative, non-seekable bars)
    // Mini-player bars are display-only (no onProgressChange), so emitting a per-frame-changing
    // progressBarRangeInfo only adds a node to the a11y geometry tree and announces an unusable value.
    enableProgressSemantics: Boolean = true,
    // Added glass effect selection mode parameter to enable a premium progress bar with a realistic water droplet crystal texture.
    glassEffectMode: GlassEffectMode = GlassEffectMode.Material,
) {
    val currentOnProgressChange by rememberUpdatedState(onProgressChange)
    
    // Cache colors and density conversions to avoid repeatedly creating objects or performing calculations during drawing.
    val trackColor = remember(color) { color.copy(alpha = 0.2f) }
    val markerColor = remember { Color.Black.copy(alpha = 0.3f) }
    val density = LocalDensity.current
    val strokeWidthPx = remember(density) { with(density) { 6.dp.toPx() } }
    // Added the radius parameter of the chapter marker ball, allowing it to embed perfectly and delicately in the track.
    val markerRadiusPx = remember(density) { with(density) { 2.dp.toPx() } }
    val knobRadiusPx = remember(density) { with(density) { 8.dp.toPx() } }
    // Theme Aware Progress Bar (Use LocalDarkTheme to resolve active theme state instead of system defaults) Read theme preference state for canvas rendering.
    val isDark = com.viel.aplayer.ui.common.theme.LocalDarkTheme.current
    // Glass Effect Resolution (Resolve glass effect modes) Map blur check to the Haze glass effect mode enum.
    val isBlur = glassEffectMode == GlassEffectMode.Haze
    // Extract secondary and tertiary colors within the Composable context, caching them as local non-Composable variables.
    // This perfectly resolves compilation errors caused by calling @Composable properties directly inside DrawScope.
    val secondaryColor = MaterialTheme.colorScheme.secondary
    val tertiaryColor = MaterialTheme.colorScheme.tertiary
    val progressContentDescription = stringResource(R.string.playback_progress_content_description)

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(32.dp)
            .graphicsLayer() // Isolate Repaint (Isolate repaint layer to minimize redraw effects on the parent layout container)
            // M-17 Fix — Supplement accessibility semantic nodes.
            // Provide progressBarRangeInfo to allow TalkBack to recognize progress values and range.
            // setProgress custom action allows accessibility services to programmatically configure the progress.
            // Decorative bars (mini player) opt out so they neither add a node to the a11y geometry tree
            // nor feed it a per-frame-changing progress value the user cannot act on.
            .then(
                if (enableProgressSemantics) {
                    Modifier.semantics(mergeDescendants = true) {
                        contentDescription = progressContentDescription
                        progressBarRangeInfo = ProgressBarRangeInfo(
                            current = progress(),
                            range = 0f..1f,
                            steps = 0
                        )
                        setProgress { newValue ->
                            currentOnProgressChange(newValue.coerceIn(0f, 1f))
                            true
                        }
                    }
                } else {
                    Modifier
                }
            )
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    val newProgress = (offset.x / size.width).coerceIn(0f, 1f)
                    currentOnProgressChange(newProgress)
                }
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onHorizontalDrag = { change, _ ->
                        change.consume()
                        val newProgress = (change.position.x / size.width).coerceIn(0f, 1f)
                        currentOnProgressChange(newProgress)
                    }
                )
            }
    ) {
        val width = size.width
        val height = size.height
        val centerY = height / 2
        val currentProgress = progress().coerceIn(0f, 1f) // Only read the progress during the drawing phase
        val currentBufferedProgress = bufferedProgress().coerceAtLeast(currentProgress).coerceIn(0f, 1f)
        val activeWidth = width * currentProgress
        val bufferedWidth = width * currentBufferedProgress

        // 1. Draw the background track (unplayed part)
        if (isBlur) {
            // Fine-tune the transparency of the unplayed background track (Track Brush) to minimize noise and improve clarity.
            // Adjusted from 15% -> 5% down to 8% -> 2% in dark mode, and from 12% -> 3% down to 6% -> 1% in light mode.
            // This ensures the background track exhibits a highly translucent, feather-light frosted texture.
            val trackBrush = Brush.linearGradient(
                colors = if (isDark) {
                    listOf(Color.White.copy(alpha = 0.08f), Color.White.copy(alpha = 0.02f))
                } else {
                    listOf(Color.Black.copy(alpha = 0.06f), Color.Black.copy(alpha = 0.01f))
                },
                start = Offset(0f, centerY),
                end = Offset(width, centerY)
            )
            drawLine(
                brush = trackBrush,
                start = Offset(0f, centerY),
                end = Offset(width, centerY),
                strokeWidth = strokeWidthPx,
                cap = StrokeCap.Round
            )
        } else {
            drawLine(
                color = trackColor,
                start = Offset(0f, centerY),
                end = Offset(width, centerY),
                strokeWidth = strokeWidthPx,
                cap = StrokeCap.Round
            )
        }

        // 2. Draw the buffered track (memory buffer coverage that has not been played yet)
        if (bufferedWidth > activeWidth) {
            if (isBlur) {
                val bufferedBrush = Brush.linearGradient(
                    colors = if (isDark) {
                        listOf(Color.White.copy(alpha = 0.18f), Color.White.copy(alpha = 0.06f))
                    } else {
                        listOf(Color.Black.copy(alpha = 0.16f), Color.Black.copy(alpha = 0.04f))
                    },
                    start = Offset(activeWidth, centerY),
                    end = Offset(bufferedWidth, centerY)
                )
                drawLine(
                    brush = bufferedBrush,
                    start = Offset(activeWidth, centerY),
                    end = Offset(bufferedWidth, centerY),
                    strokeWidth = strokeWidthPx,
                    cap = StrokeCap.Round
                )
            } else {
                drawLine(
                    color = color.copy(alpha = 0.35f),
                    start = Offset(activeWidth, centerY),
                    end = Offset(bufferedWidth, centerY),
                    strokeWidth = strokeWidthPx,
                    cap = StrokeCap.Round
                )
            }
        }
        
        // 3. Draw chapter markers (upgraded to crystal balls)
        markers.forEach { marker ->
            if (marker > 0f && marker < 1f) {
                val markerX = width * marker
                // The chapter markers are upgraded to adaptive "micro-carved refractive crystal balls" in the Haze glass state, embedded in the center of the 6.dp track.
                // It draws 35% crystal white in dark mode and 20% semi-transparent black in light mode, achieving a realistic look that coordinates beautifully with the Knob size.
                val currentMarkerColor = if (isBlur) {
                    if (isDark) Color.White.copy(alpha = 0.35f) else Color.Black.copy(alpha = 0.20f)
                } else {
                    markerColor
                }
                drawCircle(
                    color = currentMarkerColor,
                    radius = markerRadiusPx,
                    center = Offset(markerX, centerY)
                )
            }
        }

        // 4. Draw the played progress track
        if (activeWidth > 0) {
            if (isBlur) {
                // Brighten and thin out the three-layered crystal progress tube to respond to the requirement of "more transparency":
                // (a) Bottom layer: Draw a slightly wider peripheral refraction stroke line. The opacity is thinned down from 0.22 to 0.12 in dark mode, and from 0.55 to 0.30 in light mode, eliminating floaty noise.
                val borderGlowColor = if (isDark) Color.White.copy(alpha = 0.12f) else Color.White.copy(alpha = 0.30f)
                drawLine(
                    color = borderGlowColor,
                    start = Offset(0f, centerY),
                    end = Offset(activeWidth, centerY),
                    strokeWidth = strokeWidthPx + with(density) { 1.dp.toPx() },
                    cap = StrokeCap.Round
                )
                // (b) Middle layer: Draw the colored flowing liquid column track. The opacity of its three-color gradient is halved from 55%/45%/50% to 28%/18%/23%.
                //     While keeping the warm and cool flow sensations, this allows it to integrate more transparently and clearly with the frosted glass background.
                val progressBrush = Brush.linearGradient(
                    colors = listOf(
                        color.copy(alpha = 0.28f),
                        secondaryColor.copy(alpha = 0.18f),
                        tertiaryColor.copy(alpha = 0.23f)
                    ),
                    start = Offset(0f, centerY),
                    end = Offset(activeWidth, centerY)
                )
                drawLine(
                    brush = progressBrush,
                    start = Offset(0f, centerY),
                    end = Offset(activeWidth, centerY),
                    strokeWidth = strokeWidthPx,
                    cap = StrokeCap.Round
                )
                // (c) Top inner layer: The opacity of the white reflective core tube is slightly reduced from 55%/10%/35% to 35%/0.05%/20%, achieving an ultra-refreshing and transparent flowing reflection effect.
                val innerGlowBrush = Brush.linearGradient(
                    colors = listOf(
                        Color.White.copy(alpha = 0.35f),
                        Color.White.copy(alpha = 0.05f),
                        Color.White.copy(alpha = 0.20f)
                    ),
                    start = Offset(0f, centerY),
                    end = Offset(activeWidth, centerY)
                )
                drawLine(
                    brush = innerGlowBrush,
                    start = Offset(0f, centerY),
                    end = Offset(activeWidth, centerY),
                    strokeWidth = strokeWidthPx * 0.38f,
                    cap = StrokeCap.Round
                )
            } else {
                drawLine(
                    color = color,
                    start = Offset(0f, centerY),
                    end = Offset(activeWidth, centerY),
                    strokeWidth = strokeWidthPx,
                    cap = StrokeCap.Round
                )
            }
        }
        
        // 5. Draw progress handle (Knob)
        if (showKnob) {
            if (isBlur) {
                // In Haze mode, the progress knob is refactored into a glass micro-bead effect:
                // (a) Draw a radial gradient semi-transparent frosted base for the knob.
                val knobBgBrush = Brush.radialGradient(
                    colors = if (isDark) {
                        listOf(Color.White.copy(alpha = 0.35f), Color.White.copy(alpha = 0.05f))
                    } else {
                        listOf(Color.White.copy(alpha = 0.95f), Color.White.copy(alpha = 0.60f))
                    },
                    center = Offset(activeWidth, centerY),
                    radius = knobRadiusPx
                )
                drawCircle(
                    brush = knobBgBrush,
                    radius = knobRadiusPx,
                    center = Offset(activeWidth, centerY)
                )
                // (b) Draw a moist "Luminous Nucleus" using the primary color at the center of the bead.
                drawCircle(
                    color = color,
                    radius = knobRadiusPx * 0.45f,
                    center = Offset(activeWidth, centerY)
                )
                // (c) Wrap it with a 1.dp extremely fine refracting gradient border (Refraction Edge).
                drawCircle(
                    brush = Brush.linearGradient(
                        colors = if (isDark) {
                            listOf(Color.White.copy(alpha = 0.45f), Color.Transparent, Color.White.copy(alpha = 0.15f))
                        } else {
                            listOf(Color.White.copy(alpha = 0.90f), Color.Transparent, Color.White.copy(alpha = 0.40f))
                        },
                        start = Offset(activeWidth - knobRadiusPx, centerY - knobRadiusPx),
                        end = Offset(activeWidth + knobRadiusPx, centerY + knobRadiusPx)
                     ),
                     radius = knobRadiusPx,
                     center = Offset(activeWidth, centerY),
                     style = androidx.compose.ui.graphics.drawscope.Stroke(width = with(density) { 1.dp.toPx() })
                )
            } else {
                drawCircle(
                    color = color,
                    radius = knobRadiusPx,
                    center = Offset(activeWidth, centerY)
                )
            }
        }
    }
}

@Preview(showBackground = true, apiLevel = 36)
@Composable
fun AudioProgressBarPreview() {
    APlayerTheme {
        AudioProgressBar(
            progress = { 0.4f },
            onProgressChange = {},
            modifier = Modifier.padding(16.dp),
            markers = listOf(0.2f, 0.5f, 0.8f)
        )
    }
}
