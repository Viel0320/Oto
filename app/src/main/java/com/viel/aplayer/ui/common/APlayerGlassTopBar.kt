package com.viel.aplayer.ui.common

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.exclude
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.layout.onSizeChanged
import com.viel.aplayer.data.store.GlassEffectMode
import com.viel.aplayer.ui.common.theme.LiquidGlassBorderMode
import com.viel.aplayer.ui.common.theme.LiquidGlassStyle
import com.viel.aplayer.ui.common.theme.glassOverlay
import dev.chrisbanes.haze.HazeState

/**
 * APlayer Glass Top Bar (Reusable overlay chrome for screen headers)
 *
 * Extracts the Home top bar's measured liquid-glass wrapper and Material3 slot wiring so Home, Settings, and About can share identical blur behavior.
 * Callers still own title, navigation, and action content, keeping screen-specific interactions outside this shared chrome component.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun APlayerGlassTopBar(
    glassEffectMode: GlassEffectMode,
    hazeState: HazeState?,
    onHeightChanged: (Int) -> Unit,
    title: @Composable () -> Unit,
    navigationIcon: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val resolvedHazeState = hazeState.takeIf { glassEffectMode == GlassEffectMode.Haze }
    // Glass Top Bar Surface (Extracted Home overlay behavior)
    // The measured wrapper applies liquid glass over a sibling content source while reporting height for scroll-content padding reservation.
    // Use the unified glassOverlay helper to apply RectangleShape clipping and backdrop blur in a single pipeline.
    val topBarSurfaceModifier = modifier
        .fillMaxWidth()
        .onSizeChanged { onHeightChanged(it.height) }
        .glassOverlay(
            hazeState = resolvedHazeState,
            glassEffectMode = glassEffectMode,
            shape = RectangleShape,
            style = LiquidGlassStyle(
                shape = RectangleShape,
                borderMode = LiquidGlassBorderMode.None
            )
        )

    Box(modifier = topBarSurfaceModifier) {
        CenterAlignedTopAppBar(
            // Glass Top Bar Placement (Render Material chrome inside the measured overlay wrapper)
            // The wrapper handles blur and height measurement while Material3 continues to own standard top bar slot layout.
            modifier = Modifier.fillMaxWidth(),
            // Glass Top Bar Insets (Stabilize header measurement)
            // Follow status/cutout safe drawing and ignore navigation/IME changes so overlay height remains stable during keyboard transitions.
            windowInsets = WindowInsets.safeDrawing.exclude(WindowInsets.navigationBars).exclude(WindowInsets.ime),
            title = title,
            navigationIcon = navigationIcon,
            actions = actions,
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = if (resolvedHazeState != null) Color.Transparent else MaterialTheme.colorScheme.background,
                scrolledContainerColor = if (resolvedHazeState != null) Color.Transparent else MaterialTheme.colorScheme.background,
                titleContentColor = MaterialTheme.colorScheme.onSurface,
                // Top Bar Icon Color Unification (Use app-wide surface foreground)
                // Navigation and action icons share onSurface so caller slots do not inherit inconsistent Material3 defaults.
                navigationIconContentColor = MaterialTheme.colorScheme.onSurface,
                actionIconContentColor = MaterialTheme.colorScheme.onSurface
            )
        )
    }
}
