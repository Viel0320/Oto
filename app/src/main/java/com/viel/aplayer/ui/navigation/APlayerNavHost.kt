package com.viel.aplayer.ui.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.ui.NavDisplay
import com.viel.aplayer.shared.settings.GlassEffectMode
import com.viel.aplayer.shared.settings.HomeBookStatusFilter
import com.viel.aplayer.shared.settings.HomeSortDirection
import com.viel.aplayer.shared.settings.HomeSortRule
import com.viel.aplayer.shared.settings.HomeViewStyle
import com.viel.aplayer.ui.common.uiPerformanceTrace
import com.viel.aplayer.ui.detail.DetailViewModel
import com.viel.aplayer.ui.home.HomeScreen
import com.viel.aplayer.ui.home.LibraryViewModel
import com.viel.aplayer.ui.player.BookmarkViewModel
import com.viel.aplayer.ui.player.PlaybackViewModel
import com.viel.aplayer.ui.player.PlayerSettingsViewModel
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import kotlinx.serialization.Serializable

@Serializable
data object HomeRoute : NavKey

/**
 * Manage Core Screens under Navigation 3.
 *
 * System navigation management container, hosting core application pages using NavDisplay. The Home
 * top bar and view-preference dialog are owned by [HomeScreen] itself so each page controls its own
 * chrome, rather than being mounted globally outside [NavDisplay].
 */
@Composable
fun APlayerNavHost(
    modifier: Modifier = Modifier,
    navigationState: NavigationState,
    navigator: Navigator,
    libraryViewModel: LibraryViewModel,
    playbackViewModel: PlaybackViewModel,
    bookmarkViewModel: BookmarkViewModel,
    settingsViewModel: PlayerSettingsViewModel,
    detailViewModel: DetailViewModel,
    canStartNavigation: () -> Boolean,
    appHazeState: HazeState? = null,
    glassEffectMode: GlassEffectMode,
    onNavigateToSettings: () -> Unit,
    onNavigateToSearch: () -> Unit,
    onAddLibraryRequested: () -> Unit,
    onEditBookRequested: (String) -> Unit,
    onOpenDetail: (DetailOpenRequest) -> Unit,
    onHomeViewStyleSelected: (HomeViewStyle) -> Unit,
    onHomeSortRuleSelected: (HomeSortRule) -> Unit,
    onHomeSortDirectionSelected: (HomeSortDirection) -> Unit,
    onHomeBookStatusFilterSelected: (HomeBookStatusFilter) -> Unit
) {
    val isHazeMode = glassEffectMode == GlassEffectMode.Haze
    val navHostTraceState = "topLevelRoute=${navigationState.topLevelRoute},glass=$glassEffectMode"

    val provider = remember(libraryViewModel, playbackViewModel, bookmarkViewModel, settingsViewModel, detailViewModel, onOpenDetail, onAddLibraryRequested, onEditBookRequested, onNavigateToSettings, onNavigateToSearch, onHomeViewStyleSelected, onHomeSortRuleSelected, onHomeSortDirectionSelected, onHomeBookStatusFilterSelected) {
        entryProvider<NavKey> {
            entry<HomeRoute> {
                HomeScreen(
                    libraryViewModel = libraryViewModel,
                    playbackViewModel = playbackViewModel,
                    settingsViewModel = settingsViewModel,
                    detailViewModel = detailViewModel,
                    canStartNavigation = canStartNavigation,
                    onOpenDetail = onOpenDetail,
                    onNavigateToSettings = onNavigateToSettings,
                    onNavigateToSearch = onNavigateToSearch,
                    onAddLibraryRequested = onAddLibraryRequested,
                    onEditBookRequested = onEditBookRequested,
                    onHomeViewStyleSelected = onHomeViewStyleSelected,
                    onHomeSortRuleSelected = onHomeSortRuleSelected,
                    onHomeSortDirectionSelected = onHomeSortDirectionSelected,
                    onHomeBookStatusFilterSelected = onHomeBookStatusFilterSelected
                )
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .uiPerformanceTrace(
                node = "APlayerNavHost",
                route = "NavHost",
                state = navHostTraceState
            )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .then(
                    if (isHazeMode && appHazeState != null) {
                        Modifier.hazeSource(appHazeState)
                    } else {
                        Modifier
                    }
                )
        ) {
            NavDisplay(
                modifier = Modifier.fillMaxSize(),
                entries = navigationState.toEntries(provider),
                onBack = { navigator.goBack() }
            )
        }
    }
}
