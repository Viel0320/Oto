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
import androidx.compose.ui.platform.LocalContext
import com.viel.aplayer.ui.search.SearchActivity
import com.viel.aplayer.ui.settings.SettingsActivity

/**
 * 为每一次改动添加详尽的中文注释：
 * 系统导航管理容器，承载应用核心页面。
 *
 * 移除了 settings 和 search 的内联 compose 路由，改成通过启动对应的独立 Activity。
 * 同时接收 searchLauncher 以拉起独立的 SearchActivity 并且能统一回传选中的书籍 ID 进行交互。
 */
@Composable
fun APlayerNavHost(
    modifier: Modifier = Modifier,
    navController: NavHostController,
    libraryViewModel: LibraryViewModel,
    playerViewModel: PlayerViewModel,
    // 详尽的中文注释：接收独立的 DetailViewModel，用于详情页书籍选中操作
    detailViewModel: DetailViewModel,
    canStartNavigation: () -> Boolean,
    navigateBack: () -> Unit,
    // 为每一次改动添加详尽的中文注释：
    // 传递 searchLauncher，使得 HomeScreen 的搜索点击事件能够通过它启动 SearchActivity
    searchLauncher: androidx.activity.result.ActivityResultLauncher<android.content.Intent>? = null
) {
    val playerUiState by playerViewModel.uiState.collectAsStateWithLifecycle()
    val libraryUiState by libraryViewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

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
                // 为每一次改动添加详尽的中文注释：
                // 当点击搜索时，不再通过 navController 导航到 compose 路由，
                // 而是调用 searchLauncher 启动 SearchActivity 并自动应用系统默认切换动画，保持回传机制可用。
                onNavigateToSearch = {
                    if (canStartNavigation()) {
                        val intent = SearchActivity.createIntent(context)
                        searchLauncher?.launch(intent)
                    }
                },
                onLoadBook = { id: String ->
                    playerViewModel.loadBook(id)
                },
                onNavigateToPlayer = {
                    playerViewModel.setFullPlayerVisible(true)
                },
                onLibraryRootSelected = { uri -> libraryViewModel.onLibraryRootSelected(uri) },
                // 为每一次改动添加详尽的中文注释：
                // 点击设置按钮时，启动 SettingsActivity，由于设置页不需要结果回传，直接使用普通的 startActivity 启动。
                onNavigateToSettings = {
                    if (canStartNavigation()) {
                        val intent = SettingsActivity.createIntent(context)
                        context.startActivity(intent)
                    }
                },
                // 为每一次改动添加详尽的中文注释：桥接长按菜单中的标记阅读状态修改事件，将更新结果写入数据库中
                onUpdateReadStatus = { bookId, status ->
                    libraryViewModel.updateBookReadStatus(bookId, status)
                },
                // 为每一次改动添加详尽的中文注释：桥接长按菜单中的强制重建封面和元数据事件，通知 ViewModel 在后台协程执行重建
                onForceRegenerate = { bookId ->
                    libraryViewModel.forceRegenerateCoverAndMetadata(bookId)
                },
                // 为每一次改动添加详尽的中文注释：桥接长按菜单中的删除书籍事件，触发软删除以及释放文件占用。
                // 如果删除的是当前正在播放的书籍，需要先通知播放器 ViewModel 清理播放状态，关闭前台迷你播放器与全屏播放器界面，以确保端到端的前后台同步级联销毁逻辑。
                onDeleteBook = { bookId ->
                    playerViewModel.closePlayback(bookId)
                    libraryViewModel.deleteBook(bookId)
                }
            )
        }
    }
}