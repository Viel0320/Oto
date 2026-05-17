package com.viel.aplayer.ui.components

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.viel.aplayer.data.BookEntity
import com.viel.aplayer.data.BookWithProgress
import com.viel.aplayer.ui.state.RelatedSection
import com.viel.aplayer.ui.theme.APlayerTheme

@Composable
fun RelatedBooksView(
    currentBookId: String,
    authorSections: List<RelatedSection>,
    narratorSections: List<RelatedSection>,
    recentBooks: List<BookWithProgress>,
    onBookClick: (BookWithProgress) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 24.dp)
    ) {
        authorSections.forEach { section ->
            if (section.books.isNotEmpty()) {
                item {
                    RelatedSectionHeader("More by ${section.name}")
                }
                items(section.books) { book ->
                    RelatedAudiobookItem(book, onBookClick)
                }
            }
        }

        narratorSections.forEach { section ->
            if (section.books.isNotEmpty()) {
                item {
                    RelatedSectionHeader("More by ${section.name}")
                }
                items(section.books) { book ->
                    RelatedAudiobookItem(book, onBookClick)
                }
            }
        }

        if (recentBooks.isNotEmpty()) {
            item {
                RelatedSectionHeader("Recently Added")
            }
            items(recentBooks) { book ->
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
        modifier = Modifier.padding(start = 16.dp, top = 24.dp, end = 16.dp, bottom = 8.dp)
    )
}

@Composable
private fun RelatedAudiobookItem(
    book: BookWithProgress,
    onBookClick: (BookWithProgress) -> Unit
) {
    AudiobookListItem(
        title = book.book.title,
        author = book.book.author,
        narrator = book.book.narrator,
        duration = book.book.totalDurationMs,
        coverPath = book.book.thumbnailPath ?: book.book.coverPath,
        progressPercent = book.progressPercent,
        onClick = { onBookClick(book) },
        onPlayClick = { onBookClick(book) }
    )
}

@Preview(showBackground = true, backgroundColor = 0xFF101418)
@Composable
fun RelatedBooksViewPreview() {
    val mockBook = BookWithProgress(
        book = BookEntity(
            id = "id1",
            sourceType = "SINGLE_AUDIO",
            sourceUri = "uri1",
            title = "Sample Audiobook",
            author = "Author Name",
            narrator = "Narrator Name",
            totalDurationMs = 3600000L,
            addedAt = System.currentTimeMillis()
        ),
        progress = null
    )
    val mockList = listOf(mockBook, mockBook.copy(book = mockBook.book.copy(id = "id2", title = "Another Book")))

    APlayerTheme(darkTheme = true) {
        Surface(color = MaterialTheme.colorScheme.background) {
            RelatedBooksView(
                currentBookId = "id0",
                authorSections = listOf(RelatedSection("Author Name", mockList)),
                narratorSections = listOf(RelatedSection("Narrator Name", mockList)),
                recentBooks = mockList,
                onBookClick = {}
            )
        }
    }
}
