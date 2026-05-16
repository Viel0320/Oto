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
import com.viel.aplayer.data.AudiobookEntity
import com.viel.aplayer.ui.state.RelatedSection
import com.viel.aplayer.ui.theme.APlayerTheme

@Composable
fun RelatedBooksView(
    currentBookUri: String,
    authorSections: List<RelatedSection>,
    narratorSections: List<RelatedSection>,
    recentBooks: List<AudiobookEntity>,
    onBookClick: (AudiobookEntity) -> Unit,
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
    book: AudiobookEntity,
    onBookClick: (AudiobookEntity) -> Unit
) {
    AudiobookListItem(
        title = book.title,
        author = book.author,
        narrator = book.narrator,
        duration = book.duration,
        coverPath = book.thumbnailPath ?: book.coverPath,
        progressPercent = book.progressPercent,
//        addedAt = book.addedAt,
        onClick = { onBookClick(book) },
        onPlayClick = { onBookClick(book) }
    )
}

@Preview(showBackground = true, backgroundColor = 0xFF101418)
@Composable
fun RelatedBooksViewPreview() {
    val mockBook = AudiobookEntity(
        uri = "uri1",
        title = "Sample Audiobook",
        author = "Author Name",
        narrator = "Narrator Name",
        duration = 3600000L,
        addedAt = System.currentTimeMillis()
    )
    val mockList = listOf(mockBook, mockBook.copy(uri = "uri2", title = "Another Book"))

    APlayerTheme(darkTheme = true) {
        Surface(color = MaterialTheme.colorScheme.background) {
            RelatedBooksView(
                currentBookUri = "uri0",
                authorSections = listOf(RelatedSection("Author Name", mockList)),
                narratorSections = listOf(RelatedSection("Narrator Name", mockList)),
                recentBooks = mockList,
                onBookClick = {}
            )
        }
    }
}
