package com.viel.aplayer.ui.player.components.bookmarks

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
    modifier: Modifier = Modifier,
    bookmarks: List<BookmarkEntity>,
    // 展平状态传递，解耦 PlayerViewModel.BookmarkDialogsState 以达成完全去 ViewModel 的设计
    bookmarkToDelete: BookmarkEntity? = null,
    bookmarkToEdit: BookmarkEntity? = null,
    bookmarkEditTitle: String = "",
    onBookmarkClick: (Long) -> Unit,
    // 触发删除确认对话框的 callback，委托给 ViewModel.requestDeleteBookmark
    onRequestDelete: (BookmarkEntity) -> Unit = {},
    // 触发编辑对话框的 callback，委托给 ViewModel.requestEditBookmark
    onRequestEdit: (BookmarkEntity) -> Unit = {},
    // 编辑框内容变更 callback，委托给 ViewModel.onBookmarkEditTitleChange
    onEditTitleChange: (String) -> Unit = {},
    // 确认删除 callback，委托给 ViewModel.deleteBookmark
    onConfirmDelete: () -> Unit = {},
    // 确认更新 callback，委托给 ViewModel.updateBookmark
    onConfirmUpdate: () -> Unit = {},
    // 关闭所有对话框 callback，委托给 ViewModel.dismissBookmarkDialogs
    onDismissDialogs: () -> Unit = {},
    currentPosition: Long = 0L
) {
    // 删除确认对话框——状态由上层通过基础类型传入，配置变更不丢失
    if (bookmarkToDelete != null) {
        AlertDialog(
            onDismissRequest = onDismissDialogs,
            title = { Text("Delete Bookmark") },
            text = { Text("Are you sure you want to delete this bookmark?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        onConfirmDelete()
                        onDismissDialogs()
                    }
                ) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = onDismissDialogs) {
                    Text("Cancel")
                }
            }
        )
    }

    // 编辑对话框——状态由上层传入，旋转屏幕后编辑的标题仍得以完好保存
    if (bookmarkToEdit != null) {
        AlertDialog(
            onDismissRequest = onDismissDialogs,
            title = { Text("Edit Bookmark") },
            text = {
                Column {
                    Text("Update bookmark:")
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = bookmarkEditTitle,
                        onValueChange = onEditTitleChange,
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onConfirmUpdate()
                        onDismissDialogs()
                    }
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = onDismissDialogs) {
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
            // 使用书签 id 作为稳定 key，确保列表更新时 item 状态不错位复用
            key = { it.id }
        ) { bookmark ->
            val isActive = currentPosition >= bookmark.globalPositionMs

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .combinedClickable(
                        onClick = { onBookmarkClick(bookmark.globalPositionMs) },
                        // 长按触发编辑，委托给 ViewModel，不再写入局部 mutableState
                        onLongClick = { onRequestEdit(bookmark) }
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

                // 点击删除图标，委托给 ViewModel.requestDeleteBookmark，不再直接写局部 mutableState
                IconButton(onClick = { onRequestDelete(bookmark) }) {
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
                bookmarkToDelete = null,
                bookmarkToEdit = null,
                bookmarkEditTitle = "",
                onBookmarkClick = {},
                onRequestDelete = {},
                onRequestEdit = {},
                currentPosition = 1500000L
            )
        }
    }
}