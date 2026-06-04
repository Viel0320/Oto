package com.viel.aplayer.ui.common

// Miuix-blur Backdrop Integration (Replaces legacy blur library dependencies with miuix-blur's Backdrop API)
// This achieves high-fidelity textureBlur with frosted noise shading and high-density blur.
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.viel.aplayer.data.store.GlassEffectMode
import top.yukonga.miuix.kmp.blur.BlendColorEntry
import top.yukonga.miuix.kmp.blur.BlurBlendMode
import top.yukonga.miuix.kmp.blur.BlurColors
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.textureBlur

/**
 * BlurDialog (A common glassmorphic overlay dialog rewritten using miuix-blur)
 *
 * Implementation principles:
 * - Callers pass the LayerBackdrop of the outermost Activity into the [backdrop] parameter.
 * - The textureBlur modifier renders a high-fidelity glassmorphic overlay using the LayerBackdrop.
 * - Combines the blurred layers with a 0.78f translucent background to achieve clear, breathable visuals.
 *
 * @param onDismissRequest Callback triggered on clicking outside the dialog or pressing the system back button
 * @param backdrop The blur state coordinator linked to the main rendering backdrop
 * @param glassEffectMode Specifies the glass style; skips blur sampling if configured as Material
 * @param scrollable Enables vertical scrolling in the content area if configured as true
 * @param content Composable slot representing the dialog body content
 */
@Composable
fun BlurDialog(
    onDismissRequest: () -> Unit,
    backdrop: LayerBackdrop,
    // Parameter Injection Guard (The glass effect mode must be explicitly provided by the caller to avoid declaring implicit defaults)
    glassEffectMode: GlassEffectMode,
    scrollable: Boolean = true,
    content: @Composable () -> Unit
) {
    // Dark Mode Auto-Adaptation (Inspects the active system theme to adjust the dialog's blended backdrop color)
    val isDark = androidx.compose.foundation.isSystemInDarkTheme()

    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(
            // Disable Platform Width Bounds (Bypasses system default dialog widths to enforce widthIn layout constraints)
            usePlatformDefaultWidth = false,
            // Window Edge Extension (Allows dialog contents to layout under system status and navigation bar insets)
            decorFitsSystemWindows = false
        )
    ) {
        // Full-Screen Container (Serves as a layout interceptor configuring outside click touch behavior)
        // Employs a ripple-free clickable modifier to consume clicks on outside regions and trigger [onDismissRequest]
        // when usePlatformDefaultWidth is set to false.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) {
                    onDismissRequest()
                }
                .padding(horizontal = 24.dp, vertical = 48.dp),
            contentAlignment = Alignment.Center
        ) {
            // Sampling Efficiency Filter (Enables high-fidelity textureBlur only under MiuixBlur; bypasses sampling under Material to save CPU)
            val glassModifier = if (glassEffectMode == GlassEffectMode.MiuixBlur) {
                val dialogShape = MaterialTheme.shapes.extraLarge
                Modifier.textureBlur(
                    backdrop = backdrop,
                    shape = dialogShape,
                    blurRadius = 80f, // Broad Gradient Blend (Extends the blur radius to achieve smooth color blending)
                    noiseCoefficient = 0.05f, // Frosted Diffuse Texture (Injects fine noise mapping to simulate frosted physical surfaces)
                    colors = BlurColors(
                        blendColors = listOf(
                            BlendColorEntry(
                                color = if (isDark) Color.Black.copy(alpha = 0.35f) else Color.White.copy(alpha = 0.65f), // Adaptive Mask Blend (Tones opacity for specular glare and backdrop refraction rendering)
                                mode = BlurBlendMode.SrcOver
                            )
                        ),
                        brightness = if (isDark) -0.12f else -0.05f, // Brightness Dampening (Reduces white contrast highlights behind the dialog container)
                        contrast = 0.65f, // Contrast Mitigation (Flattens high-contrast borders between background elements)
                        saturation = 1.0f
                    )
                )
                // Specular Glare Layer (Draws a linear gradient overlay to simulate specular light refractions)
                .background(
                    brush = Brush.linearGradient(
                        colors = listOf(
                            Color.White.copy(alpha = 0.12f),
                            Color.White.copy(alpha = 0.03f),
                            Color.Transparent,
                            Color.White.copy(alpha = 0.06f)
                        )
                    ),
                    shape = dialogShape
                )
                // Refraction Edge Border (Applies a 1.dp adaptive gradient border to emphasize elevation and contrast)
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
                    shape = dialogShape
                )
            } else {
                Modifier
            }

            // Content Surface (Core Dialog container styled under Material 3 specification)
            // - Uses extraLarge shapes and tonal/shadow elevation mapping to reinforce spatial layering.
            // - Focus Interceptor: Attaches a custom clickable modifier to prevent gesture events propagating upward and closing the dialog.
            Surface(
                modifier = Modifier
                    .widthIn(min = 280.dp, max = 460.dp)
                    .then(glassModifier)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) {
                        // No-Op Gesture Consumption (Empty lambda consuming clicks to prevent triggering dismiss events)
                    },
                shape = MaterialTheme.shapes.extraLarge,
                // Set to transparent in MiuixBlur mode to reveal the shader material blur perfectly; Material mode uses an opaque container.
                color = if (glassEffectMode == GlassEffectMode.MiuixBlur) Color.Transparent else MaterialTheme.colorScheme.surfaceContainerHigh,
                // Elevation Dampening Guard (Forced to 0.dp under MiuixBlur since the Surface backing is transparent)
                // This avoids hardware RenderNode shadows generating unsightly grey border relics.
                tonalElevation = if (glassEffectMode == GlassEffectMode.MiuixBlur) 0.dp else 6.dp,
                shadowElevation = if (glassEffectMode == GlassEffectMode.MiuixBlur) 0.dp else 8.dp
            ) {
                // Scroll Behavior Toggle (Attaches a vertical scroll modifier if scrollable is set to true)
                val scrollModifier = if (scrollable) {
                    Modifier.verticalScroll(rememberScrollState())
                } else {
                    Modifier
                }
                Box(
                    modifier = scrollModifier
                ) {
                    content()
                }
            }
        }
    }
}
