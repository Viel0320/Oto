package com.viel.aplayer.ui.common

// Setup Haze Menu Integration (Replace miuix-blur with dev.chrisbanes.haze) Replaced miuix backdrop APIs with HazeState, hazeChild, and HazeMaterials.
// Import Clip Extension (Fix unresolved clip extension reference) Add explicit draw.clip import to allow using Modifier.clip.
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.MenuDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.PopupProperties
import com.viel.aplayer.data.store.GlassEffectMode
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeChild
import dev.chrisbanes.haze.materials.HazeMaterials

/**
 * BlurDropdownMenu — A generic DropdownMenu wrapper supporting switching between Material native menu and Haze frosted glass menu.
 *
 * Usage:
 * - Callers maintain the same [HazeState] at the root of the host page.
 * - Material mode only uses the native [DropdownMenu] and does not enable the frosted glass rendering pipeline.
 * - Haze mode attaches hazeChild to the content modifier of the DropdownMenu itself.
 */
@Composable
fun BlurDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    // Support Nullable HazeState (Provide fallback when hazeState is not ready)
    // Make hazeState optional and default to null so the menu can degrade gracefully in previews or when parent has no blur context.
    hazeState: HazeState? = null,
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
    // Set the outer Surface to transparent in Haze mode to avoid overlap rendering conflicts between the Surface background.
    // Determine Container Color (Use transparent only if Haze blur is active)
    val menuContainerColor = if (glassEffectMode == GlassEffectMode.Haze && hazeState != null) {
        Color.Transparent
    } else {
        MenuDefaults.containerColor
    }
    // Obtain the current dark/light mode state of the system for adaptive drop-down menu base color blending.
    val isDark = androidx.compose.foundation.isSystemInDarkTheme()

    val menuModifier = if (glassEffectMode == GlassEffectMode.Haze && hazeState != null) {
        // Remove Specular and Border (Clean up glass effect decoration) Remove extra linear gradient background overlay and border properties for minimalist design.
        Modifier
            // Clip menu shape before applying hazeChild
            .clip(menuShape)
            .hazeChild(
                state = hazeState,
                style = HazeMaterials.regular()
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
        // Tonal and Shadow Elevation Tuning (Dampen shadow/tonal elevation to 0.dp only when Haze is active)
        // Haze mode no longer lets the Material tonal overlay participate in color blending to avoid color tone discrepancies between the menu edge and content area.
        tonalElevation = if (glassEffectMode == GlassEffectMode.Haze && hazeState != null) 0.dp else MenuDefaults.TonalElevation,
        // Adaptively zero out elevation to completely eliminate potential Android system-level hardware shadow ghosting under transparent viewports.
        shadowElevation = if (glassEffectMode == GlassEffectMode.Haze && hazeState != null) 0.dp else MenuDefaults.ShadowElevation
    ) {
        // Pass through original DropdownMenuItem content; the business layer only needs to replace the container component.
        content()
    }
}
