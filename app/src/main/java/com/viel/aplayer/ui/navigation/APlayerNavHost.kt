package com.viel.aplayer.ui.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import com.viel.aplayer.ui.home.components.HomeAppBar
import com.viel.aplayer.ui.home.components.HomeViewPreferenceDialog
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
 * System navigation management container, hosting core application pages using NavDisplay.
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
    homeViewStyle: HomeViewStyle,
    homeSortRule: HomeSortRule,
    homeSortDirection: HomeSortDirection,
    homeBookStatusFilter: HomeBookStatusFilter,
    homeDialogHazeState: HazeState? = null,
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
    val isHomeRoute = navigationState.topLevelRoute == HomeRoute
    val isHazeMode = glassEffectMode == GlassEffectMode.Haze
    val fallbackHomeTopBarHazeState = remember { HazeState() }
    val resolvedHomeTopBarHazeState = appHazeState ?: fallbackHomeTopBarHazeState
    var homeTopBarHeightPx by remember { mutableIntStateOf(0) }
    var homeTopBarScrollToTopRequest by remember { mutableIntStateOf(0) }
    var isHomeViewPreferenceDialogVisible by remember { mutableStateOf(false) }
    val navHostTraceState = "topLevelRoute=${navigationState.topLevelRoute},isHome=$isHomeRoute," +
        "homeViewDialog=$isHomeViewPreferenceDialogVisible,glass=$glassEffectMode"

    val provider = remember(libraryViewModel, playbackViewModel, bookmarkViewModel, settingsViewModel, detailViewModel, onOpenDetail, onAddLibraryRequested, onEditBookRequested, homeDialogHazeState, homeTopBarHeightPx, homeTopBarScrollToTopRequest) {
        entryProvider<NavKey> {
            entry<HomeRoute> {
                HomeScreen(
                    libraryViewModel = libraryViewModel,
                    playbackViewModel = playbackViewModel,
                    settingsViewModel = settingsViewModel,
                    detailViewModel = detailViewModel,
                    onOpenDetail = onOpenDetail,
                    homeDialogHazeState = homeDialogHazeState,
                    homeTopBarScrollToTopRequest = homeTopBarScrollToTopRequest,
                    onAddLibraryRequested = onAddLibraryRequested,
                    onEditBookRequested = onEditBookRequested
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

        if (isHomeRoute) {
            HomeAppBar(
                glassEffectMode = glassEffectMode,
                hazeState = resolvedHomeTopBarHazeState,
                onNavigateToSearch = {
                    if (canStartNavigation()) {
                        onNavigateToSearch()
                    }
                },
                onHomeViewOptionsClick = {
                    if (canStartNavigation()) {
                        isHomeViewPreferenceDialogVisible = true
                    }
                },
                onNavigateToSettings = {
                    if (canStartNavigation()) {
                        onNavigateToSettings()
                    }
                },
                onTitleDoubleTap = {
                    homeTopBarScrollToTopRequest += 1
                },
                onHeightChanged = { homeTopBarHeightPx = it }
            )
        }

        if (isHomeRoute && isHomeViewPreferenceDialogVisible) {
            HomeViewPreferenceDialog(
                selectedViewStyle = homeViewStyle,
                selectedSortRule = homeSortRule,
                selectedSortDirection = homeSortDirection,
                selectedBookStatusFilter = homeBookStatusFilter,
                hazeState = homeDialogHazeState ?: resolvedHomeTopBarHazeState,
                glassEffectMode = glassEffectMode,
                onViewStyleSelected = onHomeViewStyleSelected,
                onSortRuleSelected = onHomeSortRuleSelected,
                onSortDirectionSelected = onHomeSortDirectionSelected,
                onBookStatusFilterSelected = onHomeBookStatusFilterSelected,
                onDismissRequest = {
                    isHomeViewPreferenceDialogVisible = false
                }
            )
        }
    }
}
