package com.viel.aplayer.ui.home

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.exclude
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.viel.aplayer.R
import com.viel.aplayer.data.entity.BookEntity
import com.viel.aplayer.data.entity.BookWithProgress
import com.viel.aplayer.data.store.AppSettings
import com.viel.aplayer.data.store.GlassEffectMode
import com.viel.aplayer.ui.common.APlayerFilterChip
import com.viel.aplayer.ui.common.CoverImageSourceSelector
import com.viel.aplayer.ui.common.theme.APlayerTheme
import com.viel.aplayer.ui.common.theme.LocalWindowClass
import com.viel.aplayer.ui.common.theme.WindowClass
import com.viel.aplayer.ui.home.components.AudiobookActionDialogs
import com.viel.aplayer.ui.home.components.ListItem
import com.viel.aplayer.ui.home.components.RecentlyAddedSection
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop

/**
 * 首页内容展示组件（Stateless），纯 UI 渲染层。
 * 
 * 经过解耦重构，HomeScreenContent 实现了完全的无状态，不再持有任何 ViewModel 或 context 上下文。
 * 所有的交互行为与数据下发全部通过纯粹的声明式参数与 Lambda 回调函数进行，极大提高了组件的单元测试性与预览灵活性。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreenContent(
    modifier: Modifier = Modifier,
    // 以下为从 LibraryUiState 拆解传入的预计算字段，Composable 无需再做 remember 运算。
    // selectedFilter 为 null 时表示 ViewModel 的 combine 管道尚未产出首个最终决策，此时不渲染 FilterChip 行以避免跳变动画。
    selectedFilter: HomeFilter? = null,
    groupedByAuthor: Map<String, List<BookWithProgress>> = emptyMap(),
    recentBooks: List<BookWithProgress> = emptyList(),
    shouldShowRecentBooks: Boolean = false,
    @StringRes recentTitleRes: Int = 0,
    glassEffectMode: GlassEffectMode,
    isMiniPlayerVisible: Boolean = false,
    recentListState: LazyListState,
    onFilterSelected: (HomeFilter) -> Unit = {},
    onNavigateToSearch: () -> Unit = {},
    onNavigateToSettings: () -> Unit = {},
    onNavigateToDetail: (String) -> Unit = {},
    onNavigateToPlayer: () -> Unit = {},
    onLoadBook: (String) -> Unit = {},
    onLibraryRootSelected: (Uri) -> Unit = {},
    // 新增修改阅读状态事件回调，供长按Dialog菜单中的标记操作响应
    onUpdateReadStatus: (String, String) -> Unit = { _, _ -> },
    // 新增强制重建封面与元数据回调，供长按菜单中的重建触发
    onForceRegenerate: (String) -> Unit = {},
    // 新增删除书籍的回调，供长按菜单中的二次确认软删除触发
    onDeleteBook: (String) -> Unit = {},
) {
    // 使用 remember 级联监听当前被长按的有声书状态，决定一级Dialog的渲染
    var activeBookForMenu by remember { mutableStateOf<BookWithProgress?>(null) }

    // 为长按操作 Dialog 创建 LayerBackdrop 状态机；Scaffold 作为采样源，Dialog 面板作为模糊渲染面。
    val actionDialogBackdrop = rememberLayerBackdrop()
    // Filter Chip 的标签映射，这是纯 UI 文本，保留在 Composable 中
    val filters = listOf(
        HomeFilter.NotStarted to stringResource(R.string.filter_not_started),
        HomeFilter.InProgress to stringResource(R.string.filter_in_progress),
        HomeFilter.Finished to stringResource(R.string.filter_finished)
    )
    // 从 ViewModel 预计算的 recentTitleRes 解析为本地化字符串（0 表示无需展示）
    val recentTitle = if (recentTitleRes != 0) stringResource(recentTitleRes) else ""
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        uri?.let(onLibraryRootSelected)
    }

    // 详尽的中文注释：
    // 使用统一封装的 WindowClass 接口获取当前窗口大小分级、列数和水平边距，
    // 避免了在此直接通过 LocalConfiguration 读取配置带来的硬编码布局判断，提高了多设备适配的高内聚与扩展性。
    val windowClass = LocalWindowClass.current
    val columnsCount = windowClass.columnsCount
    val screenHorizontalPadding = windowClass.screenHorizontalPadding

    // 计算 TopAppBar 图标的补偿边距。
    // M3 顶栏图标默认起始位是 16dp（4dp 容器间距 + 12dp 按钮居中偏移）。
    // 当业务边距升至 24dp 时，需额外补回 8dp 以前后对齐。
    val appBarIconPadding = (screenHorizontalPadding - 16.dp).coerceAtLeast(0.dp)

    // 利用 WindowInsets.safeDrawing 动态获取当前设备的状态栏、导航栏与物理刘海，并显式使用 exclude(WindowInsets.ime)
    // 彻底切断软键盘 (IME) 对主页所感知的安全区 padding 的物理影响，从而规避由于 Insets 物理高度变化导致的主页（HomeScreen）不必要重组。
    val safeDrawingPadding = WindowInsets.safeDrawing.exclude(WindowInsets.ime).asPaddingValues()
    val layoutDirection = androidx.compose.ui.platform.LocalLayoutDirection.current
    // 
    // 重构网格内边距策略：此处 gridStart/EndPadding 仅保留物理安全区域（如刘海、侧边导航栏）。
    // 将 16dp/24dp 的业务逻辑边距从 Grid 容器层剥离，下沉到具体的标题和列表项中自行实现。
    // 这样做能够确保 ListItem 的点击水波纹（Ripple） and 滑动的滚动条能够紧贴屏幕物理边缘，实现极致的 Edge-to-Edge 视觉高级感。
    val gridStartPadding = safeDrawingPadding.calculateStartPadding(layoutDirection)
    val gridEndPadding = safeDrawingPadding.calculateEndPadding(layoutDirection)

    // 为每一次改动添加详将滚动状态 remember 由 LazyListState 迁移至自适应网格 GridState，完成底座升级。
    val gridState = rememberLazyGridState()
    val scope = rememberCoroutineScope()
    // 只有 miuix-blur 模式才将主页完整内容注册为采样源；Material 模式跳过以节省渲染成本。将遗留的模糊源修饰符重命名为 blurSourceModifier。
    val blurSourceModifier = if (glassEffectMode == GlassEffectMode.MiuixBlur) {
        Modifier.layerBackdrop(actionDialogBackdrop)
    } else {
        Modifier
    }

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            // 主页完整内容作为 AudiobookActionDialogs 的 miuix-blur 背景采样源。
            .then(blurSourceModifier),
        // 显式将默认的 contentWindowInsets 排除掉 ime。
        // 这能彻底阻断软键盘弹出时通过 Scaffold 自动向下传导 innerPadding 变化引起的布局重测和不必要的主页重组。
        contentWindowInsets = WindowInsets.safeDrawing.exclude(WindowInsets.ime),
        topBar = {
            CenterAlignedTopAppBar(
                // 恢复默认修饰符，不在容器外侧加 Padding，使顶部栏背景底色或磨砂折射面能够极致平铺至屏幕物理左右边缘
                modifier = Modifier,
                // 在 safeDrawing.exclude(navigationBars) 的基础上再次显式排除 WindowInsets.ime，
                // 确保顶部栏在软键盘弹起时不受任何 Insets 物理高度波及，保持完美的静止状态，拒绝任何不必要的重组。
                windowInsets = WindowInsets.safeDrawing.exclude(WindowInsets.navigationBars).exclude(WindowInsets.ime),
                title = {
                    Text(
                        text = stringResource(R.string.app_name),
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = 22.sp
                        ),
                        modifier = Modifier.pointerInput(Unit) {
                            detectTapGestures(
                                onDoubleTap = {
                                    scope.launch {
                                        // 双击 TopAppBar 时滚动至顶映射至自适应 gridState 以实现完美兼容。
                                        gridState.scrollToItem(0)
                                    }
                                }
                            )
                        }
                    )
                },
                navigationIcon = {
                    IconButton(
                        onClick = onNavigateToSearch,
                        // 应用 appBarIconPadding 补偿，使搜索图标在横屏/大屏模式下与下方内容精准对齐
                        modifier = Modifier.padding(start = appBarIconPadding)
                    ) {
                        Icon(
                            Icons.Rounded.Search,
                            contentDescription = stringResource(R.string.search_content_description)
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = onNavigateToSettings,
                        // 应用 appBarIconPadding 补偿，使设置图标在右侧也能保持对称的视觉边界对齐
                        modifier = Modifier.padding(end = appBarIconPadding)
                    ) {
                        Icon(
                            Icons.Rounded.Tune,
                            contentDescription = stringResource(R.string.settings_content_description)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    scrolledContainerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { 
                    launcher.launch(null)
                },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                shape = CircleShape,
                modifier = Modifier
                    .padding(bottom = if (isMiniPlayerVisible) 80.dp else 16.dp)
                    .navigationBarsPadding()
                    .size(64.dp)
            ) {
                Icon(
                    Icons.Rounded.Add,
                    contentDescription = stringResource(R.string.import_content_description),
                    modifier = Modifier.size(32.dp)
                )
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = innerPadding.calculateTopPadding())
        ) {
            // 
            // 将原有的单列 LazyColumn 升级重构为全新且强大的 LazyVerticalGrid。
            // 依靠 columns 参数接收 Fixed(columnsCount)，能在超宽屏幕、平板以及普通横屏模式下流畅自适应划分多列，
            // 所有非主列表书籍项（如 FilterChipRow, RecentlyAdded Row，Author Headers 等）全部通过 span 设置 GridItemSpan(maxLineSpan) 强制其跨满全宽，
            // 从而构建出无懈可击、极具 premium 高级观感的流体自适应卡片式网格。
            LazyVerticalGrid(
                columns = GridCells.Fixed(columnsCount),
                state = gridState,
                modifier = Modifier.fillMaxSize(),
                // 将动态算出的左右物理安全区 Padding 注入到网格容器作为统一滚动边界，消除硬编码遮挡隐患
                contentPadding = PaddingValues(
                    start = gridStartPadding,
                    end = gridEndPadding,
                    bottom = innerPadding.calculateBottomPadding() + (if (isMiniPlayerVisible) 80.dp else 0.dp) + 16.dp
                )
            ) {
                // 将 FilterChip 过滤器栏从固定顶部移动至可滚动网格的第一项。
                // 当 selectedFilter 为 null 时（combine 管道尚未就绪），不渲染该项以避免跳变。
                // 通过 span 设置 GridItemSpan(maxLineSpan) 确保过滤器行在多列布局下依然横向占满全宽。
                if (selectedFilter != null) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        // 使用 APlayerFilterChip 构建横向滚动的过滤器组。
                        LazyRow(
                            modifier = Modifier
                                .fillMaxWidth(),
                            // 
                            // 过滤器行需手动补回 screenHorizontalPadding 以对齐视觉安全线，同时允许滚动时穿透业务边距。
                            contentPadding = PaddingValues(
                                start = screenHorizontalPadding,
                                end = screenHorizontalPadding
                            ),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(filters, key = { (filter, _) -> filter.name }) { (filter, label) ->
                                APlayerFilterChip(
                                    selected = filter == selectedFilter,
                                    onClick = { onFilterSelected(filter) },
                                    label = label
                                )
                            }
                        }
                    }
                }

                if (shouldShowRecentBooks) {
                    // 详尽的中文注释：使用解耦出来的独立组件渲染“最近播放/最近添加”区块，并使用 GridItemSpan(maxLineSpan) 确保其跨满网格全宽
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        RecentlyAddedSection(
                            recentTitle = recentTitle,
                            recentBooks = recentBooks,
                            recentListState = recentListState,
                            glassEffectMode = glassEffectMode,
                            screenHorizontalPadding = screenHorizontalPadding,
                            onNavigateToDetail = onNavigateToDetail,
                            onBookLongClick = { activeBookForMenu = it }
                        )
                    }
                }

                groupedByAuthor.forEach { (author, books) ->
                    // 作者分组 Header 栏通过 span 设置最大跨度占满全宽。
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Text(
                            text = author.takeIf { it.isNotBlank() } ?: "Unknown",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            // 
                            // 同样显式注入 screenHorizontalPadding，确保作者分组名与页面主标题在同一条垂线上对齐。
                            modifier = Modifier.padding(
                                start = screenHorizontalPadding,
                                end = screenHorizontalPadding,
                                top = 24.dp,
                                bottom = 8.dp
                            )
                        )
                    }

                    // M-20 修复 — 使用 book.id 作为稳定 key，避免书单排序后 item 状态错位
                    items(books, key = { it.book.id }) { book ->
                        // 
                        // 当列数 columnsCount 大于 1 时（横屏或平板网格展示模式），为了防止相邻网格的书籍项目物理上过于黏连，
                        // 我们只为其注入适度的内外边距，不需要加任何卡片背景与圆角，保持最本真的极简透光观感。
                        val itemModifier = if (columnsCount > 1) {
                            Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        } else {
                            Modifier
                        }

                        ListItem(
                            title = book.book.title,
                            author = book.book.author,
                            narrator = book.book.narrator,
                            duration = book.book.totalDurationMs,
                            // 详尽注释：主页普通列表属于小图场景，统一交给 CoverImageSourceSelector.small 决定
                            // “缩略图优先、原图兜底”的路径顺序，避免页面层继续散落手写 Elvis 规则。
                            coverPath = CoverImageSourceSelector.small(
                                thumbnailPath = book.book.thumbnailPath,
                                coverPath = book.book.coverPath
                            ),
                            coverLastUpdated = book.book.lastScannedAt, // 桥接 Room 层中的扫描/自愈重建毫秒时间戳，使用声明式设计促成图片同步强绘刷新
                            progressPercent = book.progressPercent,
                            onClick = { onNavigateToDetail(book.book.id) },
                            // 长按有声书列表项时将当前书籍状态记录到 activeBookForMenu 中，用以唤起操作 Dialog 菜单
                            onLongClick = { activeBookForMenu = book },
                            modifier = itemModifier
                        ) {
                            onLoadBook(book.book.id)
                            onNavigateToPlayer()
                        }
                    }
                }
            }
        }
    }

    // 引入独立封装的长按操作系列 Dialog，保持主页 UI 布局清晰明了
    AudiobookActionDialogs(
        bookWithProgress = activeBookForMenu,
        backdrop = actionDialogBackdrop,
        glassEffectMode = glassEffectMode,
        onDismissRequest = { activeBookForMenu = null },
        onUpdateReadStatus = onUpdateReadStatus,
        onForceRegenerate = onForceRegenerate,
        onDeleteBook = onDeleteBook
    )
}

@Preview(showBackground = true, apiLevel = 36)
@Composable
fun HomeScreenNotStartedPreview() {
    val mockBooks = listOf(
        BookWithProgress(
            book = BookEntity(
                id = "id1",
                // Preview data follows the new logical-book model.
                rootId = "preview-root",
                sourceType = "SINGLE_AUDIO",
                title = "In the Megachurch",
                author = "Ryo Asai",
                narrator = "Narrator A",
                totalDurationMs = 44580000L,
                addedAt = System.currentTimeMillis()
            ),
            progress = null
        )
    )

    APlayerTheme {
        val mockListState = rememberLazyListState()
        // 详尽的中文注释：使用 CompositionLocalProvider 显式为 Previews 注入 PortraitPhone（竖屏手机）窗口预设，保证预览能够以百分之百高保真度准确呈现列表单列布局。
        CompositionLocalProvider(
            LocalWindowClass provides WindowClass.PortraitPhone
        ) {
            HomeScreenContent(
                // Preview 中模拟 ViewModel 预计算后的数据结构
                selectedFilter = HomeFilter.NotStarted,
                groupedByAuthor = mockBooks.groupBy { it.book.author },
                recentBooks = mockBooks,
                shouldShowRecentBooks = true,
                recentTitleRes = R.string.recently_added_title,
                isMiniPlayerVisible = false,
                recentListState = mockListState,
                // Preview 显式引用设置模型里的默认玻璃效果，避免 HomeScreenContent 参数重新拥有局部默认值。
                glassEffectMode = AppSettings.DEFAULT_GLASS_EFFECT_MODE
            )
        }
    }
}
