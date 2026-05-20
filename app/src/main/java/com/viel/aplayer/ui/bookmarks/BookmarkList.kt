package com.viel.aplayer.ui.bookmarks

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.viel.aplayer.data.entity.BookmarkEntity
import com.viel.aplayer.ui.common.formatDate
import com.viel.aplayer.ui.common.formatTime
import com.viel.aplayer.ui.theme.APlayerTheme

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun BookmarkListView(
    bookmarks: List<BookmarkEntity>,
    onBookmarkClick: (Long) -> Unit,
    onDeleteClick: (BookmarkEntity) -> Unit,
    modifier: Modifier = Modifier,
    onUpdateClick: (BookmarkEntity, String) -> Unit = { _, _ -> },
    currentPosition: Long = 0L
) {
    val bookmarkToDeleteState = remember { mutableStateOf<BookmarkEntity?>(null) }
    val bookmarkToDelete = bookmarkToDeleteState.value

    val bookmarkToEditState = remember { mutableStateOf<BookmarkEntity?>(null) }
    val bookmarkToEdit = bookmarkToEditState.value
    var editTitle by remember { mutableStateOf("") }

    if (bookmarkToDelete != null) {
        AlertDialog(
            onDismissRequest = { bookmarkToDeleteState.value = null },
            title = { Text("Delete Bookmark") },
            text = { Text("Are you sure you want to delete this bookmark?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDeleteClick(bookmarkToDelete)
                        bookmarkToDeleteState.value = null
                    }
                ) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { bookmarkToDeleteState.value = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (bookmarkToEdit != null) {
        AlertDialog(
            onDismissRequest = { bookmarkToEditState.value = null },
            title = { Text("Edit Bookmark") },
            text = {
                Column {
                    Text("Update bookmark:")
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = editTitle,
                        onValueChange = { editTitle = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onUpdateClick(bookmarkToEdit, editTitle)
                        bookmarkToEditState.value = null
                    }
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { bookmarkToEditState.value = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        items(
            items = bookmarks,
            key = { it.id }
        ) { bookmark ->
            val isActive = currentPosition >= bookmark.globalPositionMs

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .combinedClickable(
                        onClick = { onBookmarkClick(bookmark.globalPositionMs) },
                        onLongClick = { 
                            bookmarkToEditState.value = bookmark
                            editTitle = bookmark.title
                        }
                    )
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
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = formatTime(bookmark.globalPositionMs),
                            style = MaterialTheme.typography.labelLarge.copy(
                                fontSize = 14.sp
                            ),
                            color = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                        )
                        Text(
                            text = "@ ${formatDate(bookmark.createdAt)}",
                            style = MaterialTheme.typography.labelLarge.copy(
                                fontSize = 14.sp
                            ),
                            color = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                        )
                    }
                }

                IconButton(onClick = { bookmarkToDeleteState.value = bookmark }) {
                Icon(
                        Icons.Rounded.Delete,
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
        BookmarkEntity("1", "id", 0L, title = "Introduction"),
        BookmarkEntity("2", "id", 300000L, title = "Chapter 1"),
        BookmarkEntity("3", "id", 1200000L, title = "Chapter 2")
    )

    APlayerTheme(darkTheme = true) {
        Surface(color = Color(0xFF101418)) {
            BookmarkListView(
                bookmarks = sampleBookmarks,
                onBookmarkClick = {},
                onDeleteClick = {},
                onUpdateClick = { _, _ -> },
                currentPosition = 1500000L
            )
        }
    }
}