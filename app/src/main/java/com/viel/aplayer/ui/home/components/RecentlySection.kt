package com.viel.aplayer.ui.home.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.viel.aplayer.R
import com.viel.aplayer.application.library.home.HomeBookItem
import com.viel.aplayer.data.store.GlassEffectMode
import com.viel.aplayer.ui.common.CoverImageSourceSelector
import com.viel.aplayer.ui.motion.SharedElementKeys

/**
 * RecentlyAddedSection Setup (Recently Played/Added Horizontal List)
 *
 * Horizontally scrolling list decoupled component for home page "Recently Played/Recently Added" audiobooks.
 * Through physical separation, this section is completely independent of the large HomeScreen class,
 * practicing the design philosophy of UI component decoupling, avoiding bloated components, and making cards inside "recent" section highly cohesive.
 *
 * @param recentTitle Main title text of the horizontal block (e.g., Recently Added, Recently Played).
 * @param recentBooks Audiobook data collection of recent items.
 * @param activeDetailBookId Currently visible Detail book ID, used to hide the matching source cover during shared-element handoff.
 * @param glassEffectMode Global frosted glass visual effect mode.
 * @param screenHorizontalPadding Dynamically calculated visual alignment left margin.
 * @param onNavigateToDetail Callback function on card click navigating to audiobook details.
 * @param onBookLongClick Callback function on card long press invoking first-level action menu.
 */
@Composable
fun RecentlyAddedSection(
    recentTitle: String,
    recentBooks: List<HomeBookItem>,
    activeDetailBookId: String?,
    glassEffectMode: GlassEffectMode,
    screenHorizontalPadding: Dp,
    onNavigateToDetail: (String) -> Unit,
    onBookLongClick: (HomeBookItem) -> Unit,
    modifier: Modifier = Modifier
) {
    // Recent CardGroup Row Scroll Ownership (Keep horizontal scroll state beside the LazyRow that consumes it)
    // The Home container no longer passes a LazyListState through route-level parameters, so this component owns the Cardgroup row viewport and preserves it while the parent grid scrolls vertically.
    val recentListState = rememberLazyListState()
    // Localized Recent Badge Copy (Render newly imported cards with locale-aware app text)
    // Percent progress remains numeric data, while the empty-progress NEW badge is authored UI copy.
    val newBadgeText = stringResource(R.string.common_new_badge)

    // Recent Dataset Head Tracking (Reset the row whenever the leading card changes)
    // The recent row now treats a changed first book as a fresh ordering event and always returns to item 0, regardless of the user's previous horizontal offset.
    val firstBookId = recentBooks.firstOrNull()?.id
    LaunchedEffect(firstBookId) {
        // Recent Row Head Reset (Show the newest leading card after recent ordering changes)
        // Running after layout avoids LazyRow anchoring leaving the updated first card off-screen or partially displaced.
        if (firstBookId != null) {
            recentListState.scrollToItem(0)
        }
    }

    Column(
        modifier = modifier.fillMaxWidth()
    ) {
        // Draw main title of "Recently Played/Added", using screenHorizontalPadding to ensure alignment with visual margins
        Text(
            text = recentTitle,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = screenHorizontalPadding, vertical = 16.dp)
        )

        // Align Horizontal List (Apply Edge Compensation Padding)
        // Horizontal scroll view horizontal contentPadding set to (visual padding - 8.dp card padding) to compensate card horizontal margins, aligning cover borders perfectly with the title text above.
        LazyRow(
            state = recentListState,
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = screenHorizontalPadding - 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Recent Row Composite Key (Scope recent-card identity to the recent carousel)
            // The recent section owns a separate key namespace, while book ID preserves item identity during reordering.
            items(recentBooks, key = { book -> recentHomeBookKey(book) }) { book ->
                Cardgroup(
                    bookId = book.id,
                    title = book.title,
                    author = book.author,
                    narrator = book.narrator,
                    // Recent Badge Resolution (Show runtime progress percentages or the localized new-item marker)
                    // The percentage comes from audiobook state, while the new-item marker is app-authored UI copy.
                    progressText = if (book.progressPercent > 0) "${book.progressPercent}%" else newBadgeText,
                    /*
                     * Recent Item Visibility Handoff (Selected source exit trigger)
                     *
                     * Passes whether this card is the active Detail target so the card cover can
                     * leave through its own AnimatedVisibility and form a valid source-target pair.
                     */
                    isDetailTargetActive = book.id == activeDetailBookId,
                    // Thumbnail Medium Caching (Optimize Memory and Cache Reuse)
                    // Recently played cards use medium cover specification, preferring local thumbnails.
                    // This allows 360px requests to hit thumbnail cache directly, avoiding decoding large covers.
                    coverPath = CoverImageSourceSelector.medium(
                        thumbnailPath = book.thumbnailPath,
                        coverPath = book.coverPath
                    ),
                    // Pass last updated timestamp to Coil, allowing it to force redraw without disk I/O when cache is reconstructed.
                    coverLastUpdated = book.lastScannedAt,
                    onClick = { onNavigateToDetail(book.id) },
                    onLongClick = { onBookLongClick(book) },
                    glassEffectMode = glassEffectMode,
                    // Deprecated: coverColorArgb is no longer passed from database
                    /*
                     * Recent To Detail Shared Element Key (Bind source artwork endpoint)
                     *
                     * Uses the selected book ID to align this recent card cover with the detail
                     * page cover endpoint under the app-level SharedTransitionLayout.
                     */
                    sharedElementKey = SharedElementKeys.home2DetailCover(book.id)
                )
            }
        }
    }
}

/**
 * Recent Home Book Key (Scopes recent-card identity to the Home recent section)
 *
 * Recent rows use their own key namespace so the same book can also appear in the main catalog without sharing Lazy
 * item identity, while the book ID remains stable across order changes.
 */
private fun recentHomeBookKey(book: HomeBookItem): String {
    return "home:recent:${book.id}"
}
