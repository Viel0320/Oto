package com.viel.aplayer.ui.navigation

import android.net.Uri
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.viel.aplayer.ui.screens.HomeScreen
import com.viel.aplayer.ui.screens.SearchScreen
import com.viel.aplayer.ui.screens.SettingsScreen
import com.viel.aplayer.ui.viewmodel.LibraryViewModel
import com.viel.aplayer.ui.viewmodel.PlayerViewModel
import com.viel.aplayer.ui.viewmodel.SettingsViewModel
import androidx.lifecycle.viewmodel.compose.viewModel

@Composable
fun APlayerNavHost(
    navController: NavHostController,
    libraryViewModel: LibraryViewModel,
    playerViewModel: PlayerViewModel,
    settingsViewModel: SettingsViewModel = viewModel(),
    canStartNavigation: () -> Boolean,
    navigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val playerUiState by playerViewModel.uiState.collectAsStateWithLifecycle()
    val libraryUiState by libraryViewModel.uiState.collectAsStateWithLifecycle()

    NavHost(
        navController = navController,
        startDestination = "home",
        modifier = modifier
    ) {
        composable("home") {
            HomeScreen(
                audiobooks = libraryUiState.audiobooks,
                selectedFilter = libraryUiState.selectedFilter,
                onFilterSelected = { libraryViewModel.setFilter(it) },
                isMiniPlayerVisible = playerUiState.hasActiveTrack,
                onNavigateToDetail = { id: String ->
                    val book = libraryUiState.audiobooks.find { it.book.id == id }
                    libraryViewModel.selectDetailBook(book)
                },
                onNavigateToSearch = {
                    if (canStartNavigation() && navController.currentBackStackEntry?.destination?.route?.startsWith("search") != true) {
                        navController.navigate("search") {
                            launchSingleTop = true
                        }
                    }
                },
                onLoadBook = { id: String ->
                    playerViewModel.loadBook(id)
                },
                onNavigateToPlayer = {
                    playerViewModel.setFullPlayerVisible(true)
                },
                onLibraryRootSelected = { uri -> libraryViewModel.onLibraryRootSelected(uri) },
                onNavigateToSettings = {
                    if (canStartNavigation()) {
                        navController.navigate("settings")
                    }
                }
            )
        }
        composable("settings") {
            val settingsState by settingsViewModel.settingsState.collectAsStateWithLifecycle()
            val libraryRoots by settingsViewModel.libraryRoots.collectAsStateWithLifecycle()
            SettingsScreen(
                onBack = navigateBack,
                onLibraryRootSelected = { uri -> libraryViewModel.onLibraryRootSelected(uri) },
                onClearHistory = { settingsViewModel.clearSearchHistory() },
                onRescan = { settingsViewModel.triggerRescan() },
                libraryRoots = libraryRoots,
                isChapterProgressMode = settingsState.isChapterProgressMode,
                onChapterProgressModeChange = { settingsViewModel.toggleChapterProgressMode(it) }
            )
        }
        composable(
            "search?q={q}",
            enterTransition = { fadeIn(animationSpec = tween(400)) },
            exitTransition = { fadeOut(animationSpec = tween(400)) },
            popEnterTransition = { fadeIn(animationSpec = tween(400)) },
            popExitTransition = { fadeOut(animationSpec = tween(400)) }
        ) { backStackEntry ->
            val initialQuery = backStackEntry.arguments?.getString("q")
            SearchScreen(
                initialQuery = initialQuery,
                onBack = navigateBack,
                onNavigateToDetail = { id: String ->
                    val book = libraryUiState.audiobooks.find { it.book.id == id }
                    libraryViewModel.selectDetailBook(book)
                },
                onLoadBook = { id: String ->
                    playerViewModel.loadBook(id)
                },
                onNavigateToPlayer = {
                    playerViewModel.setFullPlayerVisible(true)
                }
            )
        }
    }
}
