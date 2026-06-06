package com.viel.aplayer.ui.home.components

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.exclude
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import com.viel.aplayer.R
import com.viel.aplayer.data.store.GlassEffectMode
import com.viel.aplayer.ui.common.theme.LiquidGlassBorderMode
import com.viel.aplayer.ui.common.theme.LiquidGlassStyle
import com.viel.aplayer.ui.common.theme.liquidGlassCompatEffect
import dev.chrisbanes.haze.HazeState

/**
 * Home App Bar Component (Overlay header rendering for the library home screen)
 *
 * Encapsulates the home header's glass surface, icon alignment, Material top app bar wiring, and title double-tap gesture.
 * The parent screen still owns scroll state and measured height storage, so this component stays focused on rendering and header-level interactions.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeAppBar(
    glassEffectMode: GlassEffectMode,
    hazeState: HazeState,
    appBarIconPadding: Dp,
    onNavigateToSearch: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onTitleDoubleTap: () -> Unit,
    onHeightChanged: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val isBlur = glassEffectMode == GlassEffectMode.Haze
    val topAppBarGlassModifier = if (isBlur) {
        // Home App Bar Haze Surface (Create an isolated measured wrapper for glass rendering)
        // The wrapper reports its pixel height to the parent content padding cache while drawing the haze surface over the scrolling bookshelf backdrop.
        modifier
            .fillMaxWidth()
            .onSizeChanged { onHeightChanged(it.height) }
            .liquidGlassCompatEffect(
                state = hazeState,
                style = LiquidGlassStyle(
                    shape = RectangleShape,
                    // Home Top Bar Bottom Edge (Limit liquid glass chrome to a single lower separator)
                    // The top bar occupies the full screen width and system inset edge, so only the bottom boundary should draw a visible liquid glass stroke.
                    borderMode = LiquidGlassBorderMode.BottomEdge
                )
            )
    } else {
        // Home App Bar Static Surface (Measure the non-glass header with the same bounds contract)
        // Keeping the measurement path identical prevents content padding drift when the user switches between glass and Material background modes.
        modifier
            .fillMaxWidth()
            .onSizeChanged { onHeightChanged(it.height) }
    }

    Box(modifier = topAppBarGlassModifier) {
        CenterAlignedTopAppBar(
            // Home App Bar Placement (Draw inside the overlay wrapper instead of the Scaffold slot)
            // The header keeps Material top app bar behavior while allowing grid content to scroll underneath the independently sampled glass layer.
            modifier = Modifier.fillMaxWidth(),
            // Home App Bar Insets (Exclude keyboard and navigation bar insets from header measurement)
            // The header follows safe drawing for status and cutout areas while remaining stable during IME visibility changes.
            windowInsets = WindowInsets.safeDrawing.exclude(WindowInsets.navigationBars).exclude(WindowInsets.ime),
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
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = if (isBlur) Color.Transparent else MaterialTheme.colorScheme.background,
                scrolledContainerColor = if (isBlur) Color.Transparent else MaterialTheme.colorScheme.background
            )
        )
    }
}
