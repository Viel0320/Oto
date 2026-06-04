package com.viel.aplayer.ui.common

// Completely replace legacy blur library dependencies with miuix-blur's Backdrop API to achieve high-fidelity textureBlur noisy frosted glass colored high-density blur.
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.MenuDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.PopupProperties
import com.viel.aplayer.data.store.GlassEffectMode
import top.yukonga.miuix.kmp.blur.BlendColorEntry
import top.yukonga.miuix.kmp.blur.BlurBlendMode
import top.yukonga.miuix.kmp.blur.BlurColors
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.textureBlur

/**
 * BlurDropdownMenu — A generic DropdownMenu wrapper supporting switching between Material native menu and miuix-blur frosted glass menu.
 *
 * Usage:
 * - Callers maintain the same [LayerBackdrop] at the root of the host page.
 * - Material mode only uses the native [DropdownMenu] and does not enable the frosted glass rendering pipeline.
 * - miuix-blur mode attaches textureBlur to the content modifier of the DropdownMenu itself, adding a 0.78f semi-transparent base color to ensure good text contrast and premium design aesthetics.
 */
@Composable
fun BlurDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    backdrop: LayerBackdrop,
    // Glass effect mode must be explicitly passed from the settings state by the caller to prevent the generic menu from declaring default values privately.
    glassEffectMode: GlassEffectMode,
    modifier: Modifier = Modifier,
    offset: DpOffset = DpOffset(0.dp, 0.dp),
    scrollState: ScrollState = rememberScrollState(),
    properties: PopupProperties = PopupProperties(focusable = true),
    content: @Composable ColumnScope.() -> Unit
) {
    // Reuse the Material3 default menu shape to ensure the blur layer and the outer Surface of DropdownMenu share the same rounded corner boundaries.
    val menuShape = MenuDefaults.shape
    // Set the outer Surface to transparent in MiuixBlur mode to avoid overlap rendering conflicts between the Surface background and drawBackdrop. Reference modified to the newly renamed MiuixBlur.
    val menuContainerColor = if (glassEffectMode == GlassEffectMode.MiuixBlur) {
        Color.Transparent
    } else {
        MenuDefaults.containerColor
    }
    // Obtain the current dark/light mode state of the system for adaptive drop-down menu base color blending.
    val isDark = androidx.compose.foundation.isSystemInDarkTheme()

    // The modifier will be applied by Material3 to the internal scrolling Column, using miuix-blur on the fly to render blur and liquid specular refraction and overlaying a semi-transparent mask base color. Reference modified to the newly renamed MiuixBlur.
    val menuModifier = if (glassEffectMode == GlassEffectMode.MiuixBlur) {
        Modifier.textureBlur(
            backdrop = backdrop,
            shape = menuShape,
            blurRadius = 60f, // thick -> thick blur, providing excellent immersion
            noiseCoefficient = 0.05f, // texture -> strong frosted noise texture
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
            shape = menuShape
        )
        // 4. Chain-append a 1.dp extremely fine adaptive gradient shimmering refraction edge border (Refraction Edge) to significantly enhance the quality and three-dimensional texture of the drop-down menu.
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
            shape = menuShape
        )
    } else {
        Modifier
    }

    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        modifier = modifier.then(menuModifier),
        offset = offset,
        scrollState = scrollState,
        properties = properties,
        shape = menuShape,
        containerColor = menuContainerColor,
        // MiuixBlur mode no longer lets the Material tonal overlay participate in color blending to avoid color tone discrepancies between the menu edge and content area. Reference modified to the newly renamed MiuixBlur.
        tonalElevation = if (glassEffectMode == GlassEffectMode.MiuixBlur) 0.dp else MenuDefaults.TonalElevation,
        // Adaptively zero out elevation to completely eliminate potential Android system-level hardware shadow ghosting under transparent viewports.
        shadowElevation = if (glassEffectMode == GlassEffectMode.MiuixBlur) 0.dp else MenuDefaults.ShadowElevation
    ) {
        // Pass through original DropdownMenuItem content; the business layer only needs to replace the container component.
        content()
    }
}
