package com.viel.aplayer.ui.common

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
import com.viel.aplayer.shared.settings.GlassEffectMode
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import dev.chrisbanes.haze.materials.HazeMaterials

/**
 * Material-compatible snackbar with app-owned Haze glass rendering.
 *
 * Haze mode keeps the Material snackbar layout but routes the background through the shared
 * liquid-glass renderer used by dialogs, sheets, menus, and top bars. This avoids old raw
 * snackbar presets applying an overly dark tint when the app forces dark Haze mode.
 */
@OptIn(ExperimentalHazeMaterialsApi::class)
@Composable
fun BlurSnackbar(
    modifier: Modifier = Modifier,
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
    val constrainedModifier = modifier.widthIn(max = 480.dp)

    if (glassEffectMode == GlassEffectMode.Haze && hazeState != null) {
        val glassModifier = Modifier
            .clip(shape)
            .hazeEffect(state = hazeState, style = HazeMaterials.ultraThin())

        Surface(
            modifier = constrainedModifier.then(glassModifier),
            shape = shape,
            color = Color.Transparent,
            contentColor = contentColor,
            shadowElevation = 0.dp,
            tonalElevation = 0.dp
        ) {
            BlurSnackbarContent(
                action = action,
                dismissAction = dismissAction,
                actionOnNewLine = actionOnNewLine,
                content = content
            )
        }
    } else {
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

@Composable
private fun BlurSnackbarContent(
    action: @Composable (() -> Unit)?,
    dismissAction: @Composable (() -> Unit)?,
    actionOnNewLine: Boolean,
    content: @Composable () -> Unit
) {
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
                    .padding(vertical = if (action != null || dismissAction != null) 10.dp else 14.dp)
            ) {
                content()
            }

            if (action != null) action()
            if (dismissAction != null) dismissAction()
        }
    }
}
