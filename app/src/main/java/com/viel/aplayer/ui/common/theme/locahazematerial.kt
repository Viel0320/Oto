package com.viel.aplayer.ui.common.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect

/**
 * SurfaceProfile definition for the refraction bezel.
 *
 * Defines the cross-section profile shapes of the refraction bezel to control refraction edge styles.
 */
enum class SurfaceProfile {
    Circle,
    Squircle,
    Lip,
    Concave
}

/**
 * ChromaticAberrationMode definition for chromatic aberration quality.
 *
 * Controls the quality mode for chromatic aberration, offering Simple (fast) or Full (spectral) modes.
 */
enum class ChromaticAberrationMode {
    Simple,
    Full
}

/**
 * LiquidGlassBorderMode definition for component edge highlight rendering.
 *
 * Controls whether the liquid glass renderer draws a full shape outline or only the lower divider edge for toolbar-like surfaces.
 */
enum class LiquidGlassBorderMode {
    None,
    Outline,
    BottomEdge
}

/**
 * LiquidGlassStyle configuration data class.
 *
 * Configures refraction, depth, specular highlight, and light position parameters to mimic the Liquid Glass design language.
 */
data class LiquidGlassStyle(
    val tint: Color = Color.Unspecified,
    val refractionStrength: Float = 0.7f,
    val refractionHeight: Float = 0.25f,
    val depth: Float = 0.4f,
    val blurRadius: Dp = 40.dp,
    val specularIntensity: Float = 0.4f,
    val ambientResponse: Float = 0.5f,
    val edgeSoftness: Dp = 12.dp,
    val shape: Shape = RoundedCornerShape(16.dp),
    val surfaceProfile: SurfaceProfile = SurfaceProfile.Squircle,
    val lightPosition: Offset? = null,
    val chromaticAberrationStrength: Float = 0f,
    val chromaticAberrationMode: ChromaticAberrationMode = ChromaticAberrationMode.Full,
    val borderMode: LiquidGlassBorderMode = LiquidGlassBorderMode.Outline
)

/**
 * CompositionLocal providing default LiquidGlassStyle configuration.
 *
 * Exposes a LocalLiquidGlassStyle CompositionLocal to enable scoped styling of liquid glass effects across component trees.
 */
val LocalLiquidGlassStyle = staticCompositionLocalOf { LiquidGlassStyle() }

/**
 * Convert LiquidGlassStyle to HazeStyle compatibility mapper.
 *
 * Maps LiquidGlassStyle properties to dev.chrisbanes.haze.HazeStyle properties compatible with Haze v1.7.2.
 */
fun LiquidGlassStyle.toHazeStyle(): HazeStyle {
    return HazeStyle(
        blurRadius = if (blurRadius != Dp.Unspecified) blurRadius else 20.dp,
        tint = HazeTint(color = tint),
        noiseFactor = 0.1f
    )
}

/**
 * Modifier extension for Liquid Glass simulation effect.
 *
 * Combines Haze 1.7.2 backdrop blur with custom drawing of bezel highlights, Fresnel response, and virtual light source reflections.
 */
fun Modifier.liquidGlassCompatEffect(
    state: HazeState,
    style: LiquidGlassStyle = LiquidGlassStyle()
): Modifier = this.composed {
    // Theme Aware Liquid Glass (Use LocalDarkTheme to resolve active theme state instead of system defaults) Read theme preference state for glass effect color tinting.
    val isDark = LocalDarkTheme.current
    val resolvedTint = remember(style.tint, isDark) {
        if (style.tint != Color.Unspecified) {
            style.tint
        } else {
            // Theme-Aware Adaptive Glass Tint: Resolves unspecified glass tint color dynamically based on system dark theme setting (White 12% alpha for Dark mode, Black 12% alpha for Light mode) to ensure optimal visual contrast.
            //if (isDark) Color.White.copy(alpha = 0.12f) else Color.Black.copy(alpha = 0.12f)
            Color.Black.copy(alpha = 0.25f)
        }
    }
    val resolvedStyle = remember(style, resolvedTint) {
        style.copy(tint = resolvedTint)
    }

    this
        .hazeEffect(
            state = state,
            style = resolvedStyle.toHazeStyle()
        )
        .drawWithContent {
            // Render base blur and background content
            drawContent()

            // Create shape outline to draw high-fidelity border highlights
            val outline = resolvedStyle.shape.createOutline(size, layoutDirection, this)

            // Calculate virtual light source positions to cast realistic specular reflections
            val startOffset = resolvedStyle.lightPosition ?: Offset(0f, 0f)
            val endOffset = Offset(size.width, size.height)

            // Derive highlight opacity based on the configured specular intensity
            val highlightColor = Color.White.copy(alpha = 0.35f * resolvedStyle.specularIntensity)
            
            // Derive ambient lift opacity matching the Fresnel reflection strength
            val ambientColor = Color.White.copy(alpha = 0.15f * resolvedStyle.ambientResponse)
            val shadowColor = Color.Black.copy(alpha = 0.05f * (1f - resolvedStyle.ambientResponse))

            // Create linear gradient representing glass refraction highlight transitions
            val borderBrush = Brush.linearGradient(
                colors = listOf(
                    highlightColor,
                    ambientColor,
                    shadowColor
                ),
                start = startOffset,
                end = endOffset
            )

            when (resolvedStyle.borderMode) {
                LiquidGlassBorderMode.None -> {
                    // Liquid Glass Border Suppression (Allow borderless blurred surfaces)
                    // Some full-width chrome surfaces need Haze blur and tint without a decorative outline, so this mode intentionally skips all edge drawing.
                }
                LiquidGlassBorderMode.Outline -> {
                    // Render fine 1.dp bezel outer boundary highlights
                    drawOutline(
                        outline = outline,
                        brush = borderBrush,
                        style = Stroke(width = 2.dp.toPx())
                    )
                }
                LiquidGlassBorderMode.BottomEdge -> {
                    // Render Bottom Edge Highlight (Limit toolbar glass definition to the lower boundary)
                    // Toolbar glass already spans the full screen width, so drawing only the lower edge avoids visible side and top outlines while preserving the liquid glass separator.
                    drawLiquidGlassBottomEdge(borderBrush)
                }
            }
        }
}

/**
 * Draw Liquid Glass Bottom Edge (Render toolbar-only border highlight)
 *
 * Draws a single lower horizontal stroke using the same gradient brush as the full outline mode so toolbar surfaces keep the shared liquid glass optical language.
 */
private fun DrawScope.drawLiquidGlassBottomEdge(borderBrush: Brush) {
    val strokeWidth = 2.dp.toPx()
    val y = size.height - strokeWidth / 2f
    drawLine(
        brush = borderBrush,
        start = Offset(0f, y),
        end = Offset(size.width, y),
        strokeWidth = strokeWidth
    )
}
