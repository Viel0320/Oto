package com.viel.aplayer.ui.home.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.viel.aplayer.data.entity.BookWithProgress
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
 * @param recentListState Horizontal scroll LazyListState handler, used to preserve scroll position when grid scrolls vertically.
 * @param glassEffectMode Global frosted glass visual effect mode.
 * @param screenHorizontalPadding Dynamically calculated visual alignment left margin.
 * @param onNavigateToDetail Callback function on card click navigating to audiobook details.
 * @param onBookLongClick Callback function on card long press invoking first-level action menu.
 */
@Composable
fun RecentlyAddedSection(
    recentTitle: String,
    recentBooks: List<BookWithProgress>,
    activeDetailBookId: String?,
    recentListState: LazyListState,
    glassEffectMode: GlassEffectMode,
    screenHorizontalPadding: Dp,
    onNavigateToDetail: (String) -> Unit,
    onBookLongClick: (BookWithProgress) -> Unit,
    modifier: Modifier = Modifier
) {
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
            // M-20 Fix: Use unique book.id as LazyList stable key to prevent card covers from flickering or shifting due to frequent reloads
            items(recentBooks, key = { it.book.id }) { book ->
                RecentlyItem(
                    bookId = book.book.id,
                    title = book.book.title,
                    author = book.book.author,
                    narrator = book.book.narrator,
                    // If the audiobook has a specific reading progress, fill the badge with progress percentage, otherwise fill "NEW" indicating newly imported
                    progressText = if (book.progressPercent > 0) "${book.progressPercent}%" else "NEW",
                    /*
                     * Recent Item Visibility Handoff (Selected source exit trigger)
                     *
                     * Passes whether this card is the active Detail target so the card cover can
                     * leave through its own AnimatedVisibility and form a valid source-target pair.
                     */
                    isDetailTargetActive = book.book.id == activeDetailBookId,
                    // Thumbnail Medium Caching (Optimize Memory and Cache Reuse)
                    // Recently played cards use medium cover specification, preferring local thumbnails.
                    // This allows 360px requests to hit thumbnail cache directly, avoiding decoding large covers.
                    coverPath = CoverImageSourceSelector.medium(
                        thumbnailPath = book.book.thumbnailPath,
                        coverPath = book.book.coverPath
                    ),
                    // Pass last updated timestamp to Coil, allowing it to force redraw without disk I/O when cache is reconstructed.
                    coverLastUpdated = book.book.lastScannedAt,
                    onClick = { onNavigateToDetail(book.book.id) },
                    onLongClick = { onBookLongClick(book) },
                    glassEffectMode = glassEffectMode,
                    // Pass ARGB main color tone physically extracted and persisted in database to card, rendering ambiance background
                    coverColorArgb = book.book.backgroundColorArgb,
                    /*
                     * Recent To Detail Shared Element Key (Bind source artwork endpoint)
                     *
                     * Uses the selected book ID to align this recent card cover with the detail
                     * page cover endpoint under the app-level SharedTransitionLayout.
                     */
                    sharedElementKey = SharedElementKeys.home2DetailCover(book.book.id)
                )
            }
        }
    }
}
