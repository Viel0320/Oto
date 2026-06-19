package com.viel.aplayer.ui.player.components

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.viel.aplayer.R
import com.viel.aplayer.application.library.player.PlayerRelatedBook
import com.viel.aplayer.application.library.player.PlayerRelatedSection
import com.viel.aplayer.ui.common.CoverImageSourceSelector
import com.viel.aplayer.ui.common.theme.APlayerTheme
import com.viel.aplayer.ui.home.components.ListItem

/**
 * Related Books View (Player recommendation panel)
 *
 * Keeps row navigation and explicit playback as separate callbacks so tapping a recommendation
 * opens its Detail page while the play affordance remains an immediate playback command.
 */
@Composable
fun RelatedBooksView(
    currentBookId: String,
    // Heuristic recommendations parameter (To pass top scored recommended items)
    heuristicBooks: List<PlayerRelatedBook>,
    authorSections: List<PlayerRelatedSection>,
    narratorSections: List<PlayerRelatedSection>,
    recentBooks: List<PlayerRelatedBook>,
    onBookClick: (PlayerRelatedBook) -> Unit,
    onPlayClick: (PlayerRelatedBook) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 24.dp)
    ) {
        //
        // Heuristic recommendations header (To render top scored items row if heuristic list is not empty)
        // Uses section-aware keys so repeated book IDs from different recommendation groups remain valid LazyColumn items.
        if (heuristicBooks.isNotEmpty()) {
            item(key = "recommended:header") {
                // Related Header Resources (Resolve recommendation headers through localized resources)
                // Resource-backed labels let each playback tab locale translate static headings while creator names stay formatted at the UI boundary.
                RelatedSectionHeader(stringResource(R.string.player_related_recommended))
            }
            items(heuristicBooks, key = { "recommended:${it.id}" }) { book ->
                RelatedAudiobookItem(book, onBookClick, onPlayClick)
            }
        }

        authorSections.forEachIndexed { sectionIndex, section ->
            if (section.books.isNotEmpty()) {
                item(key = relatedSectionHeaderKey("author", sectionIndex, section.name)) {
                    RelatedSectionHeader(stringResource(R.string.player_related_more_by_author, section.name))
                }
                // M-20 Fix — Configure list key (To assign unique key to prevent index recycling glitches)
                items(section.books, key = { relatedSectionBookKey("author", sectionIndex, section.name, it) }) { book ->
                    RelatedAudiobookItem(book, onBookClick, onPlayClick)
                }
            }
        }

        narratorSections.forEachIndexed { sectionIndex, section ->
            if (section.books.isNotEmpty()) {
                item(key = relatedSectionHeaderKey("narrator", sectionIndex, section.name)) {
                    RelatedSectionHeader(stringResource(R.string.player_related_more_by_narrator, section.name))
                }
                // M-20 Fix — Configure narrator list key (To assign compound keys to avoid key duplicate errors)
                items(section.books, key = { relatedSectionBookKey("narrator", sectionIndex, section.name, it) }) { book ->
                    RelatedAudiobookItem(book, onBookClick, onPlayClick)
                }
            }
        }

        if (recentBooks.isNotEmpty()) {
            item(key = "recent:header") {
                RelatedSectionHeader(stringResource(R.string.player_related_recently_added))
            }
            // M-20 Fix — Configure recent list key (To assign prefix keys to prevent section key collisions)
            items(recentBooks, key = { "recent:${it.id}" }) { book ->
                RelatedAudiobookItem(book, onBookClick, onPlayClick)
            }
        }
    }
}

/**
 * Related Section Header Key (Preserves LazyColumn identity across repeated creator groups)
 *
 * Related recommendations may include the same creator label more than once after metadata splitting, so the section
 * index is part of the key instead of relying on the creator name alone.
 */
private fun relatedSectionHeaderKey(sectionType: String, sectionIndex: Int, sectionName: String): String {
    return "related:$sectionType:$sectionIndex:$sectionName:header"
}

/**
 * Related Section Book Key (Allows one book to appear under multiple recommendation groups)
 *
 * Compose requires item keys to be globally unique inside one LazyColumn. Including the section identity keeps repeated
 * book IDs valid when a title matches several author or narrator sections.
 */
private fun relatedSectionBookKey(
    sectionType: String,
    sectionIndex: Int,
    sectionName: String,
    book: PlayerRelatedBook
): String {
    return "related:$sectionType:$sectionIndex:$sectionName:${book.id}"
}

@Composable
private fun RelatedSectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        // Related Header Overflow Clamp (Keep long creator names from expanding the player panel)
        // The bounded width, single-line limit, and ellipsis preserve section rhythm under narrow widths and large font scales.
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, top = 24.dp, end = 16.dp, bottom = 8.dp)
    )
}

/**
 * Related Audiobook Item (Recommendation row action bridge)
 *
 * Routes the container tap to Detail navigation and the embedded play button to playback, matching
 * Home list semantics without letting this shared row know either destination implementation.
 */
@Composable
private fun RelatedAudiobookItem(
    book: PlayerRelatedBook,
    onBookClick: (PlayerRelatedBook) -> Unit,
    onPlayClick: (PlayerRelatedBook) -> Unit
) {
    ListItem(
        title = book.title,
        author = book.author,
        narrator = book.narrator,
        duration = book.totalDurationMs,
        // Small image loading strategy (To retrieve small cover thumbnail images)
        // Matches thumbnail caches with home lists and compact players.
        coverPath = CoverImageSourceSelector.small(
            thumbnailPath = book.thumbnailPath,
            coverPath = book.coverPath
        ),
        // Refresh modification timestamp (To forward lastScannedAt parameters)
        // Forces views reload when cover images are regenerated.
        coverLastUpdated = book.coverLastUpdated,
        progressPercent = book.progressPercent,
        onClick = { onBookClick(book) },
        onPlayClick = { onPlayClick(book) }
    )
}

@Preview(showBackground = true, backgroundColor = 0xFF101418)
@Composable
fun RelatedBooksViewPreview() {
    val mockBook = PlayerRelatedBook(
        id = "id1",
        title = "Sample Audiobook",
        author = "Author Name",
        narrator = "Narrator Name",
        totalDurationMs = 3600000L,
        thumbnailPath = null,
        coverPath = null,
        coverLastUpdated = System.currentTimeMillis(),
        progressPercent = 0
    )
    val mockList = listOf(mockBook, mockBook.copy(id = "id2", title = "Another Book"))

    APlayerTheme(darkTheme = true) {
        Surface(color = MaterialTheme.colorScheme.background) {
            RelatedBooksView(
                currentBookId = "id0",
                // Supply heuristic recommendations mock data in preview
                heuristicBooks = mockList,
                authorSections = listOf(PlayerRelatedSection("Author Name", mockList)),
                narratorSections = listOf(PlayerRelatedSection("Narrator Name", mockList)),
                recentBooks = mockList,
                onBookClick = {},
                onPlayClick = {}
            )
        }
    }
}
