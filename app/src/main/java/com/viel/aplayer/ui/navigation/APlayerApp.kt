package com.viel.aplayer.ui.navigation

//
// 引入 miuix-blur 的 Backdrop 机制 API 彻底替换旧的模糊库依赖，以实现更加清透的视口级高斯模糊折射效果
import android.text.Layout
import android.text.SpannableString
import android.text.Spanned
import android.text.style.AlignmentSpan
import android.widget.Toast
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.viel.aplayer.data.store.GlassEffectMode
import com.viel.aplayer.ui.common.ScanResultDialog
import com.viel.aplayer.ui.common.UiEvent
import com.viel.aplayer.ui.common.theme.APlayerTheme
import com.viel.aplayer.ui.detail.DetailOverlay
import com.viel.aplayer.ui.detail.DetailViewModel
import com.viel.aplayer.ui.edit.EditBookOverlay
import com.viel.aplayer.ui.edit.EditBookViewModel
import com.viel.aplayer.ui.home.LibraryViewModel
import com.viel.aplayer.ui.miniplayer.MiniPlayerActions
import com.viel.aplayer.ui.miniplayer.MiniPlayerOverlay
import com.viel.aplayer.ui.player.PlayerViewModel
import com.viel.aplayer.ui.player.components.PlayerOverlay
import com.viel.aplayer.ui.player.rememberActions
import com.viel.aplayer.ui.search.SearchOverlay
import com.viel.aplayer.ui.search.SearchViewModel
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop

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
        // 书籍详情页独立的 ViewModel，从 LibraryViewModel 中拆分出来，使各 ViewModel 职责单一
        val detailViewModel: DetailViewModel = viewModel()
        // 实例化书籍元数据编辑独立的 ViewModel，交由当前 Activity 统一承载与销毁。
        val editViewModel: EditBookViewModel = viewModel()
        
        // 
        // 实例化非独立的 SearchViewModel，由当前 Activity 统一承载与销毁。
        val searchViewModel: SearchViewModel = viewModel()

        val playerUiState by playerViewModel.uiState.collectAsStateWithLifecycle()
        val libraryUiState by libraryViewModel.uiState.collectAsStateWithLifecycle()
        val scanResult by libraryViewModel.scanResultDialogState.collectAsStateWithLifecycle()
        // 在此收集详情页 detailViewModel 的 uiState 状态。
        // 用以在后续渲染迷你播放器 PlayerOverlay 时感知详情页是否处于可见状态，以进行 miuix-blur 模糊采样源的动态自动切换映射。
        val detailUiState by detailViewModel.uiState.collectAsStateWithLifecycle()

        // 详尽的中文注释：响应式收集非独立搜索悬浮层 SearchOverlay 的可见状态流，用于动态判定与控制迷你播放器的显示与挂载，避免层级遮挡下的无效渲染与资源浪费
        val isSearchVisible by searchViewModel.isVisible.collectAsStateWithLifecycle()

        val canStartNavigation = rememberNavigationThrottle()

        // 
        // 实例化全局共享的 LayerBackdrop 采样源。
        // 当用户全局开启磨砂玻璃效果时，整个 APlayerNavHost 容器会被作为采样源挂载，
        // 从而使得位于其上方悬浮的迷你播放器 (CompactMediaPlayer) 以及非独立的搜索悬浮层 (SearchOverlay)
        // 能够以 100% 实时的超高性能直接模糊下方的 HomeScreen 主页面卡片，杜绝一切由于跨 Activity 物理模糊导致的系统桌面穿帮隐患。
        val appBackdrop = rememberLayerBackdrop()

        // 
        // 实例化专门用来采集整个详情页（DetailOverlay）视觉渲染画面的 detailBackdrop 采样源。
        // 当编辑悬浮层 (EditBookOverlay) 弹出覆盖在详情页之上时，能通过 detailBackdrop
        // 渲染出把底下详情页上的文字、封面卡片和按钮等融为一体的极其精致的高斯磨砂毛玻璃视觉背景。
        val detailBackdrop = rememberLayerBackdrop()

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


        // M-19 修复 — 高频单向数据同步管道
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

        // 消费 LibraryViewModel 发射的一次性 UI 事件（如 Toast 消息），
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
                    else -> {}
                }
            }
        }

        // 
        // 消费由当前播放器 PlayerViewModel 共享并转发过来的一次性 UI 反馈事件。
        // 直接使用标准的 Toast 进行提示，避免过度包装以保持原生的简明风格。
        LaunchedEffect(Unit) {
            playerViewModel.uiEvents.collect { event ->
                when (event) {
                    is UiEvent.ShowToast -> {
                        Toast.makeText(context, event.message, Toast.LENGTH_SHORT).show()
                    }
                    is UiEvent.ShowTrackUnavailableDialog -> {
                        // 详尽的中文注释：收到分轨失效事件后，立即在 ViewModel 中触发状态更新，以便拉起 Compose 确认跳转弹窗
                        playerViewModel.showTrackUnavailableDialog(event.bookId, event.queueIndex)
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
                // 显式协调详情页状态清理，与 playerViewModel.closePlayback 保持一致的外层协调模式
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

        // 
        // 恢复最顶级干净的 Surface 布局，用以完全避免悬浮高度和背景采样层在键盘弹起时被迫消费 bottom padding 的截断与穿帮。
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            // 
            // 在最外层使用一个没有挂载 layerBackdrop 的全屏顶级 Box 容器，仅作为所有悬浮层的坐标对齐和兄弟节点布局容器。
            // 这能够彻底隔离 layerBackdrop 采样源的层级，避免因为悬浮层在内部使用 textureBlur 采样自身父容器而导致无限递归死锁渲染失效。
            Box(
                modifier = Modifier.fillMaxSize()
            ) {
                // 
                // 
                // 使用独立的 Box 容器专门包裹底层 APlayerNavHost（即 HomeScreen 主页面卡片所在的主视图），并在此 Box 上挂载 Modifier.layerBackdrop(state = appBackdrop)。
                // 这样，当用户在全局设置中开启 miuix-blur 效果时，appBackdrop 采样源只采集主导航页的画面数据，
                // 而上方的 DetailOverlay、PlayerOverlay、SearchOverlay 则以同级兄弟节点形式存在于其外部。
                // 这样既能实现完全的 Z 轴遮盖与高斯模糊渲染，又彻底打破了“子采样父”导致的图形绘制死锁，使磨砂玻璃视效完美还原。
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .then(
                            // 对齐新命名的 MiuixBlur，如果是该模式则为底层视图容器注册 appBackdrop 采样源
                            if (libraryUiState.glassEffectMode == GlassEffectMode.MiuixBlur) {
                                Modifier.layerBackdrop(appBackdrop)
                            } else {
                                Modifier
                            }
                        )
                ) {
                    // 最底层系统导航管理容器，主导航页（HomeScreen 包含其自己的局部 Scaffold 以自备底部 Insets 避让）
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

                // 
                // 详情页悬浮层 (DetailOverlay)。现在作为同级兄弟节点挂载于 layerBackdrop 外部，避免模糊死锁问题。
                // 彻底废弃原本的拉起 SearchActivity 的 Intent 启动契约，改用内存 Lambda 同步桥接：
                // 当在书籍详情页点击搜索按钮时，直接在同一个 Activity 内部零延迟地唤醒非独立 SearchOverlay，并同步传入初始 query 以快速搜索相关作者/播音。
                DetailOverlay(
                    detailViewModel = detailViewModel,
                    canStartNavigation = canStartNavigation,
                    glassEffectMode = libraryUiState.glassEffectMode,
                    // 将全局 appBackdrop 传入详情页，实现与背景相同的毛玻璃折射效果。
                    backdrop = appBackdrop,
                    // 将专属的 detailBackdrop 传入详情页，让其注册为该采样源的 source，用以采集全量详情页画面
                    detailBackdrop = detailBackdrop,
                    onPlayBook = { bookId ->
                        playerViewModel.loadBook(bookId)
                        playerViewModel.setFullPlayerVisible(true)
                    },
                    onNavigateToSearch = { query ->
                        searchViewModel.setVisible(true)
                        searchViewModel.onQueryChange(TextFieldValue(query))
                    },
                    // 接收来自详情页的修改书籍信息点击事件，并在内存中零延迟地直接拉起 EditBookOverlay 悬浮层
                    onEditClick = { bookId ->
                        editViewModel.startEdit(bookId)
                    }
                )

                // 
                // 【物理 Z-index 层级调整说明】：
                // 根据主 Activity 窗口内部的物理层级规范，由底至顶精确重排所有悬浮组件的绘制顺序。
                // 此时在 Jetpack Compose 的 Box 容器中，后声明的组件会拥有更高的物理渲染和交互优先级（即 Z-index 更高）：
                // 1. 底层 APlayerNavHost (已在上方声明)
                // 2. 详情页 DetailOverlay (已在上方声明)
                // 3. 迷你播放器 MiniPlayerOverlay 浮于详情页之上
                // 4. 书籍信息编辑悬浮层 EditBookOverlay (全屏编辑界面) 彻底覆盖详情页和迷你播放器
                // 5. 全屏播放器 PlayerOverlay 展开时彻底遮盖迷你播放器、编辑层与详情页
                // 6. 搜索悬浮层 SearchOverlay 最顶层，覆盖全屏播放器及一切内容

                // 
                // 3. 迷你播放器悬浮层 (MiniPlayerOverlay)。
                // 核心模糊采样源自适应修复与延迟策略：
                // - 当详情页 DetailOverlay 显示（detailUiState.isVisible 为 true）时，迷你播放器底部的物理图层其实是详情页，
                //   此时我们将其 backdrop 动态切换为 detailBackdrop，使得模糊的磨砂玻璃能以极其精致真实的物理透射展现详情页的背景颜色。
                // - 当详情页隐藏时，则安全地切回 appBackdrop 采样底层的 HomeScreen 界面。
                // 针对详情页的可见性变化，采用自适应延迟切换采样源策略以平滑转场动画：
                val targetBackdrop = if (detailUiState.isVisible) detailBackdrop else appBackdrop
                val delayedBackdropState = remember(appBackdrop, detailBackdrop) {
                    mutableStateOf(targetBackdrop)
                }
                LaunchedEffect(targetBackdrop) {
                    val delayMs = 401L
                    delay(delayMs)
                    delayedBackdropState.value = targetBackdrop
                }

                MiniPlayerOverlay(
                    playerViewModel = playerViewModel,
                    miniPlayerActions = miniPlayerActions,
                    isSearchActive = isSearchVisible,
                    glassEffectMode = libraryUiState.glassEffectMode,
                    // 使用延迟后的采样源，避免转场动画残影与闪烁，提升极致平滑感
                    backdrop = delayedBackdropState.value
                )

                // 
                // 4. 书籍信息修改悬浮层 (EditBookOverlay)。
                // 【核心层级变动】：为了确保全屏编辑悬浮层能遮盖在迷你播放器的上方（覆盖详情页和迷你播放器），
                // 在 Box 容器中将其声明在 MiniPlayerOverlay 之后。同时使用专属 detailBackdrop 提供精致的磨砂毛玻璃透光质感。
                EditBookOverlay(
                    editViewModel = editViewModel,
                    glassEffectMode = libraryUiState.glassEffectMode,
                    backdrop = detailBackdrop,
                    onSaveSuccess = {
                        // 保存成功后响应式流程会通过 Room Flow 自动刷新并重绘详情页，此处无需执行额外 UI 强制刷新的脏操作
                    }
                )

                // 
                // 5. 全屏播放器悬浮层 (PlayerOverlay)。
                // 【核心层级变动】：为了确保全屏播放器在展开时彻底遮盖下方的迷你播放器、全屏编辑界面与详情页，
                // 在 Box 容器中将 PlayerOverlay 的物理声明位置后移至 EditBookOverlay 之后。
                PlayerOverlay(
                    playerViewModel = playerViewModel,
                    playerActions = playerActions,
                    playerNavigationActions = playerNavigationActions,
                    glassEffectMode = libraryUiState.glassEffectMode
                )

                // 
                // 6. 非独立搜索悬浮层 (SearchOverlay)。
                // 【核心层级变动】：为了确保搜索功能作为全局最顶层容器运行，可覆盖全屏播放器及一切内容，
                // 我们在 Z 轴物理声明上将其摆在 PlayerOverlay 之后，拥有除扫码结果之外的最高物理渲染层级。
                SearchOverlay(
                    searchViewModel = searchViewModel,
                    backdrop = appBackdrop,
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

                // 扫码结果 Dialog 面板
                scanResult?.let { session ->
                    ScanResultDialog(
                        session = session,
                        onDismiss = { libraryViewModel.dismissScanResultDialog() }
                    )
                }

                // 物理分轨不可用时的二次确认跳轨弹窗，限定在全屏播放器展开时（isFullPlayerVisible）才弹窗展示
                val trackUnavailableState by playerViewModel.trackUnavailableDialogState.collectAsStateWithLifecycle()
                if (trackUnavailableState.show && playerUiState.isFullPlayerVisible) {
                    AlertDialog(
                        onDismissRequest = { playerViewModel.dismissTrackUnavailableDialog() },
                        title = { Text("分轨文件不可用") },
                        text = { Text("当前收听的分轨物理文件不存在或损坏。是否跳过该分轨并播放下一首可用分轨？\n\n（注意：强制跳轨可能会打乱您原本预定的收听进度）") },
                        confirmButton = {
                            TextButton(
                                onClick = {
                                    // 详尽的中文注释：用户在弹窗二次确认后决定执行跳轨，在此调用 ViewModel 的接口触发自愈
                                    playerViewModel.skipToNextAvailableTrack(
                                        trackUnavailableState.bookId,
                                        trackUnavailableState.queueIndex
                                    )
                                    playerViewModel.dismissTrackUnavailableDialog()
                                }
                            ) {
                                Text("确认跳过", color = MaterialTheme.colorScheme.error)
                            }
                        },
                        dismissButton = {
                            TextButton(
                                onClick = { playerViewModel.dismissTrackUnavailableDialog() }
                            ) {
                                Text("取消")
                            }
                        }
                    )
                }
            }
        }
    }
}
