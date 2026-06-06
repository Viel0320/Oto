package com.viel.aplayer.ui.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.ui.NavDisplay
import com.viel.aplayer.data.store.GlassEffectMode
import com.viel.aplayer.ui.common.theme.LocalWindowClass
import com.viel.aplayer.ui.detail.DetailViewModel
import com.viel.aplayer.ui.home.HomeScreen
import com.viel.aplayer.ui.home.LibraryViewModel
import com.viel.aplayer.ui.home.components.HomeAppBar
import com.viel.aplayer.ui.player.PlayerViewModel
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import kotlinx.serialization.Serializable

// Define Navigation Routes (Type-safe Navigation 3 Key)
// Serializable key representing the home screen route in Navigation 3.
@Serializable
data object HomeRoute : NavKey

/**
 * Navigation Host (Manage Core Screens under Navigation 3)
 *
 * System navigation management container, hosting core application pages using NavDisplay.
 */
@Composable
fun APlayerNavHost(
    modifier: Modifier = Modifier,
    navigationState: NavigationState,
    navigator: Navigator,
    libraryViewModel: LibraryViewModel,
    playerViewModel: PlayerViewModel,
    detailViewModel: DetailViewModel,
    canStartNavigation: () -> Boolean,
    searchViewModel: com.viel.aplayer.ui.search.SearchViewModel,
    // App Backdrop Source (Own the route content sampling surface inside the navigation host)
    // Mounting the source around NavDisplay lets Home chrome live as a sibling overlay that samples the route content instead of being captured inside it.
    appHazeState: HazeState? = null,
    // App Glass Mode (Gate Haze source registration from the active settings state)
    // APlayerNavHost needs the same glass mode as the app shell so source registration and Home chrome rendering stay synchronized.
    glassEffectMode: GlassEffectMode,
    // Home Dialog Backdrop Source (Route app-level sampling into Home dialogs)
    // Dialog windows need the same app-level backdrop used by Search and mini-player overlays so their blur is not clipped by Home's page-local scrolling source.
    homeDialogHazeState: HazeState? = null,
    // Settings Navigation Event (To delegate settings launch routing to upper controller)
    // Abstract callback parameter to notify parent overlay scope when user requests setting screen.
    onNavigateToSettings: () -> Unit
) {
    val isHomeRoute = navigationState.topLevelRoute == HomeRoute
    val isHazeMode = glassEffectMode == GlassEffectMode.Haze
    val windowClass = LocalWindowClass.current
    val appBarIconPadding = (windowClass.screenHorizontalPadding - 16.dp).coerceAtLeast(0.dp)
    val fallbackHomeTopBarHazeState = remember { HazeState() }
    val resolvedHomeTopBarHazeState = appHazeState ?: fallbackHomeTopBarHazeState
    var homeTopBarHeightPx by remember { mutableIntStateOf(0) }
    var homeTopBarScrollToTopRequest by remember { mutableIntStateOf(0) }

    // Setup Entry Provider (Resolve NavKeys to Screen composables)
    // Declares the mapping of serializable NavKey to their respective Compose screen contents.
    // Explicit NavKey Provider (Fix Nav3 type parameter matching issue) Explicitly parameterize entryProvider with NavKey to bypass Kotlin contravariance compile errors.
    val provider = remember(libraryViewModel, playerViewModel, detailViewModel, homeDialogHazeState, homeTopBarHeightPx, homeTopBarScrollToTopRequest) {
        entryProvider<NavKey> {
            entry<HomeRoute> {
                /*
                 * Home Route Content (Shared source scopes stay item-local)
                 *
                 * Renders Home directly so Home->Detail shared-element sources are owned by the
                 * selected recent item instead of a route-wide scope that never exits.
                 */
                HomeScreen(
                    libraryViewModel = libraryViewModel,
                    playerViewModel = playerViewModel,
                    detailViewModel = detailViewModel,
                    homeDialogHazeState = homeDialogHazeState,
                    homeTopBarHeightPx = homeTopBarHeightPx,
                    homeTopBarScrollToTopRequest = homeTopBarScrollToTopRequest
                )
            }
        }
    }

    // Render Navigation Stack (Mount NavDisplay as the sampled route source)
    // NavDisplay owns route content while HomeAppBar is mounted as a sibling overlay below, matching the app-shell sampling pattern used by floating layers.
    Box(modifier = modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .then(
                    if (isHazeMode && appHazeState != null) {
                        // NavHost Route Haze Source (Expose route content to sibling overlays)
                        // Keeping the source on NavDisplay's wrapper lets HomeAppBar and app-level dialogs sample page content without being captured inside the same source tree.
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
                appBarIconPadding = appBarIconPadding,
                onNavigateToSearch = {
                    if (canStartNavigation()) {
                        searchViewModel.setVisible(true)
                    }
                },
                onNavigateToSettings = {
                    if (canStartNavigation()) {
                        onNavigateToSettings()
                    }
                },
                onTitleDoubleTap = {
                    // Home Top Bar Scroll Event (Bridge header gesture to Home route content)
                    // The app bar is hosted by APlayerNavHost, so it sends an integer request that HomeScreenContent can consume without sharing its grid state upward.
                    homeTopBarScrollToTopRequest += 1
                },
                onHeightChanged = { homeTopBarHeightPx = it }
            )
        }
    }
}
