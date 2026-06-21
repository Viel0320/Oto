package com.viel.aplayer.ui.home.components

import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.ui.unit.sp
import com.viel.aplayer.R
import com.viel.aplayer.shared.settings.GlassEffectMode
import com.viel.aplayer.ui.common.APlayerGlassTopBar
import dev.chrisbanes.haze.HazeState

/**
 * Overlay header rendering for the library home screen.
 *
 * Encapsulates the home header's glass surface, icon alignment, Material top app bar wiring, and title double-tap gesture.
 * The parent screen still owns scroll state and measured height storage, so this component stays focused on rendering and header-level interactions.
 */
@Composable
fun HomeAppBar(
    glassEffectMode: GlassEffectMode,
    hazeState: HazeState,
    onNavigateToSearch: () -> Unit,
    onHomeViewOptionsClick: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onTitleDoubleTap: () -> Unit,
    onHeightChanged: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val homeViewOptionsContentDescription = stringResource(R.string.home_view_options_content_description)

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
                            onTitleDoubleTap()
                        }
                    )
                }
            )
        },
        navigationIcon = {
            IconButton(
                onClick = onNavigateToSearch,
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
                    contentDescription = homeViewOptionsContentDescription
                )
            }
            IconButton(
                onClick = onNavigateToSettings,
            ) {
                Icon(
                    Icons.Rounded.Tune,
                    contentDescription = stringResource(R.string.settings_title)
                )
            }
        }
    )
}
