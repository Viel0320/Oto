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
import com.viel.aplayer.ui.settings.SettingsActivity

/**
 * 为每一次改动添加详尽的中文注释：
 * 系统导航管理容器，承载应用核心页面。
 *
 * 移除了 settings 的独立 compose 路由，改成通过启动对应的独立 Activity。
 * 其中，为了实现 100% 完美的防穿帮毛玻璃效果，搜索页重构为在同一个 Activity 内部的 SearchOverlay 悬浮层，
 * 因此我们移除了 searchLauncher 字段，引入了非独立的 searchViewModel。
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
    // 引入非独立的 SearchViewModel，点击搜索按钮时将通过修改其显隐状态来打开悬浮层，
    // 能够零延迟地共享全局 appBlurBackdrop 毛玻璃模糊背景，并且 100% 杜绝桌面穿帮问题。
    searchViewModel: com.viel.aplayer.ui.search.SearchViewModel
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
                // 详尽中文注释：从 ViewModel 预计算 of UiState 中直接传入各字段，UI 层不做任何运算
                selectedFilter = libraryUiState.selectedFilter,
                groupedByAuthor = libraryUiState.groupedByAuthor,
                recentBooks = libraryUiState.recentBooks,
                shouldShowRecentBooks = libraryUiState.shouldShowRecentBooks,
                recentTitleRes = libraryUiState.recentTitleRes,
                onFilterSelected = { libraryViewModel.setFilter(it) },
                isMiniPlayerVisible = playerUiState.hasActiveTrack,
                // 为每一次改动添加详尽的中文注释：把设置页持久化的玻璃效果模式传入首页，控制长按操作 Dialog 的 Material/miuix-blur 切换。
                glassEffectMode = libraryUiState.glassEffectMode,
                onNavigateToDetail = { id: String ->
                    val book = libraryUiState.audiobooks.find { it.book.id == id }
                    detailViewModel.selectBook(book)
                },
                // 为每一次改动添加详尽的中文注释：
                // 点击搜索按钮时，不再拉起独立的 Activity（以防窗口模糊透出系统桌面壁纸），
                // 而是直接调用 searchViewModel.setVisible(true)，以展开同一个 Activity 内部的 SearchOverlay 搜索悬浮层，
                // 以获得完全真实的、共享全局 appBlurBackdrop 采样源的超凡磨砂玻璃透光体验。
                onNavigateToSearch = {
                    if (canStartNavigation()) {
                        searchViewModel.setVisible(true)
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
