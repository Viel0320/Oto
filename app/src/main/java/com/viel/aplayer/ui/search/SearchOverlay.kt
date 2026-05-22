@file:OptIn(dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi::class)
package com.viel.aplayer.ui.search

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
// 为每一次改动添加详尽的中文注释：
// 显式导入 WindowInsets.ime 扩展属性，以便能在 Composable 内部自适应监听和计算软键盘拉起高度，确保列表不被遮挡。
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Clear
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SearchBar
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.viel.aplayer.data.entity.BookEntity
import com.viel.aplayer.data.entity.BookWithProgress
import com.viel.aplayer.data.store.GlassEffectMode
import com.viel.aplayer.data.store.SearchHistoryEntry
import com.viel.aplayer.ui.home.AudiobookListItem
import com.viel.aplayer.ui.theme.APlayerTheme

// 为每一次改动添加详尽的中文注释：
// 引入 Haze 磨砂玻璃修饰符和官方标准毛玻璃材质配置，提供极致视觉反馈。
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.materials.HazeMaterials

/**
 * 为每一次改动添加详尽的中文注释：
 * 全新设计的同 Activity 内非独立搜索悬浮层。
 * 包裹在带有垂直滑入滑出以及优雅渐显渐隐动画的 AnimatedVisibility 中。
 * 能够直接与底部的主页共享同一个 appHazeState，实现防穿帮、极致 premium 的毛玻璃视效。
 */
@Composable
fun SearchOverlay(
    searchViewModel: SearchViewModel,
    hazeState: dev.chrisbanes.haze.HazeState?,
    glassEffectMode: GlassEffectMode,
    onNavigateToDetail: (String) -> Unit,
    onLoadBook: (String) -> Unit,
    onNavigateToPlayer: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isVisible by searchViewModel.isVisible.collectAsState()

    // 为每一次改动添加详尽的中文注释：
    // 当全局设置开启了 Haze 模式时，我们将搜索悬浮层的展开与隐藏动画限制为“纯淡入淡出 (fadeIn/fadeOut)”。
    // 这能有效规避高斯模糊采样图层在高速滑入/滑出时可能产生的边缘裁剪或渲染闪烁，令磨砂玻璃的显隐动画更加极致、 premium 和平滑；
    // 而在常规非 Haze 模式下，则继续沿用原生的“滑入滑出 + 淡入淡出”丰富过渡效果。
    val isHaze = glassEffectMode == GlassEffectMode.Haze
    AnimatedVisibility(
        visible = isVisible,
        enter = if (isHaze) {
            fadeIn(animationSpec = tween(400))
        } else {
            slideInVertically(initialOffsetY = { it }, animationSpec = tween(400)) + fadeIn(animationSpec = tween(400))
        },
        exit = if (isHaze) {
            fadeOut(animationSpec = tween(400))
        } else {
            slideOutVertically(targetOffsetY = { it }, animationSpec = tween(400)) + fadeOut(animationSpec = tween(400))
        },
        modifier = modifier
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            // 为每一次改动添加详尽的中文注释：
            // 如果开启 Haze，将外层 Surface 容器背景置为透明，使系统渲染引擎可以将下方的 APlayerNavHost 内容透过来；
            // 否则退回原生 M3 background 背景填充。
            color = if (glassEffectMode == GlassEffectMode.Haze) Color.Transparent else MaterialTheme.colorScheme.background
        ) {
            SearchScreen(
                onBack = { searchViewModel.setVisible(false) },
                onNavigateToDetail = onNavigateToDetail,
                onLoadBook = onLoadBook,
                onNavigateToPlayer = onNavigateToPlayer,
                viewModel = searchViewModel,
                hazeState = hazeState,
                glassEffectMode = glassEffectMode
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    onBack: () -> Unit,
    onNavigateToDetail: (String) -> Unit,
    onLoadBook: (String) -> Unit,
    onNavigateToPlayer: () -> Unit,
    viewModel: SearchViewModel,
    hazeState: HazeState?,
    glassEffectMode: GlassEffectMode,
    modifier: Modifier = Modifier
) {
    val query by viewModel.query.collectAsState()
    val searchResults by viewModel.searchResults.collectAsState()
    val searchHistory by viewModel.searchHistory.collectAsState()

    SearchContent(
        query = query,
        searchResults = searchResults,
        searchHistory = searchHistory,
        commandSuggestions = commandSuggestionsFor(query),
        onQueryChange = { viewModel.onQueryChange(it) },
        onSearch = { viewModel.search(it) },
        onClearQuery = { viewModel.clearQuery() },
        onDeleteHistory = { viewModel.deleteHistory(it) },
        onClearHistory = { viewModel.clearHistory() },
        onBack = onBack,
        onNavigateToDetail = { id ->
            viewModel.saveSearchHistory(query.text)
            onNavigateToDetail(id)
        },
        onLoadBook = onLoadBook,
        onNavigateToPlayer = onNavigateToPlayer,
        autoFocus = true,
        hazeState = hazeState,
        glassEffectMode = glassEffectMode,
        modifier = modifier
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchContent(
    query: TextFieldValue,
    searchResults: List<BookWithProgress>,
    searchHistory: List<SearchHistoryEntry>,
    commandSuggestions: List<SearchCommand>,
    onQueryChange: (TextFieldValue) -> Unit,
    onSearch: (String) -> Unit,
    onClearQuery: () -> Unit,
    onDeleteHistory: (SearchHistoryEntry) -> Unit,
    onClearHistory: () -> Unit,
    onBack: () -> Unit,
    onNavigateToDetail: (String) -> Unit,
    onLoadBook: (String) -> Unit,
    onNavigateToPlayer: () -> Unit,
    hazeState: HazeState?,
    glassEffectMode: GlassEffectMode,
    modifier: Modifier = Modifier,
    autoFocus: Boolean = true,
) {
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val scrollState = rememberLazyListState()

    LaunchedEffect(scrollState.isScrollInProgress) {
        if (scrollState.isScrollInProgress) {
            focusManager.clearFocus()
        }
    }

    val handleBack = {
        focusManager.clearFocus()
        onBack()
    }

    LaunchedEffect(Unit) {
        if (autoFocus) {
            focusRequester.requestFocus()
        }
    }

    // 为每一次改动添加详尽的中文注释：
    // 感知 Haze 毛玻璃模式是否已被开启。
    val isHaze = glassEffectMode == GlassEffectMode.Haze && hazeState != null

    Scaffold(
        // 为每一次改动添加详尽的中文注释：
        // 如果启用 Haze 模式，将 Scaffold 容器底色改为透明，并挂载 hazeEffect 修饰符。
        // 这会令整个搜索界面实时折射下方的 APlayerNavHost 内容，形成美轮美奂的磨砂质感；
        // 非 Haze 模式下恢复原生 M3 background 色。
        modifier = modifier
            .fillMaxSize()
            .then(
                if (isHaze) {
                    // 为每一次改动添加详尽的中文注释：在 Haze 模式下链式追加 hazeEffect 高斯模糊修饰符，折射下方主页 NavHost 像素（利用 Kotlin 智能转换省去非空断言）
                    Modifier.hazeEffect(state = hazeState, style = HazeMaterials.regular())
                } else {
                    Modifier
                }
            ),
        containerColor = if (isHaze) Color.Transparent else MaterialTheme.colorScheme.background,
        topBar = {
            SearchBar(
                inputField = {
                    TextField(
                        value = query,
                        onValueChange = onQueryChange,
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(focusRequester),
                        placeholder = { 
                            Text(
                                text = "Search or use year: author: narrator:",
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            ) 
                        },
                        leadingIcon = {
                            IconButton(
                                onClick = handleBack,
                                modifier = Modifier.padding(start = 4.dp)
                            ) {
                                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                            }
                        },
                        trailingIcon = {
                            if (query.text.isNotEmpty()) {
                                IconButton(
                                    onClick = onClearQuery,
                                    modifier = Modifier.padding(end = 4.dp)
                                ) {
                                    Icon(Icons.Rounded.Clear, contentDescription = "Clear")
                                }
                            }
                        },
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            disabledContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                        ),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { 
                            focusManager.clearFocus()
                            onSearch(query.text) 
                        }),
                        singleLine = true,
                        shape = SearchBarDefaults.inputFieldShape
                    )
                },
                expanded = true,
                onExpandedChange = {
                    if (!it) handleBack()
                },
                // 为每一次改动添加详尽的中文注释：
                // 搜索栏也参与磨砂磨砂，若开启 Haze，搜索框设为透明偏亮的遮罩，否则退回 SearchBar 原生色。
                colors = SearchBarDefaults.colors(
                    containerColor = if (isHaze) Color.Transparent else MaterialTheme.colorScheme.surfaceContainerHigh
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                if (query.text.isBlank()) {
                    LazyColumn(
                        state = scrollState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(
                            top = 16.dp,
                            // 为每一次改动添加详尽的中文注释：
                            // 将 bottom padding 绑定为 WindowInsets.ime 替代原本只计算 navigationBars，
                            // 如此在键盘弹起时，bottom padding 会自动精确自适应累加键盘高度，确保列表滚到底时元素绝对不会被软键盘挡死，
                            // 并在收起键盘时完美降级回落为原生 NavigationBar 的底 padding，体验极其 premium。
                            bottom = 16.dp + WindowInsets.ime.asPaddingValues().calculateBottomPadding()
                        ),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (searchHistory.isNotEmpty()) {
                            item {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 4.dp)
                                ) {
                                    Text(
                                        text = "Recent Searches",
                                        style = MaterialTheme.typography.titleSmall,
                                        color = MaterialTheme.colorScheme.primary,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.align(Alignment.CenterStart)
                                    )
                                    Text(
                                        text = "Clear All",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.secondary,
                                        modifier = Modifier
                                            .align(Alignment.CenterEnd)
                                            .clickable { onClearHistory() }
                                            .padding(4.dp)
                                    )
                                }
                            }
                            items(
                                count = searchHistory.size,
                                key = { index -> searchHistory[index].query }
                            ) { index ->
                                val history = searchHistory[index]
                                ListItem(
                                    modifier = Modifier.clickable { 
                                        focusManager.clearFocus()
                                        onSearch(history.query) 
                                    },
                                    headlineContent = { 
                                        Text(
                                            text = history.query,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        ) 
                                    },
                                    leadingContent = {
                                        Icon(
                                            Icons.Rounded.History,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    },
                                    trailingContent = {
                                        IconButton(onClick = { onDeleteHistory(history) }) {
                                            Icon(
                                                Icons.Rounded.Clear,
                                                contentDescription = "Remove",
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                    },
                                    colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                                )
                            }
                        } else {
                            item {
                                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    Text(
                                        text = "No recent searches",
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                } else {
                    LazyColumn(
                        state = scrollState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(
                            top = 16.dp,
                            // 为每一次改动添加详尽的中文注释：
                            // 同样此处也将 bottom padding 自适应绑定为 WindowInsets.ime，确保搜索有结果时最后几项在键盘拉起状态下依然 100% 可滚动并展示出来，消除遮挡盲区。
                            bottom = 16.dp + WindowInsets.ime.asPaddingValues().calculateBottomPadding()
                        ),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (commandSuggestions.isNotEmpty()) {
                            item {
                                Text(
                                    text = "Filter by",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                                )
                            }
                            items(
                                count = commandSuggestions.size,
                                key = { index -> commandSuggestions[index].token }
                            ) { index ->
                                val cmd = commandSuggestions[index]
                                ListItem(
                                    modifier = Modifier.clickable { 
                                        val text = query.text
                                        val cursor = query.selection.start
                                        val lastSpace = text.lastIndexOf(' ', cursor - 1)
                                        
                                        val prefix = if (lastSpace == -1) "" else text.substring(0, lastSpace + 1)
                                        val suffix = text.substring(cursor)
                                        val newText = "$prefix${cmd.token}$suffix"
                                        val newCursorPos = prefix.length + cmd.token.length
                                        
                                        onQueryChange(TextFieldValue(
                                            text = newText,
                                            selection = TextRange(newCursorPos)
                                        ))
                                    },
                                    headlineContent = { 
                                        Text(
                                            text = cmd.token,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        ) 
                                    },
                                    supportingContent = {
                                        Text(
                                            text = cmd.description,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    },
                                    leadingContent = {
                                        Icon(
                                            Icons.Rounded.Search,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                    },
                                    colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                                )
                            }
                        }

                        if (searchResults.isEmpty()) {
                            item {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 32.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "No results found for \"${query.text}\"",
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        } else {
                            item {
                                Text(
                                    text = "Results",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 4.dp)
                                )
                            }
                            items(
                                count = searchResults.size,
                                key = { index -> searchResults[index].book.id }
                            ) { index ->
                                val result = searchResults[index]
                                AudiobookListItem(
                                    title = result.book.title,
                                    author = result.book.author,
                                    narrator = result.book.narrator,
                                    duration = result.book.totalDurationMs,
                                    coverPath = result.book.thumbnailPath ?: result.book.coverPath,
                                    coverLastUpdated = result.book.lastScannedAt, 
                                    progressPercent = result.progressPercent,
                                    onClick = { 
                                        focusManager.clearFocus()
                                        onNavigateToDetail(result.book.id) 
                                    }
                                ) {
                                    focusManager.clearFocus()
                                    onLoadBook(result.book.id)
                                    onNavigateToPlayer()
                                }
                            }
                        }
                    }
                }
            }
        }
    ) { padding ->
        Box(modifier = Modifier.padding(padding))
    }
}

data class SearchCommand(
    val token: String,
    val description: String
)

private val searchCommands = listOf(
    SearchCommand("Year:", "Search by release year"),
    SearchCommand("Author:", "Search by author name"),
    SearchCommand("Narrator:", "Search by narrator name")
)

private fun commandSuggestionsFor(value: TextFieldValue): List<SearchCommand> {
    val text = value.text
    val cursor = value.selection.start
    if (cursor == 0) return emptyList()

    val lastSpace = text.lastIndexOf(' ', cursor - 1)
    val currentWord = text.substring(lastSpace + 1, cursor)

    return if (currentWord.isNotEmpty() && !currentWord.contains(":")) {
        searchCommands.filter { it.token.startsWith(currentWord, ignoreCase = true) }
    } else {
        emptyList()
    }
}

@Preview(showBackground = true)
@Composable
fun SearchScreenEmptyPreview() {
    APlayerTheme {
        SearchContent(
            query = TextFieldValue(""),
            searchResults = emptyList(),
            searchHistory = listOf(
                SearchHistoryEntry("Android Development", System.currentTimeMillis()),
                SearchHistoryEntry("Jetpack Compose", System.currentTimeMillis())
            ),
            commandSuggestions = emptyList(),
            onQueryChange = {},
            onSearch = {},
            onClearQuery = {},
            onDeleteHistory = {},
            onClearHistory = {},
            onBack = {},
            onNavigateToDetail = {},
            onLoadBook = {},
            onNavigateToPlayer = {},
            hazeState = null,
            glassEffectMode = GlassEffectMode.Material,
            autoFocus = false
        )
    }
}

@Preview(showBackground = true)
@Composable
fun SearchScreenResultsPreview() {
    val mockBooks = listOf(
        BookWithProgress(
            book = BookEntity(
                id = "id1",
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
        SearchContent(
            query = TextFieldValue("Megachurch"),
            searchResults = mockBooks,
            searchHistory = emptyList(),
            commandSuggestions = emptyList(),
            onQueryChange = {},
            onSearch = {},
            onClearQuery = {},
            onDeleteHistory = {},
            onClearHistory = {},
            onBack = {},
            onNavigateToDetail = {},
            onLoadBook = {},
            onNavigateToPlayer = {},
            hazeState = null,
            glassEffectMode = GlassEffectMode.Material,
            autoFocus = false
        )
    }
}
