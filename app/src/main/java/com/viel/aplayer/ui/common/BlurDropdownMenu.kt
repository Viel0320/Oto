package com.viel.aplayer.ui.common

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.PopupProperties
import com.viel.aplayer.shared.settings.GlassEffectMode
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import dev.chrisbanes.haze.materials.HazeMaterials

/**
 * Generic DropdownMenu wrapper that switches between the Material menu and Haze frosted glass rendering.
 *
 * Usage:
 * - Callers maintain the same [HazeState] at the root of the host page.
 * - Material mode only uses the native [DropdownMenu] and does not enable the frosted glass rendering pipeline.
 * - Haze mode attaches hazeChild to the content modifier of the DropdownMenu itself.
 */
@OptIn(ExperimentalHazeMaterialsApi::class)
@Composable
fun BlurDropdownMenu(
    modifier: Modifier = Modifier,
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    hazeState: HazeState? = null,
    glassEffectMode: GlassEffectMode,
    offset: DpOffset = DpOffset(0.dp, 0.dp),
    scrollState: ScrollState = rememberScrollState(),
    properties: PopupProperties = PopupProperties(focusable = true),
    content: @Composable ColumnScope.() -> Unit
) {
    val currentColorScheme = MaterialTheme.colorScheme
    val currentTypography = MaterialTheme.typography
    val currentShapes = MaterialTheme.shapes

    val menuShape = MenuDefaults.shape
    val menuContainerColor = if (glassEffectMode == GlassEffectMode.Haze && hazeState != null) {
        Color.Transparent
    } else {
        currentColorScheme.surfaceContainer
    }

    val menuModifier = if (glassEffectMode == GlassEffectMode.Haze && hazeState != null) {
        Modifier
            .clip(menuShape)
            .hazeEffect(state = hazeState, style = HazeMaterials.ultraThin())
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
        tonalElevation = if (glassEffectMode == GlassEffectMode.Haze && hazeState != null) 0.dp else MenuDefaults.TonalElevation,
        shadowElevation = if (glassEffectMode == GlassEffectMode.Haze && hazeState != null) 0.dp else MenuDefaults.ShadowElevation
    ) {
        MaterialTheme(
            colorScheme = currentColorScheme,
            typography = currentTypography,
            shapes = currentShapes
        ) {
            content()
        }
    }
}
