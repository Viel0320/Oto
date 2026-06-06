package com.viel.aplayer.ui.home

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
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
import com.viel.aplayer.ui.detail.DetailEntrySource
import com.viel.aplayer.ui.home.components.ListItem
import com.viel.aplayer.ui.home.components.RecentlyAddedSection
import com.viel.aplayer.ui.motion.SharedElementKeys
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource

/**
 * HomeScreenContent Setup (Stateless Home Main Content UI)
 *
 * Stateless home main content rendering component, representing the pure UI rendering layer.
 * Through decoupling and refactoring, HomeScreenContent has achieved complete statelessness, no longer holding any ViewModel or context reference.
 * All interactions and data delivery are performed entirely through pure declarative parameters and Lambda callbacks, greatly improving the unit testability and preview flexibility of the component.
 */
@Composable
fun HomeScreenContent(
    modifier: Modifier = Modifier,
    // Pre-calculated Fields (Dismantled from LibraryUiState)
    // The following are pre-calculated fields dismantled and passed from LibraryUiState, so the Composable does not need to perform remember operations anymore.
    // When selectedFilter is null, it means the combine pipeline of the ViewModel has not yet produced the first final decision. At this time, the FilterChip row is not rendered to avoid jumping animations.
    selectedFilter: HomeFilter? = null,
    groupedByAuthor: Map<String, List<BookWithProgress>> = emptyMap(),
    recentBooks: List<BookWithProgress> = emptyList(),
    /*
     * Active Recent Detail Book Id (Recent-card source visibility selector)
     *
     * Carries only the visible detail target opened from Recent so the horizontal card source
     * exits independently from the main Home list thumbnail for the same book.
     */
    activeRecentDetailBookId: String? = null,
    /*
     * Active List Detail Book Id (Main-list source visibility selector)
     *
     * Carries only the visible detail target opened from the main catalog list so list cover
     * animation never hides or binds the Recent card for the same audiobook.
     */
    activeListDetailBookId: String? = null,
    shouldShowRecentBooks: Boolean = false,
    @StringRes recentTitleRes: Int = 0,
    glassEffectMode: GlassEffectMode,
    // Home Content Haze State (Register the bookshelf surface for the overlay app bar)
    // The scrolling bookshelf still registers a local source so page content remains available for component-level blur and preview fallback.
    homeHazeState: HazeState,
    // Home Top Bar Height (Reserve space for the NavHost-owned overlay header)
    // HomeContent no longer renders the header, but it still needs the measured chrome height to keep the first grid item from sitting under it.
    homeTopBarHeightPx: Int = 0,
    // Home Top Bar Scroll Request (React to the NavHost-owned title double-tap gesture)
    // Each increment represents one scroll-to-top command emitted by HomeAppBar outside this content tree.
    homeTopBarScrollToTopRequest: Int = 0,
    isMiniPlayerVisible: Boolean = false,
    recentListState: LazyListState,
    onFilterSelected: (HomeFilter) -> Unit = {},
    onNavigateToDetail: (String, DetailEntrySource) -> Unit = { _, _ -> },
    onNavigateToPlayer: () -> Unit = {},
    onLoadBook: (String) -> Unit = {},
    onLibraryRootSelected: (Uri) -> Unit = {},
    // Book Actions Request Event (Report long-press user intent to the Home dialog host)
    // The content layer no longer owns dialog visibility or dialog rendering, keeping bookshelf layout independent from concrete modal implementations.
    onBookActionsRequested: (BookWithProgress) -> Unit = {},
) {
    // Create dedicated HazeState for homepage chips to fetch clean background colors without self-sampling nested loops.
    val chipHazeState = remember { HazeState() }
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

    // Exclude Keyboard Insets (Decouple Keyboard from Safe Area)
    // Use WindowInsets.safeDrawing to dynamically obtain current status bar, navigation bar, and physical cutout, and explicitly call exclude(WindowInsets.ime).
    // This cuts off physical impact of the software keyboard (IME) on home page's perceived safe area padding, preventing unnecessary recombinations of HomeScreen due to changes in safe area heights.
    val safeDrawingPadding = WindowInsets.safeDrawing.exclude(WindowInsets.ime).asPaddingValues()
    val layoutDirection = androidx.compose.ui.platform.LocalLayoutDirection.current
    val density = LocalDensity.current
    // Edge-to-Edge Grid Padding (Exquisite Edge Visual Style)
    // Refactor grid padding strategy: gridStart/EndPadding here only retains physical safe areas (e.g., cutout, side navigation bar).
    // Strip 16dp/24dp business margins from Grid container layer, sinking it into specific titles and list items.
    // This ensures that ListItem ripple effects and scrolling scrollbars can cling to physical screen edges, achieving the ultimate premium edge-to-edge feel.
    val gridStartPadding = safeDrawingPadding.calculateStartPadding(layoutDirection)
    val gridEndPadding = safeDrawingPadding.calculateEndPadding(layoutDirection)

    // Migrate the scroll state remember from LazyListState to adaptive grid GridState to complete the base upgrade.
    val gridState = rememberLazyGridState()
    val isBlur = glassEffectMode == GlassEffectMode.Haze
    // Top Bar Height Resolution (Use the NavHost-owned header measurement)
    // A fallback keeps the first frame and previews stable before the external HomeAppBar reports its measured height.
    val measuredTopBarHeight = if (homeTopBarHeightPx > 0) {
        with(density) { homeTopBarHeightPx.toDp() }
    } else {
        safeDrawingPadding.calculateTopPadding() + 64.dp
    }
    LaunchedEffect(homeTopBarScrollToTopRequest) {
        if (homeTopBarScrollToTopRequest > 0) {
            // Home Grid Scroll Reset (Consume the external top bar gesture event)
            // The Home grid keeps ownership of its scroll state while accepting an incrementing command from the NavHost-hosted header.
            gridState.scrollToItem(0)
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        if (isBlur) {
            // Dedicated Background Layer for Chip Haze (Sample a clean background color using chipHazeState to decouple filter chips from self-sampling deadlocks) Apply hazeSource to sibling background.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .hazeSource(chipHazeState)
                    .background(MaterialTheme.colorScheme.background)
            )
        }

        Scaffold(
            modifier = Modifier
                .fillMaxSize(),
            // Exclude Keyboard Insets (Prevent Layout Re-measurement)
            // Explicitly exclude the software keyboard (IME) from default contentWindowInsets.
            // This cuts off layout re-measurement and unnecessary home page recombinations caused by innerPadding changes from software keyboard popups.
            contentWindowInsets = WindowInsets.safeDrawing.exclude(WindowInsets.ime),
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
        // Overlay Top Bar Padding (Use measured top bar height because top bar is no longer a Scaffold slot) Lets scroll content move behind the glass bar after the initial reserved space scrolls away.
        Column(
            modifier = Modifier.fillMaxSize()
        ) {
            // LazyVerticalGrid Setup (Adaptive Card Grid)
            // Upgrade and refactor the single-column LazyColumn to a new and powerful LazyVerticalGrid.
            // Using columns Fixed(columnsCount) to adaptively partition multiple columns in wide screens, tablets, and landscape phones.
            // All non-main lists (such as FilterChipRow, RecentlyAdded Row, Author Headers, etc.) use span GridItemSpan(maxLineSpan) to span full width,
            // building a fluid adaptive card grid with a premium appearance.
            // Home Grid Haze Source (Register the real scrolling bookshelf content as the backdrop source) Allows the top bar to blur books and section content after they scroll underneath it.

            LazyVerticalGrid(
                columns = GridCells.Fixed(columnsCount),
                state = gridState,
                modifier = Modifier
                    .fillMaxSize()
                    .then(
                        if (isBlur) {
                            Modifier.hazeSource(homeHazeState)
                        } else {
                            Modifier
                        }
                    ),
                // Overlay Top Bar Padding (Reserve measured overlay top bar height in grid content padding) Keeps first content aligned while preserving under-bar scrolling during list movement.
                contentPadding = PaddingValues(
                    start = gridStartPadding,
                    end = gridEndPadding,
                    top = measuredTopBarHeight + 12.dp,
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
                                .fillMaxWidth()
                                // Chip Bottom Spacing (Add extra bottom padding to separate filter chips from underneath lists) Ensure layout breathing distance.
                                .padding(bottom = 8.dp),
                            // Align Filter Row (Apply Horizontal Margins)
                            // Filter row needs to manually compensate screenHorizontalPadding to align with the safety line, allowing scrolling to penetrate margins.
                            contentPadding = PaddingValues(
                                start = screenHorizontalPadding,
                                end = screenHorizontalPadding
                            ),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(filters, key = { (filter, _) -> filter.name }) { (filter, label) ->
                                // Pass Glass Mode to Filter Chips (Deliver active glass effect mode and hazeState properties to filter chips) Support Haze rendering for homepage categories.
                                APlayerFilterChip(
                                    selected = filter == selectedFilter,
                                    onClick = { onFilterSelected(filter) },
                                    label = label,
                                    glassEffectMode = glassEffectMode,
                                    hazeState = chipHazeState
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
                            activeDetailBookId = activeRecentDetailBookId,
                            recentListState = recentListState,
                            glassEffectMode = glassEffectMode,
                            screenHorizontalPadding = screenHorizontalPadding,
                            /*
                             * Recent Detail Entry Routing (Recent motion source tagging)
                             *
                             * Marks this click as a Recent-origin detail entry so only the horizontal
                             * recent-card shared-element channel becomes active.
                             */
                            onNavigateToDetail = { bookId ->
                                onNavigateToDetail(bookId, DetailEntrySource.HomeRecent)
                            },
                            onBookLongClick = onBookActionsRequested
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
                            bookId = book.book.id,
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
                            /*
                             * List Detail Source Activity (Main-list source visibility trigger)
                             *
                             * Activates only when this book was opened from the main Home list,
                             * keeping matching Recent cards visible and out of this transition.
                             */
                            isDetailTargetActive = book.book.id == activeListDetailBookId,
                            /*
                             * List Detail Shared Element Key (Main-list channel binding)
                             *
                             * Uses the list-specific key so Home list artwork cannot pair with
                             * Recent cards that may show the same book ID.
                             */
                            sharedElementKey = SharedElementKeys.homeList2DetailCover(book.book.id),
                            onClick = { onNavigateToDetail(book.book.id, DetailEntrySource.HomeList) },
                            // Book Actions Request (Forward long-press intent to the parent Home dialog host)
                            // HomeScreenContent reports the selected audiobook without deciding which dialog should render.
                            onLongClick = { onBookActionsRequested(book) },
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

    }
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
        // Preview Shared Haze Source (Mirror the isolated Home fallback path)
        // The production top bar is hosted by APlayerNavHost, while preview keeps a local grid source for content-level blur.
        val previewHomeHazeState = remember { HazeState() }
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
                glassEffectMode = AppSettings.DEFAULT_GLASS_EFFECT_MODE,
                // Preview Home Content Haze State (Match the local app bar sampling contract)
                // Production receives top bar measurements from APlayerNavHost, while preview only needs the local grid sampling source.
                homeHazeState = previewHomeHazeState
            )
        }
    }
}
