package com.viel.aplayer.ui.search

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
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
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SearchBar
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
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
import com.viel.aplayer.ui.home.components.ListItem
import com.viel.aplayer.ui.theme.APlayerTheme
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.blur
import top.yukonga.miuix.kmp.blur.drawBackdrop

/**
 * 纯无状态的搜索界面 UI 展现组件（Stateless）。
 *
 * 经过物理架构拆分与组件解耦，SearchContent 不再依赖任何特定的 ViewModel 或是业务数据流。
 * 所有的搜索关键字输入、搜索触发、删除历史、点击书籍等复杂逻辑，全部转化为清晰易懂的声明式入参以及 Lambda 回调。
 * 这极大清空了 UI 组件的职责，消除了不必要的重组，同时为 Compose Previews 提供了无障碍的即时预览能力。
 */
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
    backdrop: LayerBackdrop?,
    glassEffectMode: GlassEffectMode,
    modifier: Modifier = Modifier,
    autoFocus: Boolean = true,
) {
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val scrollState = rememberLazyListState()

    // 详详尽中文注释：基于运行时系统 WindowInsets.safeDrawing 动态感知侧向刘海屏及横屏导航栏，完全零硬编码避让
    val safeDrawingPadding = WindowInsets.safeDrawing.asPaddingValues()
    val layoutDirection = androidx.compose.ui.platform.LocalLayoutDirection.current
    val searchStartPadding = safeDrawingPadding.calculateStartPadding(layoutDirection) + 16.dp
    val searchEndPadding = safeDrawingPadding.calculateEndPadding(layoutDirection) + 16.dp

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

    // 详尽中文注释：感知 miuix-blur 磨砂玻璃模式是否已被开启且采样源不为空
    val isBlur = glassEffectMode == GlassEffectMode.MiuixBlur && backdrop != null

    Scaffold(
        // 详尽中文注释：如果启用 miuix-blur 模式，将 Scaffold 容器底色改为透明，并挂载 drawBackdrop 修饰符与 background 半透混色底。
        // 这会令整个搜索界面实时折射下方的 APlayerNavHost 内容，形成美轮美奂的磨砂质感；
        // 非 miuix-blur 模式下恢复原生 M3 background 色。
        modifier = modifier
            .fillMaxSize()
            .then(
                if (isBlur) {
                    Modifier
                        // 使用 drawBackdrop 渲染折射模糊效果，并补全必填的 shape 参数
                        .drawBackdrop(
                            backdrop = backdrop,
                            shape = { RectangleShape },
                            effects = {
                                blur(20f)
                            }
                        )
                        // 使用 background 链式附加一层偏亮的半透明蒙版底色（亮/暗自适应），防止搜索界面内容被下方主页文本影响而视觉穿帮
                        .background(
                            if (androidx.compose.foundation.isSystemInDarkTheme()) {
                                Color.Black.copy(alpha = 0.6f)
                            } else {
                                Color.White.copy(alpha = 0.6f)
                            }
                        )
                } else {
                    Modifier
                }
            ),
        containerColor = if (isBlur) Color.Transparent else MaterialTheme.colorScheme.background,
        topBar = {
            SearchBar(
                inputField = {
                    TextField(
                        value = query,
                        onValueChange = onQueryChange,
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(focusRequester)
                            // 在此处应用 WindowInsets.safeDrawing 运行时左右物理安全边距避让，
                            // 确保输入区域内部的返回图标与清除图标在横屏状态下不被刘海物理裁切，同时保证 SearchBar 的背景色能够彻底铺满屏幕
                            .padding(
                                start = safeDrawingPadding.calculateStartPadding(layoutDirection),
                                end = safeDrawingPadding.calculateEndPadding(layoutDirection)
                            ),
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
                // 详尽中文注释：搜索栏也参与磨砂，若开启 miuix-blur，搜索框设为透明偏亮的遮罩，否则退回 SearchBar 原生色。
                colors = SearchBarDefaults.colors(
                    containerColor = if (isBlur) Color.Transparent else MaterialTheme.colorScheme.surfaceContainerHigh
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                if (query.text.isBlank()) {
                    LazyColumn(
                        state = scrollState,
                        modifier = Modifier.fillMaxSize(),
                        // 应用动态算出的 start/end 物理安全区 Padding，彻底解决横屏刘海物理裁切
                        contentPadding = PaddingValues(
                            start = searchStartPadding,
                            end = searchEndPadding,
                            top = 16.dp,
                            // 详尽中文注释：将 bottom padding 绑定为 WindowInsets.ime 替代原本只计算 navigationBars，
                            // 如此在键盘弹起时，bottom padding 会自动精确自适应累加键盘高度，确保列表滚到底时元素绝对不会被软键盘挡死，
                            // 并在收起键盘时完美降级回落为原生 NavigationBar 的底 padding，体验极其优秀。
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
                                androidx.compose.material3.ListItem(
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
                        // 应用动态算出的左右物理避让区，确保搜索结果列表防刘海遮挡
                        contentPadding = PaddingValues(
                            start = searchStartPadding,
                            end = searchEndPadding,
                            top = 16.dp,
                            // 详尽中文注释：同样此处也将 bottom padding 自适应绑定为 WindowInsets.ime，确保搜索有结果时最后几项在键盘拉起状态下依然 100% 可滚动并展示出来，消除遮挡盲区。
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
                                androidx.compose.material3.ListItem(
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
                                ListItem(
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

/**
 * 搜索指令参数辅助模型。
 */
data class SearchCommand(
    val token: String,
    val description: String
)

/**
 * 静态定义的内置高级过滤指令组。
 */
private val searchCommands = listOf(
    SearchCommand("Year:", "Search by release year"),
    SearchCommand("Author:", "Search by author name"),
    SearchCommand("Narrator:", "Search by narrator name")
)

/**
 * 详尽中文注释：基于当前输入内容和光标位置，智能匹配并计算出合适的过滤指令提示词。
 */
fun commandSuggestionsFor(value: TextFieldValue): List<SearchCommand> {
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
            backdrop = null,
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
            backdrop = null,
            glassEffectMode = GlassEffectMode.Material,
            autoFocus = false
        )
    }
}
