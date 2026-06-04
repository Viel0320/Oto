package com.viel.aplayer.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.viel.aplayer.ui.detail.DetailViewModel
import com.viel.aplayer.ui.home.HomeScreen
import com.viel.aplayer.ui.home.LibraryViewModel
import com.viel.aplayer.ui.player.PlayerViewModel

/**
 * Navigation Host (Manage Core Screens)
 *
 * System navigation management container, hosting core application pages.
 * Removed the independent compose route for settings, changing to launch the corresponding independent Activity.
 * To achieve a 100% perfect, leak-free frosted glass effect, the search page was refactored as the SearchOverlay floating layer inside the same Activity,
 * so we removed the searchLauncher field and introduced the non-independent searchViewModel.
 */
@Composable
fun APlayerNavHost(
    modifier: Modifier = Modifier,
    navController: NavHostController,
    libraryViewModel: LibraryViewModel,
    playerViewModel: PlayerViewModel,
    // Inject DetailViewModel (Select Audiobook in DetailViewModel)
    // Receive the independent DetailViewModel, used for detail page book selection operation.
    detailViewModel: DetailViewModel,
    canStartNavigation: () -> Boolean,
    navigateBack: () -> Unit,
    // Inject SearchViewModel (Seamless Search Overlay Launching)
    // Introduce the non-independent SearchViewModel to seamlessly open the search overlay inside the same Activity when long-pressed or clicked without delay.
    searchViewModel: com.viel.aplayer.ui.search.SearchViewModel
) {
    NavHost(
        navController = navController,
        startDestination = "home",
        modifier = modifier
    ) {
        composable("home") {
            // Inject Stateful HomeScreen (ViewModel Injection & Decoupling)
            // Call the refactored Stateful HomeScreen, directly injecting the ViewModels held by the navigation host.
            // Following single responsibility, NavHost no longer bears the responsibility of collecting UI State for HomeScreen and passing long parameters down.
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
