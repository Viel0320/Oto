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
import com.viel.aplayer.ui.common.CoverImageSourceSelector
import com.viel.aplayer.ui.common.theme.APlayerTheme
import com.viel.aplayer.ui.home.components.ListItem
import com.viel.aplayer.ui.motion.SharedElementKeys
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import dev.chrisbanes.haze.materials.HazeMaterials

/**
 * SearchContent Setup (Stateless Search Content UI)
 *
 * Pure stateless search content UI rendering component (Stateless).
 * Through architectural separation and component decoupling, SearchContent no longer depends on any specific ViewModel or business data stream.
 * All complex logics like query input, search trigger, history deletion, and book click are converted into declarative parameters and Lambda callbacks,
 * greatly simplifying UI responsibilities, eliminating unnecessary recompositions, and providing barrier-free instant preview capabilities for Compose Previews.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalHazeMaterialsApi::class)
@Composable
fun SearchContent(
    modifier: Modifier = Modifier,
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
    /*
     * Active Search Detail Book Id (Search-result source visibility selector)
     *
     * Hides only the selected search-result thumbnail during Search -> Detail motion, keeping
     * Home recent and Home list sources independent even when they contain the same audiobook.
     */
    activeSearchDetailBookId: String? = null,
    onNavigateToDetail: (String) -> Unit,
    onLoadBook: (String) -> Unit,
    onNavigateToPlayer: () -> Unit,
    // Setup Haze State (Transition backdrop reference to HazeState)
    hazeState: HazeState? = null,
    glassEffectMode: GlassEffectMode,
    autoFocus: Boolean = true,
) {
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val scrollState = rememberLazyListState()

    // Dynamically sense side cutout screen and landscape navigation bar based on runtime WindowInsets.safeDrawing, avoiding hard-coded offsets entirely
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

    // Detect whether MiuixBlur frosted glass mode is enabled and sampling source is not null
    // Determine Glass Blur Status (Enable blur only if in Haze mode and state is provided)
    val isBlur = glassEffectMode == GlassEffectMode.Haze && hazeState != null

    Scaffold(
        // Apply MiuixBlur Background (Frosted Glass Mask Setup)
        // If miuix-blur mode is enabled, set Scaffold container base color to transparent, and mount drawBackdrop modifier with background translucent blend.
        // This makes the entire search interface refract underlying APlayerNavHost content, forming a beautiful frosted texture.
        // Fall back to native M3 background color in non-miuix-blur mode.
        modifier = modifier
            .fillMaxSize()
            .then(
                if (isBlur) {
                    Modifier
                        // Setup SearchContent Haze (Apply hazeChild to Scaffold to blur underlying home screen contents)
                        // Remove Search Haze Shape (Use default shape matching host) Haze 1.x hazeChild does not take shape parameter.
                        .hazeEffect(
                            state = hazeState,
                            style = HazeMaterials.regular()
                        )
                        // Chain background to append a translucent mask color (light/dark adaptive) to prevent search screen contents from blending with home page text
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
                            // Safe Drawing Margin (Cutout Avoidance Padding)
                            // Apply WindowInsets.safeDrawing horizontal physical safety margins here
                            // to ensure internal back and clear icons are not clipped in landscape mode, while allowing SearchBar background to fill screen edges.
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
                // Search bar also participates in frosting. If miuix-blur is enabled, search box uses transparent/light mask, otherwise falls back to SearchBar native color.
                colors = SearchBarDefaults.colors(
                    containerColor = if (isBlur) Color.Transparent else MaterialTheme.colorScheme.surfaceContainerHigh
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                if (query.text.isBlank()) {
                    LazyColumn(
                        state = scrollState,
                        modifier = Modifier.fillMaxSize(),
                        // Apply dynamically calculated start/end physical safe area Padding to resolve landscape cutout clipping entirely
                        contentPadding = PaddingValues(
                            start = searchStartPadding,
                            end = searchEndPadding,
                            top = 16.dp,
                            // Keyboard Inset Avoidance (Adaptive Bottom Padding Adjustment)
                            // Bind bottom padding to WindowInsets.ime instead of only navigationBars.
                            // When keyboard pops up, bottom padding adaptively adds keyboard height, preventing elements from being blocked by the soft keyboard.
                            // Falling back to native NavigationBar bottom padding when keyboard is hidden.
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
                        // Apply dynamically calculated left/right physical safety paddings, ensuring search results list avoids cutout occlusion
                        contentPadding = PaddingValues(
                            start = searchStartPadding,
                            end = searchEndPadding,
                            top = 16.dp,
                            // Similarly bind bottom padding to WindowInsets.ime here, ensuring that when search has results, the last items are still fully scrollable and visible when keyboard is raised.
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
                                    bookId = result.book.id,
                                    title = result.book.title,
                                    author = result.book.author,
                                    narrator = result.book.narrator,
                                    duration = result.book.totalDurationMs,
                                    // Search Thumbnail Selection (Thumbnail Small Preferred)
                                    // Search results use small thumbnail image size, reusing CoverImageSourceSelector.small.
                                    // Thumbnail-preferred rule is centrally expressed in the selector, so adjustments don't require changes in the search page.
                                    coverPath = CoverImageSourceSelector.small(
                                        thumbnailPath = result.book.thumbnailPath,
                                        coverPath = result.book.coverPath
                                    ),
                                    coverLastUpdated = result.book.lastScannedAt, 
                                    progressPercent = result.progressPercent,
                                    /*
                                     * Search Detail Source Activity (Search-result source trigger)
                                     *
                                     * Activates only for the result opened from Search so the
                                     * selected thumbnail exits without affecting Home channels.
                                     */
                                    isDetailTargetActive = result.book.id == activeSearchDetailBookId,
                                    /*
                                     * Search Detail Shared Element Key (Search channel binding)
                                     *
                                     * Uses the search-specific key so Search result artwork cannot
                                     * pair with Home recent or Home list covers for the same book.
                                     */
                                    sharedElementKey = SharedElementKeys.search2detailCover(result.book.id),
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
 * SearchCommand Model (Search Directive Helper Model)
 *
 * Helper model for search directive parameters.
 */
data class SearchCommand(
    val token: String,
    val description: String
)

/**
 * Built-in Directives (Static Filter Directives)
 *
 * Statically defined built-in advanced filter directives.
 */
private val searchCommands = listOf(
    SearchCommand("Year:", "Search by release year"),
    SearchCommand("Author:", "Search by author name"),
    SearchCommand("Narrator:", "Search by narrator name")
)

/**
 * Resolve Directives (Smart Directive Matching)
 *
 * Intelligently matches and calculates appropriate filter directives based on current input text and cursor position.
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
