package com.viel.aplayer.ui.common

// Setup Haze Snackbar Integration (Replace miuix-blur with dev.chrisbanes.haze) Replaced miuix backdrop APIs with HazeState, hazeChild, and HazeMaterials.
// Import Clip Extension (Fix unresolved clip extension reference) Add explicit draw.clip import to allow using Modifier.clip.
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import com.viel.aplayer.data.store.GlassEffectMode
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import dev.chrisbanes.haze.materials.HazeMaterials

/**
 * BlurSnackbar — A generic Snackbar wrapper supporting real-time dual-state switching between Material 3 native styling and Haze frosted glass.
 *
 * Core Principles:
 * 1. Color Adaptation and Blur:
 *    - Haze mode: Sets the Surface container color to fully transparent, relying on hazeChild to render the blurred background.
 *    - Material mode: Uses standard native colors and container corner radiuses to guarantee the peak performance and pure experience of native styling.
 */
@OptIn(ExperimentalHazeMaterialsApi::class)
@Composable
fun BlurSnackbar(
    modifier: Modifier = Modifier,
    // Support Nullable HazeState (Provide fallback when hazeState is not ready)
    // Make hazeState optional and default to null so the snackbar can degrade gracefully in previews or when parent has no blur context.
    hazeState: HazeState? = null,
    glassEffectMode: GlassEffectMode,
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

    // Align with the Haze mode, using hazeChild on the fly to render the frosted glass effect when hazeState is available.
    if (glassEffectMode == GlassEffectMode.Haze && hazeState != null) {
        // Obtain the current dark mode status of the system for dual-state frosted glass color adaptation.
        val glassModifier = Modifier
            // Remove Specular and Border (Clean up glass effect decoration) Remove extra linear gradient background overlay and border properties for minimalist design.
            // Clip snackbar shape before applying hazeChild
            .clip(shape)
            .hazeEffect(
                state = hazeState,
                style = HazeMaterials.ultraThick()
            )

        // Custom shadowless Surface.
        //
        // Force shadow and tonal elevation to 0.dp to eliminate black edge projection artifacts.
        // It draws the blurred background and adaptive light/dark semi-transparent base color by mounting Haze, achieving a gorgeous, clear, and high-end frosted glass effect.
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
