package com.viel.aplayer.ui.common

// Completely replace legacy blur library dependencies with miuix-blur's Backdrop API to achieve high-fidelity textureBlur noisy frosted glass colored high-density blur.
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.viel.aplayer.data.store.GlassEffectMode
import top.yukonga.miuix.kmp.blur.BlendColorEntry
import top.yukonga.miuix.kmp.blur.BlurBlendMode
import top.yukonga.miuix.kmp.blur.BlurColors
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.textureBlur

/**
 * BlurModalBottomSheet — Frosted glass BottomSheet refactored using miuix-blur.
 *
 * Implementation Principles:
 * Material3's [ModalBottomSheet] uses an independent Dialog Window internally to host content. We need
 * to pass the outermost Activity's [LayerBackdrop] to the backdrop parameter here to sample the background, drawing blur via drawBackdrop.
 *
 * Relation with [ModalBottomSheet]:
 * This component is a thin wrapper around Material3 [ModalBottomSheet]. All original parameters are passed through,
 * enclosing only the drag handle and the main content within the stable miuix-blur frosted glass layer. Callers do not need to modify their content structure.
 *
 * Container Color Strategy:
 * Uses surfaceContainerLow + 0.78f alpha by default, which is better suited for glass mimetic visual balance when the BottomSheet covers a large area.
 *
 * @param onDismissRequest Callback invoked when clicking on the scrim or sliding down to close.
 * @param backdrop The blur descriptor state machine associated with the main rendering background.
 * @param glassEffectMode Current glass effect mode. Material mode does not attach the blur modifier.
 * @param sheetState BottomSheet state, controlling expanded/collapsed/partially expanded states.
 * @param shape The shape of the BottomSheet panel's top corners, defaulting to BottomSheetDefaults.ExpandedShape from the Material3 specification.
 * @param containerColor Panel background color, defaulting to surfaceContainerLow + 0.78f alpha.
 * @param contentColor Default foreground color of the content.
 * @param tonalElevation Tonal elevation.
 * @param scrimColor Scrim mask color (keeping default transparent/semi-transparent is recommended, since the blur effect serves as the visual mask).
 * @param dragHandle Top drag handle Composable, defaulting to the Material3 standard handle.
 * @param contentWindowInsets Window insets for the BottomSheet content area, defaulting to not consuming system bars.
 * @param modifier Modifier.
 * @param content BottomSheet body content (ColumnScope).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BlurModalBottomSheet(
    onDismissRequest: () -> Unit,
    backdrop: LayerBackdrop,
    // Glass effect mode must be explicitly passed from the settings state by the caller to prevent BottomSheet from declaring default values privately.
    glassEffectMode: GlassEffectMode,
    modifier: Modifier = Modifier,
    sheetState: SheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false),
    shape: Shape = BottomSheetDefaults.ExpandedShape,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerLow,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
    tonalElevation: Dp = 8.dp,
    scrimColor: Color = BottomSheetDefaults.ScrimColor,
    dragHandle: @Composable (() -> Unit)? = { BottomSheetDefaults.DragHandle() },
    contentWindowInsets: @Composable () -> WindowInsets = { BottomSheetDefaults.windowInsets },
    content: @Composable ColumnScope.() -> Unit
) {
    // Obtain the current dark/light mode state of the system for adaptive BottomSheet color blending.
    val isDark = androidx.compose.foundation.isSystemInDarkTheme()

    // Set the outer containerColor to transparent in MiuixBlur mode, letting the internal frosted glass Box render the background to avoid double backgrounds. Reference modified to MiuixBlur.
    val sheetContainerColor = if (glassEffectMode == GlassEffectMode.MiuixBlur) {
        Color.Transparent
    } else {
        containerColor
    }

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        sheetState = sheetState,
        shape = shape,
        containerColor = sheetContainerColor,
        contentColor = contentColor,
        // Set elevation to 0.dp adaptively in MiuixBlur mode to completely prevent overlapping gray shadows produced by the system RenderNode on transparent rounded corners. Reference modified to MiuixBlur.
        tonalElevation = if (glassEffectMode == GlassEffectMode.MiuixBlur) 0.dp else tonalElevation,
        scrimColor = scrimColor,
        // Move dragHandle (previously drawn by ModalBottomSheet alone) into the blurred content layer to ensure the handle area shares the same frosted glass background.
        dragHandle = null,
        contentWindowInsets = contentWindowInsets,
    ) {
        // Mount drawBackdrop, semi-transparent mask base color, and liquid specular refraction in MiuixBlur mode only; Material mode skips frosted glass decoration completely. Reference modified to the newly renamed MiuixBlur.
        val glassModifier = if (glassEffectMode == GlassEffectMode.MiuixBlur) {
            Modifier.textureBlur(
                backdrop = backdrop,
                shape = shape,
                blurRadius = 60f, // thick -> large-range deep blur
                noiseCoefficient = 0.05f, // texture -> high-fidelity diffuse frosted noise
                colors = BlurColors(
                    blendColors = listOf(
                        BlendColorEntry(
                            color = if (isDark) Color.Black.copy(alpha = 0.35f) else Color.White.copy(alpha = 0.65f), // Fine-tune mask depth to support ambient refraction and specular display from the underlying layers.
                            mode = BlurBlendMode.SrcOver
                        )
                    )
                )
            )
            // 3. Chain-append a specular glare layer (diagonal white reflection) to simulate physical refraction reflections of light source on actual crystal glass surfaces.
            .background(
                brush = Brush.linearGradient(
                    colors = listOf(
                        Color.White.copy(alpha = 0.12f),
                        Color.White.copy(alpha = 0.03f),
                        Color.Transparent,
                        Color.White.copy(alpha = 0.06f)
                    )
                ),
                shape = shape
            )
            // 4. Chain-append a 1.dp extremely fine adaptive gradient shimmering refraction edge border (Refraction Edge) to give the bottom drawer a prominent three-dimensional relief look.
            .border(
                width = 1.dp,
                brush = Brush.linearGradient(
                    colors = if (isDark) {
                        listOf(
                            Color.White.copy(alpha = 0.18f),
                            Color.White.copy(alpha = 0.02f),
                            Color.Transparent,
                            Color.White.copy(alpha = 0.08f)
                        )
                    } else {
                        listOf(
                            Color.White.copy(alpha = 0.45f),
                            Color.White.copy(alpha = 0.10f),
                            Color.Transparent,
                            Color.White.copy(alpha = 0.25f)
                        )
                    }
                ),
                shape = shape
            )
        } else {
            Modifier
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .then(glassModifier)
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                // The original dragHandle slot of Material3 centers by default; it requires restoring fillMaxWidth + Center alignment manually after being moved into the blurred content layer.
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    // Draw the drag handle inside the same blurred panel to avoid texture fragmentation between the top handle area and the body text.
                    dragHandle?.invoke()
                }

                // Pass through body content provided by the caller; the business layer does not need to perceive the internal blur wrapper.
                content()
            }
        }
    }
}
