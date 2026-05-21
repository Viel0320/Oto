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
import com.viel.aplayer.ui.common.UiEvent
import com.viel.aplayer.ui.detail.DetailOverlay
import com.viel.aplayer.ui.detail.DetailViewModel
import com.viel.aplayer.ui.home.LibraryViewModel
import com.viel.aplayer.ui.home.ScanResultDialog
import com.viel.aplayer.ui.player.MiniPlayerActions
import com.viel.aplayer.ui.player.PlayerViewModel
import com.viel.aplayer.ui.player.components.PlayerOverlay
import com.viel.aplayer.ui.player.rememberActions
import com.viel.aplayer.ui.search.SearchActivity
import com.viel.aplayer.ui.theme.APlayerTheme

@Composable
fun APlayerApp(
    openPlayerOverlayRequest: Boolean = false,
    onOpenPlayerOverlayConsumed: () -> Unit = {},
    // 为每一次改动添加详尽的中文注释：
    // 从 MainActivity 传入搜索结果回传状态：待打开详情的书籍 ID。
    // 非空时触发 DetailViewModel.selectBook 打开详情 Overlay，消费后由 onPendingDetailBookIdConsumed 复位。
    pendingDetailBookId: String? = null,
    onPendingDetailBookIdConsumed: () -> Unit = {},
    // 为每一次改动添加详尽的中文注释：
    // 从 MainActivity 传入搜索结果回传状态：待立即播放的书籍 ID。
    // 非空时先 loadBook 再展开全屏播放器，消费后复位。
    pendingPlayBookId: String? = null,
    onPendingPlayBookIdConsumed: () -> Unit = {},
    // 为每一次改动添加详尽的中文注释：
    // 从 MainActivity 传入搜索结果回传状态：是否仅打开播放器 Overlay（不涉及书籍切换）。
    openPlayerFromSearchRequest: Boolean = false,
    onOpenPlayerFromSearchConsumed: () -> Unit = {},
    // 为每一次改动添加详尽的中文注释：
    // MainActivity 的 ActivityResultLauncher，用于从 NavHost/HomeScreen 启动 SearchActivity。
    // 传入 Composable 层而非直接 startActivity，是为了让回传结果能被 MainActivity 统一接收并通过 Compose State 传回来。
    searchLauncher: androidx.activity.result.ActivityResultLauncher<android.content.Intent>? = null
) {
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

        // 为本次桌面 widget 改动添加注释：消费 MainActivity 从桌面小组件转交的外部入口请求，直接切回主播放页并显示全屏播放器 overlay。
        LaunchedEffect(openPlayerOverlayRequest) {
            if (openPlayerOverlayRequest) {
                playerViewModel.setSelectedContentTab(-1)
                playerViewModel.setMiniPlayerHidden(false)
                playerViewModel.setFullPlayerVisible(true)
                onOpenPlayerOverlayConsumed()
            }
        }

        // 为每一次改动添加详尽的中文注释：
        // 消费从 SearchActivity 经由 MainActivity 传入的"打开详情 Overlay"请求。
        // pendingDetailBookId 非空时，从当前 libraryUiState.audiobooks 中找到对应书籍对象，
        // 调用 detailViewModel.selectBook 触发详情 Overlay 展开，然后立即通知 MainActivity 复位状态防止重复触发。
        LaunchedEffect(pendingDetailBookId) {
            val bookId = pendingDetailBookId ?: return@LaunchedEffect
            val book = libraryUiState.audiobooks.find { it.book.id == bookId }
            detailViewModel.selectBook(book)
            onPendingDetailBookIdConsumed()
        }

        // 为每一次改动添加详尽的中文注释：
        // 消费从 SearchActivity 经由 MainActivity 传入的"立即播放"请求。
        // 先调用 PlayerViewModel.loadBook 加载指定书籍，再展开全屏播放器 Overlay。
        LaunchedEffect(pendingPlayBookId) {
            val bookId = pendingPlayBookId ?: return@LaunchedEffect
            playerViewModel.loadBook(bookId)
            playerViewModel.setFullPlayerVisible(true)
            onPendingPlayBookIdConsumed()
        }

        // 为每一次改动添加详尽的中文注释：
        // 消费从 SearchActivity 经由 MainActivity 传入的"仅打开播放器 Overlay"请求（不切换书籍）。
        LaunchedEffect(openPlayerFromSearchRequest) {
            if (openPlayerFromSearchRequest) {
                playerViewModel.setFullPlayerVisible(true)
                onOpenPlayerFromSearchConsumed()
            }
        }

        LaunchedEffect(currentRoute) {
            playerViewModel.onRouteChanged()
        }


        // 详尽中文注释：M-19 修复 — 高频单向数据同步管道
        // 监听播放器的当前播放书籍 ID 和实时播放进度百分比。
        // 一旦它们发生变化，立刻调用 detailViewModel.updatePlaybackProgress，
        // 将高频更新推送到详情页 ViewModel 内部，由其结合 3 秒锁定保护状态进行统一调度。
        val currentBookId by playerViewModel.currentBookId.collectAsStateWithLifecycle()
        val playbackPercent by playerViewModel.currentPlaybackProgressPercent.collectAsStateWithLifecycle()
        LaunchedEffect(currentBookId, playbackPercent) {
            currentBookId?.let { bookId ->
                if (playbackPercent > 0) {
                    detailViewModel.updatePlaybackProgress(bookId, playbackPercent)
                }
            }
        }

        // 详尽的中文注释：消费 LibraryViewModel 发射的一次性 UI 事件（如 Toast 消息），
        // 遵循 ViewModel 不直接操作 Android UI 组件的架构原则，
        // 所有 Toast 的构造和展示均回归 Composable 层。
        // 重构后：匹配通用的 UiEvent.ShowToast 进行集中渲染。
        LaunchedEffect(Unit) {
            libraryViewModel.uiEvents.collect { event ->
                when (event) {
                    is UiEvent.ShowToast -> {
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

        // 为每一次改动添加详尽的中文注释：
        // 消费由当前播放器 PlayerViewModel 共享并转发过来的一次性 UI 反馈事件。
        // 直接使用标准的 Toast 进行提示，避免过度包装以保持原生的简明风格。
        LaunchedEffect(Unit) {
            playerViewModel.uiEvents.collect { event ->
                when (event) {
                    is UiEvent.ShowToast -> {
                        Toast.makeText(context, event.message, Toast.LENGTH_SHORT).show()
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
                    navigateBack = navigateBack,
                    searchLauncher = searchLauncher
                )

                // 详尽中文注释：调入详情 Overlay，解耦后的新组件通过 onNavigateToSearch 回调拉起 SearchActivity 启动 Intent。
                DetailOverlay(
                    detailViewModel = detailViewModel,
                    canStartNavigation = canStartNavigation,
                    onPlayBook = { bookId ->
                        // 详尽中文注释：M-19 修复 — 在宿主层接收并消费从详情页往上传播的播放事件。
                        // 统一加载书籍并展开全屏播放器，实现了彻底的单向数据流闭环。
                        playerViewModel.loadBook(bookId)
                        playerViewModel.setFullPlayerVisible(true)
                    },
                    onNavigateToSearch = { query ->
                        val intent = SearchActivity.createIntent(context, query)
                        searchLauncher?.launch(intent)
                    }
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
