package com.viel.aplayer.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import com.viel.aplayer.R
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.viel.aplayer.data.BookmarkEntity
import com.viel.aplayer.ui.theme.APlayerTheme
import com.viel.aplayer.ui.utils.formatTime

@Composable
fun BookmarkListView(
    bookmarks: List<BookmarkEntity>,
    onBookmarkClick: (Long) -> Unit,
    onDeleteClick: (BookmarkEntity) -> Unit,
    modifier: Modifier = Modifier,
    currentPosition: Long = 0L
) {
    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        items(
            items = bookmarks,
            key = { it.id }
        ) { bookmark ->
            val isActive = currentPosition >= bookmark.position

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onBookmarkClick(bookmark.position) }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = bookmark.title,
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Medium,
                            fontSize = 20.sp
                        ),
                        color = if (isActive) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                    Text(
                        text = formatTime(bookmark.position),
                        style = MaterialTheme.typography.labelLarge.copy(
                            fontSize = 14.sp
                        ),
                        color = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                    )
                }

                IconButton(onClick = { onDeleteClick(bookmark) }) {
                    Icon(
                        painterResource(R.drawable.ic_rounded_delete),
                        contentDescription = "Delete Bookmark",
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}

@Preview(name = "Bookmark List View - Dark", apiLevel = 36)
@Composable
fun BookmarkListViewDarkPreview() {
    val sampleBookmarks = listOf(
        BookmarkEntity(1, "uri", 0L, "Introduction"),
        BookmarkEntity(2, "uri", 300000L, "Chapter 1"),
        BookmarkEntity(3, "uri", 1200000L, "Chapter 2")
    )

    APlayerTheme(darkTheme = true) {
        Surface(color = Color(0xFF101418)) {
            BookmarkListView(
                bookmarks = sampleBookmarks,
                onBookmarkClick = {},
                onDeleteClick = {},
                currentPosition = 1500000L
            )
        }
    }
}
