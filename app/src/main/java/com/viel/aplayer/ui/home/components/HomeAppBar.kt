package com.viel.aplayer.ui.home.components

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.ViewModule
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import com.viel.aplayer.R
import com.viel.aplayer.data.store.GlassEffectMode
import com.viel.aplayer.ui.common.APlayerGlassTopBar
import dev.chrisbanes.haze.HazeState

/**
 * Home App Bar Component (Overlay header rendering for the library home screen)
 *
 * Encapsulates the home header's glass surface, icon alignment, Material top app bar wiring, and title double-tap gesture.
 * The parent screen still owns scroll state and measured height storage, so this component stays focused on rendering and header-level interactions.
 */
@Composable
fun HomeAppBar(
    glassEffectMode: GlassEffectMode,
    hazeState: HazeState,
    appBarIconPadding: Dp,
    onNavigateToSearch: () -> Unit,
    onHomeViewOptionsClick: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onTitleDoubleTap: () -> Unit,
    onHeightChanged: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    // Home Top Bar Shared Chrome Reuse (Keep Home-specific behavior in slots)
    // The extracted component owns glass rendering and measurement while HomeAppBar only supplies the title gesture and home navigation actions.
    APlayerGlassTopBar(
        glassEffectMode = glassEffectMode,
        hazeState = hazeState,
        onHeightChanged = onHeightChanged,
        modifier = modifier,
        title = {
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.titleLarge.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize = 22.sp
                ),
                modifier = Modifier.pointerInput(Unit) {
                    detectTapGestures(
                        onDoubleTap = {
                            // Home Title Double Tap (Delegate scroll-to-top behavior to the parent)
                            // The component owns the gesture target but leaves grid scrolling details outside the header boundary.
                            onTitleDoubleTap()
                        }
                    )
                }
            )
        },
        navigationIcon = {
            IconButton(
                onClick = onNavigateToSearch,
                // Home Search Icon Alignment (Compensate adaptive horizontal margins)
                // The padding keeps the search icon aligned with the content rail on wide and compact window classes.
                modifier = Modifier.padding(start = appBarIconPadding)
            ) {
                Icon(
                    Icons.Rounded.Search,
                    contentDescription = stringResource(R.string.search_content_description)
                )
            }
        },
        actions = {
            IconButton(
                onClick = onHomeViewOptionsClick
            ) {
                Icon(
                    Icons.Rounded.ViewModule,
                    // Home View Options Entry (Expose catalog layout and sorting controls from the Home top bar)
                    // The entry sits immediately before Settings so display preferences are discoverable without entering the full settings overlay.
                    contentDescription = "Home view options"
                )
            }
            IconButton(
                onClick = onNavigateToSettings,
                // Home Settings Icon Alignment (Mirror the adaptive compensation on the trailing edge)
                // The padding preserves symmetric app bar affordance placement against the grid content boundary.
                modifier = Modifier.padding(end = appBarIconPadding)
            ) {
                Icon(
                    Icons.Rounded.Tune,
                    contentDescription = stringResource(R.string.settings_content_description)
                )
            }
        }
    )
}
