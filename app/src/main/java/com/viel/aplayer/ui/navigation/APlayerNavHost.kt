package com.viel.aplayer.ui.navigation

// Import HomeBookStatusFilter (Brings in the relocated type-safe availability filter from the data store layer)
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.ui.NavDisplay
import com.viel.aplayer.data.store.GlassEffectMode
import com.viel.aplayer.data.store.HomeBookStatusFilter
import com.viel.aplayer.data.store.HomeSortDirection
import com.viel.aplayer.data.store.HomeSortRule
import com.viel.aplayer.data.store.HomeViewStyle
import com.viel.aplayer.ui.common.layout.LocalAppWindowSizeClass
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
    playbackViewModel: PlaybackViewModel,
    bookmarkViewModel: BookmarkViewModel,
    settingsViewModel: PlayerSettingsViewModel,
    detailViewModel: DetailViewModel,
    canStartNavigation: () -> Boolean,
    searchViewModel: com.viel.aplayer.ui.search.SearchViewModel,
    // App Backdrop Source (Own the route content sampling surface inside the navigation host)
    // Mounting the source around NavDisplay lets Home chrome live as a sibling overlay that samples the route content instead of being captured inside it.
    appHazeState: HazeState? = null,
    // App Glass Mode (Gate Haze source registration from the active settings state)
    // APlayerNavHost needs the same glass mode as the app shell so source registration and Home chrome rendering stay synchronized.
    glassEffectMode: GlassEffectMode,
    // Home View Style Selection (Current catalog renderer preference for the Home app bar dialog)
    // The navigation host owns the Home top bar, so it also receives the selected value needed by the adjacent display-preference dialog.
    homeViewStyle: HomeViewStyle,
    // Home Sort Rule Selection (Current catalog grouping pivot for the Home app bar dialog)
    // Passing this value keeps the dialog stateless while the Home catalog policy owns mixed-script ordering.
    homeSortRule: HomeSortRule,
    // Home Sort Direction Selection (Current in-cluster order for the Home app bar dialog)
    // The navigation host passes this through without altering the fixed C/J/K/E/Other cluster sequence.
    homeSortDirection: HomeSortDirection,
    // Home Book Status Filter Selection (Current availability filter for the Home app bar dialog)
    // The navigation host owns dialog chrome only, so it receives and forwards this value without filtering the catalog itself.
    homeBookStatusFilter: HomeBookStatusFilter,
    // Home Dialog Backdrop Source (Route app-level sampling into Home dialogs)
    // Dialog windows need the same app-level backdrop used by Search and mini-player overlays so their blur is not clipped by Home's page-local scrolling source.
    homeDialogHazeState: HazeState? = null,
    // Settings Navigation Event (To delegate settings launch routing to upper controller)
    // Abstract callback parameter to notify parent overlay scope when user requests setting screen.
    onNavigateToSettings: () -> Unit,
    // Home Add Library Event (Delegate empty-state source creation to the app shell)
    // The NavHost only bridges the Home FAB intent; SettingsDialogHost and activity result launchers remain owned by APlayerApp.
    onAddLibraryRequested: () -> Unit,
    // Home Edit Book Event (Delegate action-menu edits to the app shell)
    // EditBookRoute is mounted beside other overlays in APlayerApp, so the NavHost only forwards the selected book id.
    onEditBookRequested: (String) -> Unit,
    // Detail Open Request (Route Home detail intents through the app-level transition gate)
    // APlayerNavHost owns Home route composition, while APlayerApp owns the overlay transition gate that protects shared-element return chains.
    onOpenDetail: (DetailOpenRequest) -> Unit,
    // Home View Style Selection Callback (Persist display renderer changes through the parent ViewModel)
    // APlayerNavHost only hosts the top-bar dialog and delegates actual preference writes upward.
    onHomeViewStyleSelected: (HomeViewStyle) -> Unit,
    // Home Sort Rule Selection Callback (Persist grouping/order pivot changes through the parent ViewModel)
    // The ViewModel recalculates sorted sections after DataStore emits the updated rule.
    onHomeSortRuleSelected: (HomeSortRule) -> Unit,
    // Home Sort Direction Selection Callback (Persist in-cluster direction changes through the parent ViewModel)
    // Direction updates re-run sorting in LibraryViewModel without changing the script cluster order.
    onHomeSortDirectionSelected: (HomeSortDirection) -> Unit,
    // Home Book Status Filter Callback (Persist availability filter changes through the parent ViewModel)
    // The ViewModel applies the filter to its Home scene projection after the DataStore-backed state updates.
    onHomeBookStatusFilterSelected: (HomeBookStatusFilter) -> Unit
) {
    val isHomeRoute = navigationState.topLevelRoute == HomeRoute
    val isHazeMode = glassEffectMode == GlassEffectMode.Haze
    val windowClass = LocalAppWindowSizeClass.current
    val appBarIconPadding = (windowClass.screenHorizontalPadding - 16.dp).coerceAtLeast(0.dp)
    val fallbackHomeTopBarHazeState = remember { HazeState() }
    val resolvedHomeTopBarHazeState = appHazeState ?: fallbackHomeTopBarHazeState
    var homeTopBarHeightPx by remember { mutableIntStateOf(0) }
    var homeTopBarScrollToTopRequest by remember { mutableIntStateOf(0) }
    // Home View Dialog Visibility (Local chrome state for the top-bar display preferences entry)
    // This state belongs to the navigation host because HomeAppBar is mounted here rather than inside HomeContent.
    var isHomeViewPreferenceDialogVisible by remember { mutableStateOf(false) }

    // Setup Entry Provider (Resolve NavKeys to Screen composables)
    // Declares the mapping of serializable NavKey to their respective Compose screen contents.
    // Explicit NavKey Provider (Fix Nav3 type parameter matching issue) Explicitly parameterize entryProvider with NavKey to bypass Kotlin contravariance compile errors.
    val provider = remember(libraryViewModel, playbackViewModel, bookmarkViewModel, settingsViewModel, detailViewModel, onOpenDetail, onAddLibraryRequested, onEditBookRequested, homeDialogHazeState, homeTopBarHeightPx, homeTopBarScrollToTopRequest) {
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
                    playbackViewModel = playbackViewModel,
                    settingsViewModel = settingsViewModel,
                    detailViewModel = detailViewModel,
                    onOpenDetail = onOpenDetail,
                    homeDialogHazeState = homeDialogHazeState,
                    homeTopBarHeightPx = homeTopBarHeightPx,
                    homeTopBarScrollToTopRequest = homeTopBarScrollToTopRequest,
                    onAddLibraryRequested = onAddLibraryRequested,
                    onEditBookRequested = onEditBookRequested
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
                    // Home Top Bar Scroll Event (Bridge header gesture to Home route content)
                    // The app bar is hosted by APlayerNavHost, so it sends an integer request that HomeContent can consume without sharing its grid state upward.
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
                // Home View Dialog Backdrop (Prefer the app-level Home dialog haze source)
                // Falls back to the top-bar haze state in isolated hosts so previews and tests can still render the dialog safely.
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
