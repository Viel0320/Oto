package com.viel.aplayer.ui.home

import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.viel.aplayer.ui.detail.DetailViewModel
import com.viel.aplayer.ui.player.PlayerViewModel
import com.viel.aplayer.ui.settings.SettingsActivity

/**
 * 首页图书馆的过滤选项枚举。
 */
enum class HomeFilter {
    /** 正在阅读（播放进度 > 0 且未读完） */
    InProgress,
    /** 未开始 */
    NotStarted,
    /** 已读完 */
    Finished
}

/**
 * 首页容器组件（Stateful），负责在主导航宿主中观察和同步 LibraryViewModel 和 PlayerViewModel 里的状态。
 *
 * 经过物理分离架构建设，HomeScreen 升级为了高层次的业务状态绑定容器，
 * 专门从 ViewModel 的 StateFlow 中收集 UI 数据状态，并在内部托管最近列表横向滚动状态 `recentListState`，
 * 从而完全屏蔽了网格因上下滚动被销毁导致的状态丢失隐患，净化了系统的导航与路由架构。
 */
@Composable
fun HomeScreen(
    modifier: Modifier = Modifier,
    libraryViewModel: LibraryViewModel,
    playerViewModel: PlayerViewModel,
    detailViewModel: DetailViewModel,
    searchViewModel: com.viel.aplayer.ui.search.SearchViewModel,
    canStartNavigation: () -> Boolean,
    navigateBack: () -> Unit
) {
    val playerUiState by playerViewModel.uiState.collectAsStateWithLifecycle()
    val libraryUiState by libraryViewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // 详尽中文注释：为主页“最近”横向滚动列表维护独立的滚动状态，放在最外层，
    // 防止在网格（LazyVerticalGrid）上下滚动时，该横向列表的状态由于离开屏幕而被销毁重置。
    val recentListState = rememberLazyListState()

    // 详尽中文注释：利用 state 变量记录上一次渲染时的首轨书籍 ID 以及是否需要重置滚动的标记。
    // 在 composition 阶段，列表的实际 layout 尚未运行，因此此时的 `firstVisibleItemIndex` 和 `firstVisibleItemScrollOffset`
    // 依旧保留着上一帧的真实滚动位置。这能让我们在多本书籍批量插入导致 layout 发生物理移位前，精准捕获到“变更前是否处于起点”的状态，
    // 彻底规避多项目批量导入时由于 Compose 默认锚定导致的状态判断失效与竞态问题。
    var prevFirstBookId by remember { mutableStateOf<String?>(null) }
    var shouldScrollToStart by remember { mutableStateOf(false) }

    val recentBooks = libraryUiState.recentBooks
    val firstBookId = recentBooks.firstOrNull()?.book?.id
    if (firstBookId != prevFirstBookId) {
        // 数据源首项发生变更（有新书导入或切换了过滤器）
        val wasAtStart = recentListState.firstVisibleItemIndex == 0 && recentListState.firstVisibleItemScrollOffset == 0
        if (wasAtStart) {
            shouldScrollToStart = true
        }
        prevFirstBookId = firstBookId
    }

    LaunchedEffect(shouldScrollToStart) {
        if (shouldScrollToStart) {
            // 详尽中文注释：在 layout 运行且视口由于锚定发生偏移后，由 LaunchedEffect 异步安全地重置视口到最左侧第 0 项。
            // 这样无论一次性导入多少本书，只要用户之前在起点，视口就会始终锁定在最左侧的最新书籍上，将旧书籍顺延推到右侧。
            recentListState.scrollToItem(0)
            shouldScrollToStart = false
        }
    }

    HomeScreenContent(
        modifier = modifier,
        selectedFilter = libraryUiState.selectedFilter,
        groupedByAuthor = libraryUiState.groupedByAuthor,
        recentBooks = recentBooks,
        shouldShowRecentBooks = libraryUiState.shouldShowRecentBooks,
        recentTitleRes = libraryUiState.recentTitleRes,
        glassEffectMode = libraryUiState.glassEffectMode,
        isMiniPlayerVisible = playerUiState.hasActiveTrack,
        recentListState = recentListState,
        onFilterSelected = { libraryViewModel.setFilter(it) },
        onNavigateToDetail = { id: String ->
            val book = libraryUiState.audiobooks.find { it.book.id == id }
            detailViewModel.selectBook(book)
        },
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
        onNavigateToSettings = {
            if (canStartNavigation()) {
                val intent = SettingsActivity.createIntent(context)
                context.startActivity(intent)
            }
        },
        onUpdateReadStatus = { bookId, status ->
            libraryViewModel.updateBookReadStatus(bookId, status)
        },
        onForceRegenerate = { bookId ->
            libraryViewModel.forceRegenerateCoverAndMetadata(bookId)
        },
        onDeleteBook = { bookId ->
            playerViewModel.closePlayback(bookId)
            libraryViewModel.deleteBook(bookId)
        }
    )
}
