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
    // 接收独立的 DetailViewModel，用于详情页书籍选中操作
    detailViewModel: DetailViewModel,
    canStartNavigation: () -> Boolean,
    navigateBack: () -> Unit,
    // 详尽的中文注释：引入非独立的 SearchViewModel，用于长按或点击搜索时无延迟展开同一个 Activity 内的搜索悬浮层
    searchViewModel: com.viel.aplayer.ui.search.SearchViewModel
) {
    NavHost(
        navController = navController,
        startDestination = "home",
        modifier = modifier
    ) {
        composable("home") {
            // 详尽的中文注释：调用重构后的 Stateful HomeScreen，直接将导航宿主持有的各 ViewModel 注入。
            // 遵循单一职责，NavHost 不再承担为 HomeScreen 收集 UI State 并进行长参下发的职责。
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
