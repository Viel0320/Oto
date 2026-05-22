package com.viel.aplayer.ui.home

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Refresh
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.TextButton
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.viel.aplayer.R
import kotlinx.coroutines.launch
import com.viel.aplayer.data.entity.BookEntity
import com.viel.aplayer.data.entity.BookWithProgress
import com.viel.aplayer.data.store.AppSettings
import com.viel.aplayer.data.store.GlassEffectMode
import com.viel.aplayer.ui.common.APlayerFilterChip
import com.viel.aplayer.ui.common.formatPeopleSubtitle
import com.viel.aplayer.ui.theme.APlayerTheme
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState

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
 * 详尽中文注释：首页 Composable，纯 UI 渲染层。
 * 所有数据变换（过滤、分组、排序截取）已全部迁移至 LibraryViewModel 的 Flow 管道，
 * 此函数仅负责接收预计算好的数据并渲染界面，不做任何业务运算。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    modifier: Modifier = Modifier,
    // 详尽中文注释：以下为从 LibraryUiState 拆解传入的预计算字段，Composable 无需再做 remember 运算。
    // selectedFilter 为 null 时表示 ViewModel 的 combine 管道尚未产出首个最终决策，此时不渲染 FilterChip 行以避免跳变动画。
    selectedFilter: HomeFilter? = null,
    groupedByAuthor: Map<String, List<BookWithProgress>> = emptyMap(),
    recentBooks: List<BookWithProgress> = emptyList(),
    shouldShowRecentBooks: Boolean = false,
    @StringRes recentTitleRes: Int = 0,
    onFilterSelected: (HomeFilter) -> Unit = {},
    isMiniPlayerVisible: Boolean = false,
    // 为每一次改动添加详尽的中文注释：玻璃效果模式必须由 NavHost 从设置状态显式传入，主页本身不再声明 Material 默认值。
    glassEffectMode: GlassEffectMode,
    onNavigateToSearch: () -> Unit = {},
    onNavigateToSettings: () -> Unit = {},
    onNavigateToDetail: (String) -> Unit = {},
    onNavigateToPlayer: () -> Unit = {},
    onLoadBook: (String) -> Unit = {},
    onLibraryRootSelected: (Uri) -> Unit = {},
    // 为每一次改动添加详尽的中文注释：新增修改阅读状态事件回调，供长按Dialog菜单中的标记操作响应
    onUpdateReadStatus: (String, String) -> Unit = { _, _ -> },
    // 为每一次改动添加详尽的中文注释：新增强制重建封面与元数据回调，供长按菜单中的重建触发
    onForceRegenerate: (String) -> Unit = {},
    // 为每一次改动添加详尽的中文注释：新增删除书籍的回调，供长按菜单中的二次确认软删除触发
    onDeleteBook: (String) -> Unit = {},
) {
    // 为每一次改动添加详尽的中文注释：使用 remember 级联监听当前被长按的有声书状态，决定一级Dialog的渲染
    var activeBookForMenu by remember { mutableStateOf<BookWithProgress?>(null) }
    // 为每一次改动添加详尽的中文注释：为长按操作 Dialog 创建 HazeState；Scaffold 作为 source，Dialog 面板作为 effect。
    val actionDialogHazeState = rememberHazeState()
    // 详尽中文注释：Filter Chip 的标签映射，这是纯 UI 文本，保留在 Composable 中
    val filters = listOf(
        HomeFilter.NotStarted to stringResource(R.string.filter_not_started),
        HomeFilter.InProgress to stringResource(R.string.filter_in_progress),
        HomeFilter.Finished to stringResource(R.string.filter_finished)
    )
    // 详尽中文注释：从 ViewModel 预计算的 recentTitleRes 解析为本地化字符串（0 表示无需展示）
    val recentTitle = if (recentTitleRes != 0) stringResource(recentTitleRes) else ""
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        uri?.let(onLibraryRootSelected)
    }

    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    // 为每一次改动添加详尽的中文注释：只有 Haze 模式才将主页完整内容注册为采样源；Material 模式跳过以节省渲染成本。
    val hazeSourceModifier = if (glassEffectMode == GlassEffectMode.Haze) {
        Modifier.hazeSource(state = actionDialogHazeState)
    } else {
        Modifier
    }

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            // 为每一次改动添加详尽的中文注释：主页完整内容作为 AudiobookActionDialogs 的 Haze 背景采样源。
            .then(hazeSourceModifier),
        topBar = {
            CenterAlignedTopAppBar(
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
                                        listState.scrollToItem(0)
                                    }
                                }
                            )
                        }
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateToSearch) {
                        Icon(
                            Icons.Rounded.Search,
                            contentDescription = stringResource(R.string.search_content_description)
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onNavigateToSettings) {
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
            // 详尽中文注释：当 selectedFilter 为 null 时（combine 管道尚未就绪），
            // 不渲染 FilterChip 行，避免 stateIn 初始值导致的首帧假选中状态以及随后的跳变动画闪烁。
            if (selectedFilter != null) {
                // 详尽中文注释：使用全新重构的 Material 3 APlayerFilterChip 构建横向滚动的首页 Filter Chip Group。
                // 将 LazyRow 的底部外边距微调至 12.dp，既确保了点击 FilterChip 时其原生水波纹与按压阴影等视觉微反馈
                // 有足够的溢出展示空间不被强行截断，又为下方内容区域带来更加宽敞、透气的大师级呼吸感间距。
                LazyRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp, bottom = 12.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // 详尽中文注释：M-20 修复 — 使用 filter.name 作为稳定 key，避免过滤器切换时 FilterChip 动画错位
                    items(filters, key = { (filter, _) -> filter.name }) { (filter, label) ->
                        APlayerFilterChip(
                            selected = filter == selectedFilter,
                            onClick = { onFilterSelected(filter) },
                            label = label
                        )
                    }
                }
            }

            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    bottom = innerPadding.calculateBottomPadding() + (if (isMiniPlayerVisible) 80.dp else 0.dp) + 16.dp
                )
            ) {
                if (shouldShowRecentBooks) {
                    item {
                        Text(
                            text = recentTitle,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 16.dp)
                        )
                    }

                    item {
                        LazyRow(
                            modifier = Modifier.fillMaxWidth(),
                            contentPadding = PaddingValues(horizontal = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            // 详尽中文注释：M-20 修复 — 使用 book.id 作为稳定 key，避免最近添加列表更新时封面加载状态错位
                            items(recentBooks, key = { it.book.id }) { book ->
                                RecentlyItem(
                                    title = book.book.title,
                                    author = book.book.author,
                                    narrator = book.book.narrator,
                                    progressText = if (book.progressPercent > 0) "${book.progressPercent}%" else "NEW",
                                    coverPath = book.book.thumbnailPath ?: book.book.coverPath,
                                    coverLastUpdated = book.book.lastScannedAt, // 详尽中文注释：桥接 Room 中的扫描/更新时间戳，令 Coil 声明式打破缓存以即时更新界面
                                    onClick = { onNavigateToDetail(book.book.id) },
                                    // 为每一次改动添加详尽的中文注释：绑定长按RecentlyItem卡片事件以唤起操作一级菜单
                                    onLongClick = { activeBookForMenu = book },
                                    // 为每一次改动添加详尽的中文注释：向 RecentlyItem 传递当前全局的磨砂玻璃模式状态以进行极致毛玻璃视觉适配
                                    glassEffectMode = glassEffectMode,
                                    // 为每一次改动添加详尽的中文注释：将 Room 数据库中持久化缓存的书籍封面 ARGB 主色调传递给卡片组件
                                    coverColorArgb = book.book.backgroundColorArgb
                                )
                            }
                        }
                    }
                }

                groupedByAuthor.forEach { (author, books) ->
                    item {
                        Text(
                            text = author.takeIf { it.isNotBlank() } ?: "Unknown",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 8.dp)
                        )
                    }

                    // 详尽中文注释：M-20 修复 — 使用 book.id 作为稳定 key，避免书单排序后 item 状态错位
                    items(books, key = { it.book.id }) { book ->
                        AudiobookListItem(
                            title = book.book.title,
                            author = book.book.author,
                            narrator = book.book.narrator,
                            duration = book.book.totalDurationMs,
                            coverPath = book.book.thumbnailPath ?: book.book.coverPath,
                            coverLastUpdated = book.book.lastScannedAt, // 详尽中文注释：桥接 Room 层中的扫描/自愈重建毫秒时间戳，使用声明式设计促成图片同步强绘刷新
                            progressPercent = book.progressPercent,
                            onClick = { onNavigateToDetail(book.book.id) },
                            // 为每一次改动添加详尽的中文注释：长按有声书列表项时将当前书籍状态记录到 activeBookForMenu 中，用以唤起操作 Dialog 菜单
                            onLongClick = { activeBookForMenu = book }
                        ) { 
                            onLoadBook(book.book.id)
                            onNavigateToPlayer()
                        }
                    }
                }
            }
        }
    }

    // 为每一次改动添加详尽的中文注释：引入独立封装的长按操作系列 Dialog，保持主页 UI 布局清晰明了
    AudiobookActionDialogs(
        bookWithProgress = activeBookForMenu,
        hazeState = actionDialogHazeState,
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
        HomeScreen(
            // 详尽中文注释：Preview 中模拟 ViewModel 预计算后的数据结构
            selectedFilter = HomeFilter.NotStarted,
            groupedByAuthor = mockBooks.groupBy { it.book.author },
            recentBooks = mockBooks,
            shouldShowRecentBooks = true,
            recentTitleRes = R.string.recently_added_title,
            isMiniPlayerVisible = false,
            // 为每一次改动添加详尽的中文注释：Preview 显式引用设置模型里的默认玻璃效果，避免 HomeScreen 参数重新拥有局部默认值。
            glassEffectMode = AppSettings.DEFAULT_GLASS_EFFECT_MODE
        )
    }
}
