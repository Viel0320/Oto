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
import androidx.compose.ui.layout.onSizeChanged
import com.viel.aplayer.data.store.GlassEffectMode
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import dev.chrisbanes.haze.materials.HazeMaterials

/**
 * APlayer Glass Top Bar (Reusable overlay chrome for screen headers)
 *
 * Extracts the Home top bar's measured Haze material wrapper and Material3 slot wiring so Home, Settings, and About can share identical blur behavior.
 * Callers still own title, navigation, and action content, keeping screen-specific interactions outside this shared chrome component.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalHazeMaterialsApi::class)
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
    // Haze Top Bar Surface (Extracted Home overlay behavior)
    // The measured wrapper applies HazeMaterials.thin over a sibling content source while reporting height for scroll-content padding reservation.
    val topBarSurfaceModifier = modifier
        .fillMaxWidth()
        .onSizeChanged { onHeightChanged(it.height) }
        .then(
            if (resolvedHazeState != null) {
                Modifier.hazeEffect(state = resolvedHazeState, style = HazeMaterials.thin())
            } else {
                Modifier
            }
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
