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
import com.viel.aplayer.ui.theme.APlayerTheme
import com.viel.aplayer.data.store.GlassEffectMode
import com.viel.aplayer.ui.edit.EditBookOverlay
import com.viel.aplayer.ui.edit.EditBookViewModel

// 为每一次改动添加详尽的中文注释：
// 引入 Haze 磨砂玻璃的核心依赖组件，以便能在同一个 Activity 内对底层 APlayerNavHost 进行高保真毛玻璃模糊采样。
import dev.chrisbanes.haze.rememberHazeState
import dev.chrisbanes.haze.hazeSource

@Composable
fun APlayerApp(
    openPlayerOverlayRequest: Boolean = false,
    onOpenPlayerOverlayConsumed: () -> Unit = {}
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
        // 为每一次改动添加详尽的中文注释：实例化书籍元数据编辑独立的 ViewModel，交由当前 Activity 统一承载与销毁。
        val editViewModel: EditBookViewModel = viewModel()
        
        // 为每一次改动添加详尽的中文注释：
        // 实例化非独立的 SearchViewModel，由当前 Activity 统一承载与销毁。
        val searchViewModel: com.viel.aplayer.ui.search.SearchViewModel = viewModel()

        val playerUiState by playerViewModel.uiState.collectAsStateWithLifecycle()
        val libraryUiState by libraryViewModel.uiState.collectAsStateWithLifecycle()
        val scanResult by libraryViewModel.scanResultDialogState.collectAsStateWithLifecycle()

        val canStartNavigation = rememberNavigationThrottle()

        // 为每一次改动添加详尽的中文注释：
        // 实例化全局共享的 HazeState 采样源。
        // 当用户全局开启 Haze 磨砂玻璃效果时，整个 APlayerNavHost 容器会被作为采样源挂载，
        // 从而使得位于其上方悬浮的迷你播放器 (CompactMediaPlayer) 以及非独立的搜索悬浮层 (SearchOverlay)
        // 能够以 100% 实时的超高性能直接模糊下方的 HomeScreen 主页面卡片，杜绝一切由于跨 Activity 物理模糊导致的系统桌面穿帮隐患。
        val appHazeState = rememberHazeState()

        // 为每一次改动添加详尽的中文注释：
        // 实例化专门用来采集整个详情页（DetailOverlay）视觉渲染画面的 detailHazeState 采样源。
        // 当编辑悬浮层 (EditBookOverlay) 弹出覆盖在详情页之上时，能通过 detailHazeState
        // 渲染出把底下详情页上的文字、封面卡片和按钮等融为一体的极其精致的高斯磨砂毛玻璃视觉背景。
        val detailHazeState = rememberHazeState()

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

        // 为每一次改动添加详尽的中文注释：
        // 恢复最顶级干净的 Surface 布局，用以完全避免悬浮高度和背景采样层在键盘弹起时被迫消费 bottom padding 的截断与穿帮。
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            // 为每一次改动添加详尽的中文注释：
            // 在最外层使用一个没有挂载 hazeSource 的全屏顶级 Box 容器，仅作为所有悬浮层的坐标对齐和兄弟节点布局容器。
            // 这能够彻底隔离 hazeSource 采样源的层级，避免因为悬浮层在内部使用 hazeEffect 采样自身父容器而导致无限递归死锁渲染失效。
            Box(
                modifier = Modifier.fillMaxSize()
            ) {
                // 为每一次改动添加详尽的中文注释：
                // 使用独立的 Box 容器专门包裹底层 APlayerNavHost（即 HomeScreen 主页面卡片所在的主视图），并在此 Box 上挂载 Modifier.hazeSource(state = appHazeState)。
                // 这样，当用户在全局设置中开启 Haze 效果时，appHazeState 采样源只采集主导航页的画面数据，
                // 而上方的 DetailOverlay、PlayerOverlay、SearchOverlay 则以同级兄弟节点形式存在于其外部。
                // 这样既能实现完全的 Z 轴遮盖与高斯模糊渲染，又彻底打破了“子采样父”导致的图形绘制死锁，使磨砂玻璃视效完美还原。
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .then(
                            if (libraryUiState.glassEffectMode == GlassEffectMode.Haze) {
                                Modifier.hazeSource(state = appHazeState)
                            } else {
                                Modifier
                            }
                        )
                ) {
                    // 为每一次改动添加详尽的中文注释：最底层系统导航管理容器，主导航页（HomeScreen 包含其自己的局部 Scaffold 以自备底部 Insets 避让）
                    APlayerNavHost(
                        navController = navController,
                        libraryViewModel = libraryViewModel,
                        playerViewModel = playerViewModel,
                        detailViewModel = detailViewModel,
                        canStartNavigation = canStartNavigation,
                        navigateBack = navigateBack,
                        searchViewModel = searchViewModel
                    )
                }

                // 为每一次改动添加详尽的中文注释：
                // 详情页悬浮层 (DetailOverlay)。现在作为同级兄弟节点挂载于 hazeSource 外部，避免模糊死锁问题。
                // 彻底废弃原本的拉起 SearchActivity 的 Intent 启动契约，改用内存 Lambda 同步桥接：
                // 当在书籍详情页点击搜索按钮时，直接在同一个 Activity 内部零延迟地唤醒非独立 SearchOverlay，并同步传入初始 query 以快速搜索相关作者/播音。
                DetailOverlay(
                    detailViewModel = detailViewModel,
                    canStartNavigation = canStartNavigation,
                    glassEffectMode = libraryUiState.glassEffectMode,
                    // 为每一次改动添加详尽的中文注释：将全局 appHazeState 传入详情页，实现与背景相同的毛玻璃折射效果。
                    hazeState = appHazeState,
                    // 为每一次改动添加详尽的中文注释：将专属的 detailHazeState 传入详情页，让其注册为该采样源的 source，用以采集全量详情页画面
                    detailHazeState = detailHazeState,
                    onPlayBook = { bookId ->
                        playerViewModel.loadBook(bookId)
                        playerViewModel.setFullPlayerVisible(true)
                    },
                    onNavigateToSearch = { query ->
                        searchViewModel.setVisible(true)
                        searchViewModel.onQueryChange(androidx.compose.ui.text.input.TextFieldValue(query))
                    },
                    // 为每一次改动添加详尽的中文注释：接收来自详情页的修改书籍信息点击事件，并在内存中零延迟地直接拉起 EditBookOverlay 悬浮层
                    onEditClick = { bookId ->
                        editViewModel.startEdit(bookId)
                    }
                )

                // 为每一次改动添加详尽的中文注释：
                // 播放器悬浮层 (PlayerOverlay)。位于详情页之上，包含全屏播放器 and 迷你播放器组件。
                // 向其透传全局 appHazeState，使得底部 CompactMediaPlayer能实时且极致 premium 地高斯模糊折射 HomeScreen 书籍卡片色彩。
                PlayerOverlay(
                    playerViewModel = playerViewModel,
                    playerActions = playerActions,
                    miniPlayerActions = miniPlayerActions,
                    playerNavigationActions = playerNavigationActions,
                    currentRoute = currentRoute,
                    glassEffectMode = libraryUiState.glassEffectMode,
                    hazeState = appHazeState
                )

                // 为每一次改动添加详尽的中文注释：
                // 书籍信息修改悬浮层 (EditBookOverlay)。
                // 【核心层级变动】：为了确保编辑悬浮层遮盖在 compact 播放器（迷你播放器，属于 PlayerOverlay 内部组件）的上方，
                // 我们在 Box 容器中将 EditBookOverlay 的物理挂载位置调整到 PlayerOverlay 之后。
                // 从而使 EditBookOverlay 的 Z-index 处于更高层，完美防止迷你播放器错误遮盖编辑层。
                // 此外，它依然以同级兄弟节点形式挂载在 appHazeState 外部以彻底规避 Haze 渲染无限死锁；
                // 并且将全局 detailHazeState 作为其 Haze 磨砂背景模糊源，以极其精致透亮地模糊折射底层的详情页面。
                EditBookOverlay(
                    editViewModel = editViewModel,
                    glassEffectMode = libraryUiState.glassEffectMode,
                    hazeState = detailHazeState,
                    onSaveSuccess = {
                        // 保存成功后响应式流程会通过 Room Flow 自动刷新并重绘详情页，此处无需执行额外 UI 强制刷新的脏操作
                    }
                )

                // 为每一次改动添加详尽的中文注释：
                // 非独立搜索悬浮层 (SearchOverlay)。在 Z 轴绘制顺序上摆在 PlayerOverlay 之后，
                // 以获得高于迷你播放器 (CompactMediaPlayer) 和有声书详情页的最高渲染层级。
                // 彻底废弃原本的跨 Window 桥接，利用全内存 lambda 表达式实现无缝的零延迟通信：
                // 1. 点击书籍跳转详情：隐去搜索悬浮层并立即同步拉起详情 Overlay，状态转换无缝顺畅。
                // 2. 点击直接播放：隐去搜索悬浮层，加载书籍并开启全屏播放器。
                // 3. 共享全局的 appHazeState 采样源，呈现极致 premium 的磨砂毛玻璃透光质感。
                com.viel.aplayer.ui.search.SearchOverlay(
                    searchViewModel = searchViewModel,
                    hazeState = appHazeState,
                    glassEffectMode = libraryUiState.glassEffectMode,
                    onNavigateToDetail = { bookId ->
                        searchViewModel.setVisible(false)
                        val book = libraryUiState.audiobooks.find { it.book.id == bookId }
                        detailViewModel.selectBook(book)
                    },
                    onLoadBook = { bookId ->
                        searchViewModel.setVisible(false)
                        playerViewModel.loadBook(bookId)
                    },
                    onNavigateToPlayer = {
                        playerViewModel.setFullPlayerVisible(true)
                    }
                )

                // 为每一次改动添加详尽的中文注释：扫码结果 Dialog 面板
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
