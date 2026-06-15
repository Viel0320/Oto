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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import com.viel.aplayer.data.store.GlassEffectMode
import com.viel.aplayer.ui.common.theme.LiquidGlassStyle
import com.viel.aplayer.ui.common.theme.LocalDarkTheme
import com.viel.aplayer.ui.common.theme.glassOverlay
import dev.chrisbanes.haze.HazeState

/**
 * BlurSnackbar (Material-compatible snackbar with app-owned Haze glass rendering)
 *
 * Haze mode keeps the Material snackbar layout but routes the background through the shared
 * liquid-glass renderer used by dialogs, sheets, menus, and top bars. This avoids old raw
 * snackbar presets applying an overly dark tint when the app forces dark Haze mode.
 */
@Composable
fun BlurSnackbar(
    modifier: Modifier = Modifier,
    // Snackbar Backdrop Input (Accept the caller-owned sampling source)
    // The caller decides which page or overlay layer registers hazeSource; this component only consumes the already-resolved source.
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
    // Snackbar Width Bound (Match Material readability on wide and landscape screens)
    // The component still fills available compact width while preventing long desktop/tablet lines from becoming hard to scan.
    val constrainedModifier = modifier.widthIn(max = 480.dp)

    if (glassEffectMode == GlassEffectMode.Haze && hazeState != null) {
        // Snackbar Glass Tint (Use a light wash in forced-dark Haze mode)
        // The shared liquid renderer defaults to a dark tint, so snackbar supplies a lighter local tint to avoid reading as a black background.
        val snackbarGlassTint = if (LocalDarkTheme.current) {
            Color.White.copy(alpha = 0.10f)
        } else {
            Color.Black.copy(alpha = 0.08f)
        }
        // Haze Snackbar Glass Layer (Use the shared liquid-glass renderer instead of old raw presets)
        // Use the unified glassOverlay helper to apply shape clipping and liquid glass blur with custom tinting.
        val glassModifier = Modifier.glassOverlay(
            hazeState = hazeState,
            glassEffectMode = glassEffectMode,
            shape = shape,
            style = LiquidGlassStyle(
                tint = snackbarGlassTint,
                shape = shape
            )
        )

        // Haze Snackbar Surface (Let the effect provide the visible glass body)
        // A transparent Surface avoids stacking Material's opaque snackbar container on top of the sampled backdrop.
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
        // Material Snackbar Fallback (Use the official component when glass is disabled or unavailable)
        // This preserves platform snackbar layout, colors, and action handling outside Haze mode.
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
        // Two-Line Action Layout (Mirror Material snackbar behavior for long messages or multiple actions)
        // The action row moves below the message while preserving the same spacing contract as the previous custom snackbar body.
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
        // Single-Line Action Layout (Mirror Material snackbar density for compact feedback)
        // Message and actions stay in one row while vertical padding adapts to whether trailing actions are present.
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
