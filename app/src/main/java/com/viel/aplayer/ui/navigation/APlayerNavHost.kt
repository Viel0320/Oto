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
import com.viel.aplayer.ui.detail.DetailViewModel
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
    // 详尽的中文注释：接收独立的 DetailViewModel，用于详情页书籍选中操作
    detailViewModel: DetailViewModel,
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
                    detailViewModel.selectBook(book)
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
            // 详尽的中文注释：在此绑定新增的 isCleartextTrafficAllowed 状态、开关修改动作以及级联物理删除/释放 SAF 授权的方法 (H-01, H-04)
            SettingsScreen(
                onBack = navigateBack,
                onLibraryRootSelected = { uri -> libraryViewModel.onLibraryRootSelected(uri) },
                onClearHistory = { settingsViewModel.clearSearchHistory() },
                onRescan = { settingsViewModel.triggerRescan() },
                libraryRoots = libraryRoots,
                isChapterProgressMode = settingsState.isChapterProgressMode,
                onChapterProgressModeChange = { settingsViewModel.toggleChapterProgressMode(it) },
                isCleartextTrafficAllowed = settingsState.isCleartextTrafficAllowed,
                onCleartextTrafficAllowedChange = { settingsViewModel.toggleCleartextTrafficAllowed(it) },
                onDeleteLibraryRoot = { settingsViewModel.deleteLibraryRoot(it) },
                // 为每一次改动添加详尽的中文注释：绑定自动跳过静音全局控制开关状态与更新事件
                isSkipSilenceEnabled = settingsState.isSkipSilenceEnabled,
                onSkipSilenceEnabledChange = { settingsViewModel.toggleSkipSilenceEnabled(it) },
                // 为每一次改动添加详尽的中文注释：绑定自动跳过静音判定时长阈值状态与更新事件
                skipSilenceDurationThreshold = settingsState.skipSilenceDurationThreshold,
                onSkipSilenceDurationThresholdChange = { settingsViewModel.updateSkipSilenceDurationThreshold(it) },
                // 为每一次改动添加详尽的中文注释：绑定跳过静音提示温馨通知开关状态与更新事件
                isSkipSilenceNotificationEnabled = settingsState.isSkipSilenceNotificationEnabled,
                onSkipSilenceNotificationEnabledChange = { settingsViewModel.toggleSkipSilenceNotificationEnabled(it) },
                // 为每一次改动添加详尽的中文注释：绑定睡眠定时器音量渐隐全局控制开关状态与更新事件
                isSleepFadeOutEnabled = settingsState.isSleepFadeOutEnabled,
                onSleepFadeOutEnabledChange = { settingsViewModel.toggleSleepFadeOutEnabled(it) },
                // 为每一次改动添加详尽的中文注释：绑定摇晃手机重置睡眠定时器全局控制开关状态与更新事件
                isShakeToResetEnabled = settingsState.isShakeToResetEnabled,
                onShakeToResetEnabledChange = { settingsViewModel.toggleShakeToResetEnabled(it) }
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
                    detailViewModel.selectBook(book)
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