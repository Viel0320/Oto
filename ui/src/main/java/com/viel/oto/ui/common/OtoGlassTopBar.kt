package com.viel.oto.ui.common

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
import com.viel.oto.shared.model.GlassEffectMode
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import dev.chrisbanes.haze.materials.HazeMaterials

/**
 * Reusable overlay chrome for screen headers.
 *
 * Extracts the Home top bar's measured Haze material wrapper and Material3 slot wiring so Home, Settings, and About can share identical blur behavior.
 * Callers still own title, navigation, and action content, keeping screen-specific interactions outside this shared chrome component.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalHazeMaterialsApi::class)
@Composable
fun OtoGlassTopBar(
    glassEffectMode: GlassEffectMode,
    hazeState: HazeState?,
    onHeightChanged: (Int) -> Unit,
    title: @Composable () -> Unit,
    navigationIcon: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val resolvedHazeState = hazeState.takeIf { glassEffectMode == GlassEffectMode.Haze }
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
            modifier = Modifier.fillMaxWidth(),
            windowInsets = WindowInsets.safeDrawing.exclude(WindowInsets.navigationBars).exclude(WindowInsets.ime),
            title = title,
            navigationIcon = navigationIcon,
            actions = actions,
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = if (resolvedHazeState != null) Color.Transparent else MaterialTheme.colorScheme.background,
                scrolledContainerColor = if (resolvedHazeState != null) Color.Transparent else MaterialTheme.colorScheme.background,
                titleContentColor = MaterialTheme.colorScheme.onSurface,
                navigationIconContentColor = MaterialTheme.colorScheme.onSurface,
                actionIconContentColor = MaterialTheme.colorScheme.onSurface
            )
        )
    }
}
