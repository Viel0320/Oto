package com.viel.aplayer.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.ui.NavDisplay
import com.viel.aplayer.ui.detail.DetailViewModel
import com.viel.aplayer.ui.home.HomeScreen
import com.viel.aplayer.ui.home.LibraryViewModel
import com.viel.aplayer.ui.player.PlayerViewModel
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
    navigateBack: () -> Unit,
    searchViewModel: com.viel.aplayer.ui.search.SearchViewModel
) {
    // Setup Entry Provider (Resolve NavKeys to Screen composables)
    // Declares the mapping of serializable NavKey to their respective Compose screen contents.
    // Explicit NavKey Provider (Fix Nav3 type parameter matching issue) Explicitly parameterize entryProvider with NavKey to bypass Kotlin contravariance compile errors.
    val provider = remember(libraryViewModel, playerViewModel, detailViewModel, searchViewModel, canStartNavigation, navigateBack) {
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
                    searchViewModel = searchViewModel,
                    canStartNavigation = canStartNavigation,
                    navigateBack = navigateBack
                )
            }
        }
    }

    // Render Navigation Stack (Mount NavDisplay component)
    // Renders the active back stack content and intercepts the physical back gestures.
    NavDisplay(
        modifier = modifier,
        entries = navigationState.toEntries(provider),
        onBack = { navigator.goBack() }
    )
}
