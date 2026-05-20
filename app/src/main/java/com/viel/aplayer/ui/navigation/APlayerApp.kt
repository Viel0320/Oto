package com.viel.aplayer.ui.navigation

import android.text.Layout
import android.text.SpannableString
import android.text.Spanned
import android.text.style.AlignmentSpan
import android.widget.Toast
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.viel.aplayer.ui.detail.DetailOverlay
import com.viel.aplayer.ui.detail.DetailViewModel
import com.viel.aplayer.ui.home.LibraryUiEvent
import com.viel.aplayer.ui.home.LibraryViewModel
import com.viel.aplayer.ui.home.ScanResultDialog
import com.viel.aplayer.ui.player.MiniPlayerActions
import com.viel.aplayer.ui.player.PlayerViewModel
import com.viel.aplayer.ui.player.components.PlayerOverlay
import com.viel.aplayer.ui.player.rememberActions
import com.viel.aplayer.ui.theme.APlayerTheme

@Composable
fun APlayerApp() {
    APlayerTheme {
        val navController = rememberNavController()
        val navBackStackEntry by navController.currentBackStackEntryAsState()
        val currentRoute = navBackStackEntry?.destination?.route

        val context = LocalContext.current
        val libraryViewModel: LibraryViewModel = viewModel()
        val playerViewModel: PlayerViewModel = viewModel()
        // 详尽的中文注释：书籍详情页独立的 ViewModel，从 LibraryViewModel 中拆分出来，使各 ViewModel 职责单一
        val detailViewModel: DetailViewModel = viewModel()

        val playerUiState by playerViewModel.uiState.collectAsStateWithLifecycle()
        val libraryUiState by libraryViewModel.uiState.collectAsStateWithLifecycle()
        val scanResult by libraryViewModel.scanResultDialogState.collectAsStateWithLifecycle()

        val canStartNavigation = rememberNavigationThrottle()

        LaunchedEffect(Unit) {
            playerViewModel.initialize(context)
        }

        LaunchedEffect(currentRoute) {
            playerViewModel.onRouteChanged()
        }

        // 详尽的中文注释：消费 LibraryViewModel 发射的一次性 UI 事件（如 Toast 消息），
        // 遵循 ViewModel 不直接操作 Android UI 组件的架构原则，
        // 所有 Toast 的构造和展示均回归 Composable 层。
        LaunchedEffect(Unit) {
            libraryViewModel.uiEvents.collect { event ->
                when (event) {
                    is LibraryUiEvent.ShowToast -> {
                        val spannable = SpannableString(event.message)
                        spannable.setSpan(
                            AlignmentSpan.Standard(Layout.Alignment.ALIGN_CENTER),
                            0, event.message.length,
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                        )
                        Toast.makeText(context, spannable, Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        val navigateBack: () -> Unit = remember(navController) {
            {
                if (canStartNavigation() && navController.previousBackStackEntry != null) {
                    navController.popBackStack()
                }
            }
        }

        // 使用扩展函数构建 Actions 对象，实现逻辑解耦与性能缓存
        val playerActions = playerViewModel.rememberActions(
            onDeleteBook = { bookId ->
                playerViewModel.closePlayback(bookId)
                // 详尽的中文注释：显式协调详情页状态清理，与 playerViewModel.closePlayback 保持一致的外层协调模式
                detailViewModel.dismissIfShowing(bookId)
                libraryViewModel.deleteBook(bookId)
                if (currentRoute != null && currentRoute != "home") {
                    navigateBack()
                }
            }
        )

        val miniPlayerActions = remember(playerViewModel) {
            MiniPlayerActions(
                onPlayPauseClick = { playerViewModel.togglePlayPause() },
                onHide = { playerViewModel.setMiniPlayerHidden(true) },
                onUnavailable = { playerViewModel.closeCurrentPlayback() }
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
                    detailViewModel = detailViewModel,
                    canStartNavigation = canStartNavigation,
                    navigateBack = navigateBack
                )

                // 详情页 Overlay
                DetailOverlay(
                    detailViewModel = detailViewModel,
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

                scanResult?.let { session ->
                    ScanResultDialog(
                        session = session,
                        onDismiss = { libraryViewModel.dismissScanResultDialog() }
                    )
                }
            }
        }
    }
}