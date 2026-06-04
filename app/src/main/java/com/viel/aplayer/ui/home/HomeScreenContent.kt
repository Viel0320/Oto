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
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.haze
import kotlinx.coroutines.launch

/**
 * HomeScreenContent Setup (Stateless Home Main Content UI)
 *
 * Stateless home main content rendering component, representing the pure UI rendering layer.
 * Through decoupling and refactoring, HomeScreenContent has achieved complete statelessness, no longer holding any ViewModel or context reference.
 * All interactions and data delivery are performed entirely through pure declarative parameters and Lambda callbacks, greatly improving the unit testability and preview flexibility of the component.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreenContent(
    modifier: Modifier = Modifier,
    // Pre-calculated Fields (Dismantled from LibraryUiState)
    // The following are pre-calculated fields dismantled and passed from LibraryUiState, so the Composable does not need to perform remember operations anymore.
    // When selectedFilter is null, it means the combine pipeline of the ViewModel has not yet produced the first final decision. At this time, the FilterChip row is not rendered to avoid jumping animations.
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
    // New callback for updating reading status, responding to marking actions in the long-press dialog menu
    onUpdateReadStatus: (String, String) -> Unit = { _, _ -> },
    // New callback for forcing regeneration of cover and metadata, triggered from the long-press menu
    onForceRegenerate: (String) -> Unit = {},
    // New callback for deleting books, triggered from the long-press menu's secondary confirmation soft deletion
    onDeleteBook: (String) -> Unit = {},
) {
    // Use remember to listen to the currently long-pressed audiobook state, determining the rendering of the first-level dialog
    var activeBookForMenu by remember { mutableStateOf<BookWithProgress?>(null) }

    // Create HazeState for long-press operation dialog; Scaffold as sampling source, Dialog panel as blur rendering surface.
    val homeHazeState = remember { HazeState() }
    // Label mapping of Filter Chips, which is pure UI text and remains in the Composable
    val filters = listOf(
        HomeFilter.NotStarted to stringResource(R.string.filter_not_started),
        HomeFilter.InProgress to stringResource(R.string.filter_in_progress),
        HomeFilter.Finished to stringResource(R.string.filter_finished)
    )
    // Resolve recentTitleRes pre-calculated from ViewModel into a localized string (0 means no display needed)
    val recentTitle = if (recentTitleRes != 0) stringResource(recentTitleRes) else ""
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        uri?.let(onLibraryRootSelected)
    }

    // WindowClass Adaptation Setup (Determine Adaptation Configuration)
    // Use the unified WindowClass interface to obtain current window size class, columns count, and horizontal margins.
    // This avoids hard-coded layout judgments by reading configurations directly through LocalConfiguration, improving high cohesion and scalability for multi-device adaptation.
    val windowClass = LocalWindowClass.current
    val columnsCount = windowClass.columnsCount
    val screenHorizontalPadding = windowClass.screenHorizontalPadding

    // TopAppBar Compensation Padding (Align Top Bar Icons)
    // Calculate the compensation padding for TopAppBar icons.
    // The default start margin of M3 top bar icons is 16dp (4dp container margin + 12dp button centering offset).
    // When the business margin increases to 24dp, an extra 8dp needs to be compensated to align front and back.
    val appBarIconPadding = (screenHorizontalPadding - 16.dp).coerceAtLeast(0.dp)

    // Exclude Keyboard Insets (Decouple Keyboard from Safe Area)
    // Use WindowInsets.safeDrawing to dynamically obtain current status bar, navigation bar, and physical cutout, and explicitly call exclude(WindowInsets.ime).
    // This cuts off physical impact of the software keyboard (IME) on home page's perceived safe area padding, preventing unnecessary recombinations of HomeScreen due to changes in safe area heights.
    val safeDrawingPadding = WindowInsets.safeDrawing.exclude(WindowInsets.ime).asPaddingValues()
    val layoutDirection = androidx.compose.ui.platform.LocalLayoutDirection.current
    // Edge-to-Edge Grid Padding (Exquisite Edge Visual Style)
    // Refactor grid padding strategy: gridStart/EndPadding here only retains physical safe areas (e.g., cutout, side navigation bar).
    // Strip 16dp/24dp business margins from Grid container layer, sinking it into specific titles and list items.
    // This ensures that ListItem ripple effects and scrolling scrollbars can cling to physical screen edges, achieving the ultimate premium edge-to-edge feel.
    val gridStartPadding = safeDrawingPadding.calculateStartPadding(layoutDirection)
    val gridEndPadding = safeDrawingPadding.calculateEndPadding(layoutDirection)

    // Migrate the scroll state remember from LazyListState to adaptive grid GridState to complete the base upgrade.
    val gridState = rememberLazyGridState()
    val scope = rememberCoroutineScope()
    // Only Haze mode registers the home page full content as sampling source; Material mode skips it to save rendering cost.
    val blurSourceModifier = if (glassEffectMode == GlassEffectMode.Haze) {
        // Setup HomeScreen Haze (Apply haze modifier to container to make it a blur source)
        Modifier.haze(homeHazeState)
    } else {
        Modifier
    }

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            // Home page full content as the miuix-blur background sampling source of AudiobookActionDialogs.
            .then(blurSourceModifier),
        // Exclude Keyboard Insets (Prevent Layout Re-measurement)
        // Explicitly exclude the software keyboard (IME) from default contentWindowInsets.
        // This cuts off layout re-measurement and unnecessary home page recombinations caused by innerPadding changes from software keyboard popups.
        contentWindowInsets = WindowInsets.safeDrawing.exclude(WindowInsets.ime),
        topBar = {
            CenterAlignedTopAppBar(
                // Restore default modifier without adding padding outside the container, allowing top bar background or frosted refraction surface to stretch to screen physical edges
                modifier = Modifier,
                // Exclude Keyboard Insets (Prevent Title Bar Recombination)
                // Explicitly exclude WindowInsets.ime in addition to safeDrawing.exclude(navigationBars) to ensure the top bar is unaffected by keyboard height, avoiding unnecessary recombinations.
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
                                        // Double-click TopAppBar to scroll to top, mapped to adaptive gridState to achieve perfect compatibility.
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
                        // Apply appBarIconPadding compensation to align search icon precisely with bottom contents in landscape/large screen modes
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
                        // Apply appBarIconPadding compensation to keep setting icon symmetrically aligned on the right boundary
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
            // LazyVerticalGrid Setup (Adaptive Card Grid)
            // Upgrade and refactor the single-column LazyColumn to a new and powerful LazyVerticalGrid.
            // Using columns Fixed(columnsCount) to adaptively partition multiple columns in wide screens, tablets, and landscape phones.
            // All non-main lists (such as FilterChipRow, RecentlyAdded Row, Author Headers, etc.) use span GridItemSpan(maxLineSpan) to span full width,
            // building a fluid adaptive card grid with a premium appearance.
            LazyVerticalGrid(
                columns = GridCells.Fixed(columnsCount),
                state = gridState,
                modifier = Modifier.fillMaxSize(),
                // Inject dynamically calculated left/right physical safe area paddings into grid container as unified scroll boundary, eliminating hard-coded occlusion risks
                contentPadding = PaddingValues(
                    start = gridStartPadding,
                    end = gridEndPadding,
                    bottom = innerPadding.calculateBottomPadding() + (if (isMiniPlayerVisible) 80.dp else 0.dp) + 16.dp
                )
            ) {
                // FilterChip Placement (Horizontal Filter List)
                // Move the FilterChip row from fixed top to the first item in the scrollable grid.
                // When selectedFilter is null, do not render this item to avoid jumping.
                // Set GridItemSpan(maxLineSpan) to ensure it spans full width.
                if (selectedFilter != null) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        // Use APlayerFilterChip to build horizontal scrolling filter row.
                        LazyRow(
                            modifier = Modifier
                                .fillMaxWidth(),
                            // Align Filter Row (Apply Horizontal Margins)
                            // Filter row needs to manually compensate screenHorizontalPadding to align with the safety line, allowing scrolling to penetrate margins.
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
                    // Render the decoupled independent "Recently Played/Recently Added" section, using GridItemSpan(maxLineSpan) to ensure it spans full width
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
                    // Author section header spans full width.
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Text(
                            text = author.takeIf { it.isNotBlank() } ?: "Unknown",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            // Align Author Header (Apply Horizontal Margins)
                            // Explicitly inject screenHorizontalPadding to ensure author group names align vertically with the page main title.
                            modifier = Modifier.padding(
                                start = screenHorizontalPadding,
                                end = screenHorizontalPadding,
                                top = 24.dp,
                                bottom = 8.dp
                            )
                        )
                    }

                    // M-20 Fix: Use book.id as a stable key to prevent item state dislocation after book list sorting
                    items(books, key = { it.book.id }) { book ->
                        // Grid Layout Mode (Extreme Simplicity Style)
                        // When columnsCount > 1 (landscape or tablet mode), inject moderate padding inside without card background or corners, keeping the clean and minimal aesthetic.
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
                            // Small Thumbnail Selection (thumbnail Preferred)
                            // Main page common list uses small cover size, delegated to CoverImageSourceSelector.small to choose "thumbnail preferred, original fallback" path, avoiding custom hand-written Elvis rules.
                            coverPath = CoverImageSourceSelector.small(
                                thumbnailPath = book.book.thumbnailPath,
                                coverPath = book.book.coverPath
                            ),
                            coverLastUpdated = book.book.lastScannedAt, // Bridge scan/self-healing milliseconds timestamp in Room layer, using declarative design to force synchronous image refreshing
                            progressPercent = book.progressPercent,
                            onClick = { onNavigateToDetail(book.book.id) },
                            // Long-press book item records current book state to activeBookForMenu to invoke action Dialog menu
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

    // Introduce independently encapsulated long-press operation dialogs to keep the home page UI layout clean and clear
    AudiobookActionDialogs(
        bookWithProgress = activeBookForMenu,
        hazeState = homeHazeState,
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
        // Use CompositionLocalProvider to inject PortraitPhone window preset for Previews, ensuring portrait list layout is rendered with high fidelity.
        CompositionLocalProvider(
            LocalWindowClass provides WindowClass.PortraitPhone
        ) {
            HomeScreenContent(
                // Preview simulated data pre-calculated from ViewModel
                selectedFilter = HomeFilter.NotStarted,
                groupedByAuthor = mockBooks.groupBy { it.book.author },
                recentBooks = mockBooks,
                shouldShowRecentBooks = true,
                recentTitleRes = R.string.recently_added_title,
                isMiniPlayerVisible = false,
                recentListState = mockListState,
                // Preview explicitly references setting model default glass effect
                glassEffectMode = AppSettings.DEFAULT_GLASS_EFFECT_MODE
            )
        }
    }
}
