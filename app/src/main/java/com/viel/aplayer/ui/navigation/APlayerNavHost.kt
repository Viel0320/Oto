package com.viel.aplayer.ui.navigation

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.viel.aplayer.ui.home.HomeScreen
import com.viel.aplayer.ui.home.LibraryViewModel
import com.viel.aplayer.ui.player.PlayerViewModel
import com.viel.aplayer.ui.search.SearchScreen
import com.viel.aplayer.ui.settings.SettingsScreen
import com.viel.aplayer.ui.settings.SettingsViewModel

@Composable
fun APlayerNavHost(
    modifier: Modifier = Modifier,
    navController: NavHostController,
    libraryViewModel: LibraryViewModel,
    playerViewModel: PlayerViewModel,
    settingsViewModel: SettingsViewModel = viewModel(),
    canStartNavigation: () -> Boolean,
    navigateBack: () -> Unit
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
                // 详尽中文注释：从 ViewModel 预计算的 UiState 中直接传入各字段，UI 层不做任何运算
                selectedFilter = libraryUiState.selectedFilter,
                groupedByAuthor = libraryUiState.groupedByAuthor,
                recentBooks = libraryUiState.recentBooks,
                shouldShowRecentBooks = libraryUiState.shouldShowRecentBooks,
                recentTitleRes = libraryUiState.recentTitleRes,
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
            LaunchedEffect(Unit) {
                // Settings entry refreshes library-root permission status before rendering the stored roots.
                settingsViewModel.refreshLibraryRootStatuses()
            }
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