package com.viel.oto.ui.home

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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectableGroup
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.viel.oto.application.library.LibraryBookSourceType
import com.viel.oto.application.library.LibraryBookStatus
import com.viel.oto.application.library.LibraryReadStatus
import com.viel.oto.application.library.home.HomeBookItem
import com.viel.oto.shared.R
import com.viel.oto.shared.model.HomeFilter
import com.viel.oto.shared.model.HomeViewStyle
import com.viel.oto.ui.common.CoverImageSourceSelector
import com.viel.oto.ui.common.OtoFilterChip
import com.viel.oto.ui.common.layout.AppWindowSizeClass
import com.viel.oto.ui.common.layout.LocalAppWindowSizeClass
import com.viel.oto.ui.common.theme.LocalIsBlur
import com.viel.oto.ui.common.theme.OtoTheme
import com.viel.oto.ui.detail.DetailEntrySource
import com.viel.oto.ui.home.components.Cardgroup
import com.viel.oto.ui.home.components.ListItem
import com.viel.oto.ui.home.components.RecentlyAddedSection
import com.viel.oto.ui.motion.SharedElementKeys
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource

/**
 * Stateless Home Main Content UI.
 *
 * Stateless home main content rendering component, representing the pure UI rendering layer.
 * Through decoupling and refactoring, HomeContent has achieved complete statelessness, no longer holding any ViewModel or context reference.
 * All interactions and data delivery are performed entirely through pure declarative parameters and Lambda callbacks, greatly improving the unit testability and preview flexibility of the component.
 */
@Composable
fun HomeContent(
    modifier: Modifier = Modifier,
    selectedFilter: HomeFilter? = null,
    groupedAudiobooks: Map<String, List<HomeBookItem>> = emptyMap(),
    recentBooks: List<HomeBookItem> = emptyList(),
    activeRecentDetailBookId: String? = null,
    activeListDetailBookId: String? = null,
    shouldShowRecentBooks: Boolean = false,
    @StringRes recentTitleRes: Int = 0,
    homeHazeState: HazeState,
    homeViewStyle: HomeViewStyle = HomeViewStyle.List,
    homeTopBarScrollToTopRequest: Int = 0,
    isMiniPlayerVisible: Boolean = false,
    onFilterSelected: (HomeFilter) -> Unit = {},
    onNavigateToDetail: (String, DetailEntrySource) -> Unit = { _, _ -> },
    onNavigateToPlayer: () -> Unit = {},
    onLoadBook: (String) -> Unit = {},
    shouldShowAddLibraryFab: Boolean = false,
    onAddLibraryRequested: () -> Unit = {},
    onBookActionsRequested: (HomeBookItem) -> Unit = {},
) {
    val chipHazeState = remember { HazeState() }
    val filters = listOf(
        HomeFilter.NotStarted to stringResource(R.string.filter_not_started),
        HomeFilter.InProgress to stringResource(R.string.filter_in_progress),
        HomeFilter.Finished to stringResource(R.string.filter_finished)
    )
    val recentTitle = if (recentTitleRes != 0) stringResource(recentTitleRes) else ""
    val newBadgeText = stringResource(R.string.common_new_badge)
    val windowClass = LocalAppWindowSizeClass.current
    val columnsCount = windowClass.columnsCount
    val screenHorizontalPadding = windowClass.screenHorizontalPadding
    val listgroupColumnsCount = columnsCount

    val safeDrawingPadding = WindowInsets.safeDrawing.exclude(WindowInsets.ime).asPaddingValues()
    val layoutDirection = androidx.compose.ui.platform.LocalLayoutDirection.current

    val gridStartPadding = safeDrawingPadding.calculateStartPadding(layoutDirection)
    val gridEndPadding = safeDrawingPadding.calculateEndPadding(layoutDirection)

    val gridState = rememberLazyGridState()
    val isBlur = LocalIsBlur.current

    val topBarHeight = safeDrawingPadding.calculateTopPadding() + 64.dp

    LaunchedEffect(homeTopBarScrollToTopRequest) {
        if (homeTopBarScrollToTopRequest > 0) {
            gridState.scrollToItem(0)
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .then(
                if (!isBlur) {
                    Modifier.background(MaterialTheme.colorScheme.background)
                } else {
                    Modifier
                }
            )
    ) {
        if (isBlur) {
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
            containerColor = Color.Transparent,
            contentWindowInsets = WindowInsets.safeDrawing.exclude(WindowInsets.ime),
            floatingActionButton = {
                if (shouldShowAddLibraryFab) {
                    FloatingActionButton(
                        onClick = onAddLibraryRequested,
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
                            contentDescription = stringResource(R.string.settings_add_library_title),
                            modifier = Modifier.size(32.dp)
                        )
                    }
                }
            }
        ) { innerPadding ->
        Column(
            modifier = Modifier.fillMaxSize()
        ) {

            LazyVerticalGrid(
                columns = GridCells.Fixed(listgroupColumnsCount),
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
                contentPadding = PaddingValues(
                    start = gridStartPadding,
                    end = gridEndPadding,
                    top = topBarHeight + 12.dp,
                    bottom = innerPadding.calculateBottomPadding() + (if (isMiniPlayerVisible) 80.dp else 0.dp) + 16.dp
                )
            ) {
                if (selectedFilter != null) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        LazyRow(
                            modifier = Modifier
                                .fillMaxWidth()
                                .selectableGroup()
                                .padding(bottom = 8.dp),
                            contentPadding = PaddingValues(horizontal = screenHorizontalPadding),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(filters, key = { (filter, _) -> filter.name }) { (filter, label) ->
                                OtoFilterChip(
                                    selected = filter == selectedFilter,
                                    onClick = { onFilterSelected(filter) },
                                    label = label,
                                    hazeState = chipHazeState
                                )
                            }
                        }
                    }
                }

                if (shouldShowRecentBooks) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        RecentlyAddedSection(
                            recentTitle = recentTitle,
                            recentBooks = recentBooks,
                            activeDetailBookId = activeRecentDetailBookId,
                            screenHorizontalPadding = screenHorizontalPadding,
                            onNavigateToDetail = { bookId ->
                                onNavigateToDetail(bookId, DetailEntrySource.HomeRecent)
                            },
                            onBookLongClick = onBookActionsRequested
                        )
                    }
                }

                groupedAudiobooks.forEach { (groupTitle, books) ->
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Text(
                            text = groupTitle,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(
                                start = screenHorizontalPadding,
                                end = screenHorizontalPadding,
                                top = 24.dp,
                                bottom = 8.dp
                            )
                        )
                    }

                    when (homeViewStyle) {
                        HomeViewStyle.List -> {
                            items(
                                books,
                                key = { book -> homeGroupedBookKey("list", groupTitle, book) }
                            ) { book ->
                                val itemModifier = if (listgroupColumnsCount > 1) {
                                    Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                } else {
                                    Modifier
                                }
                                ListItem(
                                    bookId = book.id,
                                    title = book.title,
                                    author = book.author,
                                    narrator = book.narrator,
                                    duration = book.totalDurationMs,
                                    coverPath = CoverImageSourceSelector.small(
                                        thumbnailPath = book.thumbnailPath,
                                        coverPath = book.coverPath
                                    ),
                                    coverLastUpdated = book.lastScannedAt,
                                    progressPercent = book.progressPercent,
                                    isDetailTargetActive = book.id == activeListDetailBookId,
                                    sharedElementKey = SharedElementKeys.homeList2DetailCover(book.id),
                                    onClick = { onNavigateToDetail(book.id, DetailEntrySource.HomeList) },
                                    onPlayClick = {
                                        onLoadBook(book.id)
                                        onNavigateToPlayer()
                                    },
                                    onLongClick = { onBookActionsRequested(book) },
                                    modifier = itemModifier
                                )
                            }
                        }
                        HomeViewStyle.Grid -> {
                            item(span = { GridItemSpan(maxLineSpan) }) {
                                LazyRow(
                                    modifier = Modifier.fillMaxWidth(),
                                    contentPadding = PaddingValues(horizontal = screenHorizontalPadding - 8.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    items(
                                        books,
                                        key = { book -> homeGroupedBookKey("card-row", groupTitle, book) }
                                    ) { book ->
                                        Cardgroup(
                                            bookId = book.id,
                                            title = book.title,
                                            author = book.author,
                                            narrator = book.narrator,
                                            progressText = if (book.progressPercent > 0) "${book.progressPercent}%" else newBadgeText,
                                            coverPath = CoverImageSourceSelector.medium(
                                                thumbnailPath = book.thumbnailPath,
                                                coverPath = book.coverPath
                                            ),
                                            coverLastUpdated = book.lastScannedAt,
                                            isDetailTargetActive = book.id == activeListDetailBookId,
                                            onClick = { onNavigateToDetail(book.id, DetailEntrySource.HomeList) },
                                            onLongClick = { onBookActionsRequested(book) },
                                            sharedElementKey = SharedElementKeys.homeList2DetailCover(book.id)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    }
}

/**
 * Scopes catalog row identity to a Home section.
 *
 * The Home section and book ID form the UI identity for a catalog row, preserving Compose state when items reorder
 * while still preventing the same book from colliding across different Home rendering sections.
 */
private fun homeGroupedBookKey(
    sectionType: String,
    groupTitle: String,
    book: HomeBookItem
): String {
    return "home:$sectionType:$groupTitle:${book.id}"
}

@Preview(showBackground = true, apiLevel = 36)
@Composable
fun HomeNotStartedPreview() {
    val mockBooks = listOf(
        HomeBookItem(
            id = "id1",
            rootId = "preview-root",
            sourceType = LibraryBookSourceType.SINGLE_AUDIO,
            status = LibraryBookStatus.READY,
            title = "In the Megachurch",
            author = "Ryo Asai",
            narrator = "Narrator A",
            description = "",
            year = "",
            series = "",
            totalDurationMs = 44580000L,
            totalFileSize = 0L,
            coverPath = null,
            thumbnailPath = null,
            lastScannedAt = 0L,
            addedAt = System.currentTimeMillis(),
            readStatus = LibraryReadStatus.NOT_STARTED,
            progressPercent = 0,
            lastPlayedAt = 0L,
            isFinished = false,
            isInProgress = false,
            isNotStarted = true
        )
    )

    OtoTheme {
        val previewHomeHazeState = remember { HazeState() }
        CompositionLocalProvider(
            LocalAppWindowSizeClass provides AppWindowSizeClass.PortraitPhone
        ) {
            HomeContent(
                selectedFilter = HomeFilter.NotStarted,
                groupedAudiobooks = mockBooks.groupBy { it.author },
                recentBooks = mockBooks,
                shouldShowRecentBooks = true,
                recentTitleRes = R.string.recently_added_title,
                isMiniPlayerVisible = false,
                homeHazeState = previewHomeHazeState
            )
        }
    }
}
