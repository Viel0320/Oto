package com.viel.aplayer.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.viel.aplayer.ui.action.MiniPlayerActions
import com.viel.aplayer.ui.action.PlayerNavigationActions
import com.viel.aplayer.ui.action.rememberActions
import com.viel.aplayer.ui.components.DetailOverlay
import com.viel.aplayer.ui.components.PlayerOverlay
import com.viel.aplayer.ui.navigation.APlayerNavHost
import com.viel.aplayer.ui.theme.APlayerTheme
import com.viel.aplayer.ui.utils.rememberNavigationThrottle
import com.viel.aplayer.ui.viewmodel.LibraryViewModel
import com.viel.aplayer.ui.viewmodel.PlayerViewModel

@Composable
fun APlayerApp() {
    APlayerTheme {
        val navController = rememberNavController()
        val navBackStackEntry by navController.currentBackStackEntryAsState()
        val currentRoute = navBackStackEntry?.destination?.route

        val context = LocalContext.current
        val libraryViewModel: LibraryViewModel = viewModel()
        val playerViewModel: PlayerViewModel = viewModel()

        val canStartNavigation = rememberNavigationThrottle()

        LaunchedEffect(Unit) {
            playerViewModel.initialize(context)
        }

        LaunchedEffect(currentRoute) {
            playerViewModel.onRouteChanged()
        }

        // 使用扩展函数构建 Actions 对象，实现逻辑解耦与性能缓存
        val playerActions = playerViewModel.rememberActions()

        val navigateBack: () -> Unit = remember(navController) {
            {
                if (canStartNavigation() && navController.previousBackStackEntry != null) {
                    navController.popBackStack()
                }
            }
        }

        val miniPlayerActions = remember(playerViewModel) {
            MiniPlayerActions(
                onPlayPauseClick = { playerViewModel.togglePlayPause() },
                onHide = { playerViewModel.setMiniPlayerHidden(true) }
            )
        }
        val playerNavigationActions = remember(navController, playerViewModel) {
            PlayerNavigationActions(
                onMinimize = { playerViewModel.setFullPlayerVisible(false) },
                onClose = { playerViewModel.setFullPlayerVisible(false) },
                onBookmarksClick = {
                    playerViewModel.setSelectedContentTab(0)
                    playerViewModel.setFullPlayerVisible(true)
                },
                onSubtitlesClick = {
                    playerViewModel.setSelectedContentTab(1)
                    playerViewModel.setFullPlayerVisible(true)
                },
                onRelatedClick = {
                    playerViewModel.setSelectedContentTab(2)
                    playerViewModel.setFullPlayerVisible(true)
                },
                onNavigateToNewPlayer = { playerViewModel.setFullPlayerVisible(true) }
            )
        }

        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                APlayerNavHost(
                    navController = navController,
                    libraryViewModel = libraryViewModel,
                    playerViewModel = playerViewModel,
                    canStartNavigation = canStartNavigation,
                    navigateBack = navigateBack
                )

                // 详情页 Overlay
                DetailOverlay(
                    libraryViewModel = libraryViewModel,
                    playerViewModel = playerViewModel,
                    navController = navController,
                    canStartNavigation = canStartNavigation
                )

                PlayerOverlay(
                    playerViewModel = playerViewModel,
                    playerActions = playerActions,
                    miniPlayerActions = miniPlayerActions,
                    playerNavigationActions = playerNavigationActions,
                    currentRoute = currentRoute
                )
            }
        }
    }
}
