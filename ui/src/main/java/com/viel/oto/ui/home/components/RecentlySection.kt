package com.viel.oto.ui.home.components

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
import com.viel.oto.shared.R
import com.viel.oto.application.library.home.HomeBookItem
import com.viel.oto.shared.model.GlassEffectMode
import com.viel.oto.ui.common.CoverImageSourceSelector
import com.viel.oto.ui.motion.SharedElementKeys

/**
 * Renders a horizontally scrolling Home section for recently played or recently added books.
 *
 * The section owns only carousel layout, recent-row identity, and card callbacks so HomeScreen does not
 * need to carry LazyRow viewport state or cover-source motion details.
 *
 * @param recentTitle Title text for the horizontal block, such as recently added or recently played.
 * @param recentBooks Audiobook projections shown in this recent row.
 * @param activeDetailBookId Currently visible Detail book ID, used to hide the matching source cover during shared-element handoff.
 * @param glassEffectMode Global frosted glass visual effect mode.
 * @param screenHorizontalPadding Horizontal alignment inset shared with the Home page.
 * @param onNavigateToDetail Opens the selected audiobook details.
 * @param onBookLongClick Opens the first-level audiobook action menu.
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
    val recentListState = rememberLazyListState()
    val newBadgeText = stringResource(R.string.common_new_badge)

    val firstBookId = recentBooks.firstOrNull()?.id
    LaunchedEffect(firstBookId) {
        if (firstBookId != null) {
            recentListState.scrollToItem(0)
        }
    }

    Column(
        modifier = modifier.fillMaxWidth()
    ) {
        Text(
            text = recentTitle,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = screenHorizontalPadding, vertical = 16.dp)
        )

        LazyRow(
            state = recentListState,
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = screenHorizontalPadding - 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(recentBooks, key = { book -> recentHomeBookKey(book) }) { book ->
                Cardgroup(
                    bookId = book.id,
                    title = book.title,
                    author = book.author,
                    narrator = book.narrator,
                    progressText = if (book.progressPercent > 0) "${book.progressPercent}%" else newBadgeText,
                    isDetailTargetActive = book.id == activeDetailBookId,
                    coverPath = CoverImageSourceSelector.medium(
                        thumbnailPath = book.thumbnailPath,
                        coverPath = book.coverPath
                    ),
                    coverLastUpdated = book.lastScannedAt,
                    onClick = { onNavigateToDetail(book.id) },
                    onLongClick = { onBookLongClick(book) },
                    glassEffectMode = glassEffectMode,
                    sharedElementKey = SharedElementKeys.home2DetailCover(book.id)
                )
            }
        }
    }
}

/**
 * Scopes recent-card identity to the Home recent section.
 *
 * Recent rows use their own key namespace so the same book can also appear in the main catalog without sharing Lazy
 * item identity, while the book ID remains stable across order changes.
 */
private fun recentHomeBookKey(book: HomeBookItem): String {
    return "home:recent:${book.id}"
}
