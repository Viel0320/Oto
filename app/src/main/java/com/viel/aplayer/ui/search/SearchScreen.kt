package com.viel.aplayer.ui.search


import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
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
import androidx.lifecycle.viewmodel.compose.viewModel
import com.viel.aplayer.data.entity.BookEntity
import com.viel.aplayer.data.entity.BookWithProgress
import com.viel.aplayer.data.store.SearchHistoryEntry
import com.viel.aplayer.ui.home.AudiobookListItem
import com.viel.aplayer.ui.theme.APlayerTheme

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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    modifier: Modifier = Modifier,
    initialQuery: String? = null,
    onBack: () -> Unit = {},
    onNavigateToDetail: (String) -> Unit = {},
    onLoadBook: (String) -> Unit = {},
    onNavigateToPlayer: () -> Unit = {},
    viewModel: SearchViewModel = viewModel()
) {
    val query by viewModel.query.collectAsState()
    val searchResults by viewModel.searchResults.collectAsState()
    val searchHistory by viewModel.searchHistory.collectAsState()

    LaunchedEffect(initialQuery) {
        if (!initialQuery.isNullOrBlank()) {
            // 只要 initialQuery 不为空，就强制执行新搜索，从而实现自动清空已有文本并跳转的功�?
            viewModel.search(initialQuery)
        }
    }

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
        autoFocus = initialQuery.isNullOrBlank(),
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

    // 详尽中文注释：为了让搜索�?100% 呈现 Android 系统级原生的预测性返回手势动画（向右拉出预览�?pop 退出）�?
    // 此处彻底移除了对返回事件�?PredictiveBackHandler 手动拦截�?
    // 返回手势事件直接移交系统�?Compose Navigation 接管。当用户向右滑动返回时，
    // 系统将顺畅显示原生页面退场过渡，并在最终退出销毁时自动清空输入框焦点与收起软键盘�?

    LaunchedEffect(Unit) {
        if (autoFocus) {
            focusRequester.requestFocus()
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
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
                modifier = Modifier.fillMaxWidth()
            ) {
                if (query.text.isBlank()) {
                    LazyColumn(
                        state = scrollState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(
                            top = 16.dp,
                            bottom = 16.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
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
                            items(searchHistory.size) { index ->
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
                            bottom = 16.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
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
                            items(commandSuggestions.size) { index ->
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
                            items(searchResults.size) { index ->
                                val result = searchResults[index]
                                AudiobookListItem(
                                    title = result.book.title,
                                    author = result.book.author,
                                    narrator = result.book.narrator,
                                    duration = result.book.totalDurationMs,
                                    coverPath = result.book.thumbnailPath ?: result.book.coverPath,
                                    coverLastUpdated = result.book.lastScannedAt, // 详尽中文注释：桥�?Room 层的 lastScannedAt 更新时间戳，当封面自愈触发时强行刷新 Coil 缓存以显示新封面
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
            autoFocus = false
        )
    }
}
