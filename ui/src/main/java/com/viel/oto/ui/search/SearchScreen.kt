package com.viel.oto.ui.search

import androidx.annotation.StringRes
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
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.viel.oto.shared.R
import com.viel.oto.application.library.search.SearchHistoryItem
import com.viel.oto.application.library.search.SearchResultSnapshot
import com.viel.oto.shared.settings.GlassEffectMode
import com.viel.oto.ui.common.CoverImageSourceSelector
import com.viel.oto.ui.common.layout.LocalAppWindowSizeClass
import com.viel.oto.ui.common.theme.LocalDarkTheme
import com.viel.oto.ui.common.theme.OtoTheme
import com.viel.oto.ui.home.components.ListItem
import com.viel.oto.ui.motion.SharedElementKeys
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import dev.chrisbanes.haze.materials.HazeMaterials

/**
 * Stateless search screen adapter.
 *
 * Receives all state and callbacks from SearchRoute, then forwards them to the stateless SearchContent.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    modifier: Modifier = Modifier,
    query: TextFieldValue,
    searchResults: List<SearchResultSnapshot>,
    searchHistory: List<SearchHistoryItem>,
    onQueryChange: (TextFieldValue) -> Unit,
    onSearch: (String) -> Unit,
    onClearQuery: () -> Unit,
    onDeleteHistory: (SearchHistoryItem) -> Unit,
    onClearHistory: () -> Unit,
    onBack: () -> Unit,
    activeSearchDetailBookId: String? = null,
    onNavigateToDetail: (String) -> Unit,
    onLoadBook: (String) -> Unit,
    onNavigateToPlayer: () -> Unit,
    hazeState: HazeState? = null,
    glassEffectMode: GlassEffectMode
) {
    SearchContent(
        query = query,
        searchResults = searchResults,
        searchHistory = searchHistory,
        commandSuggestions = commandSuggestionsFor(query),
        onQueryChange = onQueryChange,
        onSearch = onSearch,
        onClearQuery = onClearQuery,
        onDeleteHistory = onDeleteHistory,
        onClearHistory = onClearHistory,
        onBack = onBack,
        activeSearchDetailBookId = activeSearchDetailBookId,
        onNavigateToDetail = onNavigateToDetail,
        onLoadBook = onLoadBook,
        onNavigateToPlayer = onNavigateToPlayer,
        autoFocus = true,
        hazeState = hazeState,
        glassEffectMode = glassEffectMode,
        modifier = modifier
    )
}

/**
 * Renders the search surface from immutable state and callbacks supplied by the route.
 *
 * Keeping query input, search execution, history deletion, and book opening as parameters leaves this
 * component previewable and prevents it from depending on a concrete ViewModel or persistence stream.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalHazeMaterialsApi::class)
@Composable
fun SearchContent(
    modifier: Modifier = Modifier,
    query: TextFieldValue,
    searchResults: List<SearchResultSnapshot>,
    searchHistory: List<SearchHistoryItem>,
    commandSuggestions: List<SearchCommand>,
    onQueryChange: (TextFieldValue) -> Unit,
    onSearch: (String) -> Unit,
    onClearQuery: () -> Unit,
    onDeleteHistory: (SearchHistoryItem) -> Unit,
    onClearHistory: () -> Unit,
    onBack: () -> Unit,
    activeSearchDetailBookId: String? = null,
    onNavigateToDetail: (String) -> Unit,
    onLoadBook: (String) -> Unit,
    onNavigateToPlayer: () -> Unit,
    hazeState: HazeState? = null,
    glassEffectMode: GlassEffectMode,
    autoFocus: Boolean = true,
) {
    val searchPlaceholderText = stringResource(R.string.search_placeholder, SEARCH_DIRECTIVE_HINT)
    val backContentDescription = stringResource(R.string.back_content_description)
    val clearContentDescription = stringResource(R.string.clear_content_description)
    val recentSearchesText = stringResource(R.string.search_recent_title)
    val clearAllText = stringResource(R.string.search_clear_all)
    val removeHistoryContentDescription = stringResource(R.string.search_remove_history_content_description)
    val noRecentSearchesText = stringResource(R.string.search_no_recent)
    val filterByText = stringResource(R.string.search_filter_by)
    val resultsText = stringResource(R.string.search_results_title)

    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val scrollState = rememberLazyListState()
    val screenHorizontalPadding = LocalAppWindowSizeClass.current.screenHorizontalPadding

    val safeDrawingPadding = WindowInsets.safeDrawing.asPaddingValues()
    val layoutDirection = androidx.compose.ui.platform.LocalLayoutDirection.current
    val searchStartPadding = safeDrawingPadding.calculateStartPadding(layoutDirection) + screenHorizontalPadding
    val searchEndPadding = safeDrawingPadding.calculateEndPadding(layoutDirection) + screenHorizontalPadding

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

    val isBlur = glassEffectMode == GlassEffectMode.Haze && hazeState != null

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .then(
                if (isBlur) {
                    Modifier
                        .hazeEffect(
                            state = hazeState,
                            style = HazeMaterials.ultraThin()
                        )
                        .background(
                            if (LocalDarkTheme.current) {
                                Color.Black.copy(alpha = 0.32f)
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
                            .padding(
                                start = safeDrawingPadding.calculateStartPadding(layoutDirection),
                                end = safeDrawingPadding.calculateEndPadding(layoutDirection)
                            ),
                        placeholder = {
                            Text(
                                text = searchPlaceholderText,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        },
                        leadingIcon = {
                            IconButton(
                                onClick = handleBack,
                                modifier = Modifier.padding(start = 4.dp)
                            ) {
                                Icon(
                                    Icons.AutoMirrored.Rounded.ArrowBack,
                                    contentDescription = backContentDescription,
                                    tint = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        },
                        trailingIcon = {
                            if (query.text.isNotEmpty()) {
                                IconButton(
                                    onClick = onClearQuery,
                                    modifier = Modifier.padding(end = 4.dp)
                                ) {
                                    Icon(
                                        Icons.Rounded.Clear,
                                        contentDescription = clearContentDescription,
                                        tint = MaterialTheme.colorScheme.onSurface
                                    )
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
                colors = SearchBarDefaults.colors(
                    containerColor = if (isBlur) Color.Transparent else MaterialTheme.colorScheme.surfaceContainerHigh
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                if (query.text.isBlank()) {
                    LazyColumn(
                        state = scrollState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(
                            start = searchStartPadding,
                            end = searchEndPadding,
                            top = 16.dp,
                            bottom = 16.dp + WindowInsets.ime.asPaddingValues().calculateBottomPadding()
                        ),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (searchHistory.isNotEmpty()) {
                            item {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(
                                            start = screenHorizontalPadding,
                                            end = screenHorizontalPadding,
                                            top = 8.dp,
                                            bottom = 4.dp
                                        )
                                ) {
                                    Text(
                                        text = recentSearchesText,
                                        style = MaterialTheme.typography.titleSmall,
                                        color = MaterialTheme.colorScheme.primary,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.align(Alignment.CenterStart)
                                    )
                                    TextButton(
                                        onClick = onClearHistory,
                                        // Material's compact text button can measure at 40.dp here;
                                        // the explicit floor preserves the Android accessibility target.
                                        modifier = Modifier
                                            .align(Alignment.CenterEnd)
                                            .heightIn(min = 48.dp)
                                            .minimumInteractiveComponentSize()
                                    ) {
                                        Text(
                                            text = clearAllText,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
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
                                                contentDescription = removeHistoryContentDescription,
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
                                        text = noRecentSearchesText,
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
                            start = searchStartPadding,
                            end = searchEndPadding,
                            top = 16.dp,
                            bottom = 16.dp + WindowInsets.ime.asPaddingValues().calculateBottomPadding()
                        ),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (commandSuggestions.isNotEmpty()) {
                            item {
                                Text(
                                    text = filterByText,
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(
                                        horizontal = screenHorizontalPadding,
                                        vertical = 4.dp
                                    )
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
                                            text = stringResource(cmd.descriptionRes),
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
                                        text = stringResource(R.string.search_no_results_for, query.text),
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        } else {
                            item {
                                Text(
                                    text = resultsText,
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(
                                        start = screenHorizontalPadding,
                                        end = screenHorizontalPadding,
                                        top = 8.dp,
                                        bottom = 4.dp
                                    )
                                )
                            }
                            items(
                                count = searchResults.size,
                                key = { index -> searchResults[index].id }
                            ) { index ->
                                val result = searchResults[index]
                                ListItem(
                                    bookId = result.id,
                                    title = result.title,
                                    author = result.author,
                                    narrator = result.narrator,
                                    duration = result.totalDurationMs,
                                    coverPath = CoverImageSourceSelector.small(
                                        thumbnailPath = result.thumbnailPath,
                                        coverPath = result.coverPath
                                    ),
                                    coverLastUpdated = result.coverLastUpdated,
                                    progressPercent = result.progressPercent,
                                    isDetailTargetActive = result.id == activeSearchDetailBookId,
                                    sharedElementKey = SharedElementKeys.search2detailCover(result.id),
                                    onClick = {
                                        focusManager.clearFocus()
                                        onNavigateToDetail(result.id)
                                    },
                                    onPlayClick = {
                                        focusManager.clearFocus()
                                        onLoadBook(result.id)
                                        onNavigateToPlayer()
                                    }
                                )
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
 * Describes one stable search directive token and its localized explanation.
 */
data class SearchCommand(
    val token: String,
    @field:StringRes val descriptionRes: Int
)

/**
 * Built-in directive tokens recognized by the search query parser.
 */
private const val SEARCH_DIRECTIVE_HINT = "year: author: narrator:"

private val searchCommands = listOf(
    SearchCommand("Year:", R.string.search_command_year_description),
    SearchCommand("Author:", R.string.search_command_author_description),
    SearchCommand("Narrator:", R.string.search_command_narrator_description)
)

/**
 * Matches filter directive suggestions against the word currently being edited.
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
    OtoTheme {
        SearchContent(
            query = TextFieldValue(""),
            searchResults = emptyList(),
            searchHistory = listOf(
                SearchHistoryItem("Android Development", System.currentTimeMillis()),
                SearchHistoryItem("Jetpack Compose", System.currentTimeMillis())
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
        SearchResultSnapshot(
            id = "id1",
            title = "In the Megachurch",
            author = "Ryo Asai",
            narrator = "Narrator A",
            totalDurationMs = 44580000L,
            thumbnailPath = null,
            coverPath = null,
            coverLastUpdated = System.currentTimeMillis(),
            progressPercent = 0
        )
    )
    OtoTheme {
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
