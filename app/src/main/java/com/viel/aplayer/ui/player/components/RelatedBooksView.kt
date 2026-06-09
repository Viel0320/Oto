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

@Composable
fun RelatedBooksView(
    currentBookId: String,
    // Heuristic recommendations parameter (To pass top scored recommended items)
    heuristicBooks: List<PlayerRelatedBook>,
    authorSections: List<PlayerRelatedSection>,
    narratorSections: List<PlayerRelatedSection>,
    recentBooks: List<PlayerRelatedBook>,
    onBookClick: (PlayerRelatedBook) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 24.dp)
    ) {
        //
        // Heuristic recommendations header (To render top scored items row if heuristic list is not empty)
        // Uses composite prefix keys like "h:${book.book.id}" to guarantee layout uniqueness across sections.
        if (heuristicBooks.isNotEmpty()) {
            item {
                // Related Header Resources (Resolve recommendation headers through localized resources)
                // Resource-backed labels let each playback tab locale translate static headings while creator names stay formatted at the UI boundary.
                RelatedSectionHeader(stringResource(R.string.player_related_recommended))
            }
            items(heuristicBooks, key = { "h:${it.id}" }) { book ->
                RelatedAudiobookItem(book, onBookClick)
            }
        }

        authorSections.forEach { section ->
            if (section.books.isNotEmpty()) {
                item {
                    RelatedSectionHeader(stringResource(R.string.player_related_more_by_author, section.name))
                }
                // M-20 Fix — Configure list key (To assign unique key to prevent index recycling glitches)
                items(section.books, key = { it.id }) { book ->
                    RelatedAudiobookItem(book, onBookClick)
                }
            }
        }

        narratorSections.forEach { section ->
            if (section.books.isNotEmpty()) {
                item {
                    RelatedSectionHeader(stringResource(R.string.player_related_more_by_narrator, section.name))
                }
                // M-20 Fix — Configure narrator list key (To assign compound keys to avoid key duplicate errors)
                items(section.books, key = { "n:${it.id}" }) { book ->
                    RelatedAudiobookItem(book, onBookClick)
                }
            }
        }

        if (recentBooks.isNotEmpty()) {
            item {
                RelatedSectionHeader(stringResource(R.string.player_related_recently_added))
            }
            // M-20 Fix — Configure recent list key (To assign prefix keys to prevent section key collisions)
            items(recentBooks, key = { "r:${it.id}" }) { book ->
                RelatedAudiobookItem(book, onBookClick)
            }
        }
    }
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

@Composable
private fun RelatedAudiobookItem(
    book: PlayerRelatedBook,
    onBookClick: (PlayerRelatedBook) -> Unit
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
        onPlayClick = { onBookClick(book) }
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
                onBookClick = {}
            )
        }
    }
}
