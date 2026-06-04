package com.viel.aplayer.ui.common

// Completely replace legacy blur library dependencies with miuix-blur's Backdrop API to achieve high-fidelity textureBlur noisy frosted glass colored high-density blur.
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDefaults
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import com.viel.aplayer.data.store.GlassEffectMode
import top.yukonga.miuix.kmp.blur.BlendColorEntry
import top.yukonga.miuix.kmp.blur.BlurBlendMode
import top.yukonga.miuix.kmp.blur.BlurColors
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.textureBlur

/**
 * BlurSnackbar — A generic Snackbar wrapper supporting real-time dual-state switching between Material 3 native styling and miuix-blur frosted glass.
 *
 * Core Principles:
 * 1. Explicit Rounded Corner Clipping (Modifier.clip):
 *    In miuix-blur mode, we place the clip modifier before `textureBlur` to achieve perfect rounded-edge sample clipping.
 * 2. Completely Cut Off Shadow Leakage (Custom Shadowless Surface):
 *    In miuix-blur mode, we adopt a custom shadowless Surface (shadowElevation = 0.dp, tonalElevation = 0.dp)
 *    and use defaultMinSize(minHeight) to precisely simulate the minimum height of the official Snackbar, completely eliminating shadow line leaks.
 * 3. Color Adaptation and Blur:
 *    - miuix-blur mode: Sets the Surface container color to fully transparent, relying on textureBlur to render the blurred background and chain-overlaying a semi-transparent mask base color (adaptive to light/dark).
 *    - Material mode: Uses standard native colors and container corner radiuses to guarantee the peak performance and pure experience of native styling.
 */
@Composable
fun BlurSnackbar(
    backdrop: LayerBackdrop,
    glassEffectMode: GlassEffectMode,
    modifier: Modifier = Modifier,
    action: @Composable (() -> Unit)? = null,
    dismissAction: @Composable (() -> Unit)? = null,
    actionOnNewLine: Boolean = false,
    shape: Shape = RoundedCornerShape(12.dp),
    containerColor: Color = MaterialTheme.colorScheme.surfaceVariant,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
    actionContentColor: Color = MaterialTheme.colorScheme.primary,
    dismissActionContentColor: Color = SnackbarDefaults.dismissActionContentColor,
    content: @Composable () -> Unit
) {
    // Limit Snackbar max width.
    //
    // Constrain the maximum width to 480.dp to provide better visual layout and readability on large-screen/landscape devices.
    val constrainedModifier = modifier.widthIn(max = 480.dp)

    // Align with the newly renamed MiuixBlur, using textureBlur on the fly to render the frosted glass effect.
    if (glassEffectMode == GlassEffectMode.MiuixBlur) {
        // Obtain the current dark mode status of the system for dual-state frosted glass color adaptation.
        val isDark = androidx.compose.foundation.isSystemInDarkTheme()
        // Use textureBlur instead of the original drawBackdrop physical sampling to support a colored thick frosted pill glass texture.
        val glassModifier = Modifier.textureBlur(
            backdrop = backdrop,
            shape = shape,
            blurRadius = 60f, // thick -> thick blur, providing excellent immersion
            noiseCoefficient = 0.05f, // texture -> strong frosted noise texture
            colors = BlurColors(
                blendColors = listOf(
                    BlendColorEntry(
                        color = if (isDark) Color(0xFF2C2C2C).copy(alpha = 0.65f) else Color.White.copy(alpha = 0.82f), // colored -> adaptive color blending
                        mode = BlurBlendMode.SrcOver
                    )
                )
            )
        )
        // 3. Chain-overlay a specular glare layer (diagonal white physical sweep) to give the pill-shaped bar a micro-droplet 3D stereoscopic feel.
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
        // 4. Chain-add a 1.dp extremely fine refracting gradient border (Refraction Edge) to prevent edge sticking on large variegated backgrounds.
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

        // Custom shadowless Surface.
        //
        // Force shadow and tonal elevation to 0.dp to eliminate black edge projection artifacts.
        // It draws the blurred background and adaptive light/dark semi-transparent base color by mounting miuix-blur, achieving a gorgeous, clear, and high-end frosted glass effect.
        Surface(
            modifier = constrainedModifier
                .then(glassModifier),
            shape = shape,
            color = Color.Transparent,
            contentColor = contentColor,
            shadowElevation = 0.dp,
            tonalElevation = 0.dp
        ) {
            // High-precision simulation of the official M3 Snackbar layout. Force the minimum height constraint using defaultMinSize, and support actionOnNewLine and dismissAction.
            if (actionOnNewLine) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = 68.dp)
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(modifier = Modifier.fillMaxWidth()) {
                        content()
                    }
                    Row(
                        modifier = Modifier.align(Alignment.End),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (action != null) action()
                        if (dismissAction != null) dismissAction()
                    }
                }
            } else {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = 48.dp)
                        .padding(
                            start = 16.dp,
                            end = if (action != null || dismissAction != null) 8.dp else 16.dp
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            // Dynamically assign vertical padding (10.dp or 14.dp) based on the presence of actions to prevent padding accumulation between Row and internal child items.
                            .padding(vertical = if (action != null || dismissAction != null) 10.dp else 14.dp)
                    ) {
                        content()
                    }

                    if (action != null) action()
                    if (dismissAction != null) dismissAction()
                }
            }
        }
    } else {
        // Material native mode, directly using the official standard Snackbar to preserve optimal rendering compatibility.
        Snackbar(
            modifier = constrainedModifier,
            action = action,
            dismissAction = dismissAction,
            actionOnNewLine = actionOnNewLine,
            shape = shape,
            containerColor = containerColor,
            contentColor = contentColor,
            actionContentColor = actionContentColor,
            dismissActionContentColor = dismissActionContentColor,
            content = content
        )
    }
}
