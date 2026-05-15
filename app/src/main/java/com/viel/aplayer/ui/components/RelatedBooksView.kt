package com.viel.aplayer.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.viel.aplayer.data.AudiobookEntity
import com.viel.aplayer.ui.screens.AudiobookListItem
import androidx.core.net.toUri

@Composable
fun RelatedBooksView(
    author: String,
    narrator: String,
    authorBooks: List<AudiobookEntity>,
    narratorBooks: List<AudiobookEntity>,
    recentBooks: List<AudiobookEntity>,
    onBookClick: (AudiobookEntity) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 24.dp)
    ) {
        if (authorBooks.isNotEmpty()) {
            item {
                RelatedSectionHeader("More by $author")
            }
            items(authorBooks) { book ->
                RelatedAudiobookItem(book, onBookClick)
            }
        }

        if (narratorBooks.isNotEmpty()) {
            item {
                RelatedSectionHeader("More by $narrator")
            }
            items(narratorBooks) { book ->
                RelatedAudiobookItem(book, onBookClick)
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
        modifier = Modifier.padding(start = 24.dp, top = 24.dp, end = 24.dp, bottom = 8.dp)
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
        addedAt = book.addedAt,
        onClick = { onBookClick(book) },
        onPlayClick = { onBookClick(book) }
    )
}
