package com.viel.oto.ui.player.components.bookmarks

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
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.viel.oto.R
import com.viel.oto.application.library.player.PlayerBookmarkItem
import com.viel.oto.shared.formatDate
import com.viel.oto.shared.formatTime
import com.viel.oto.shared.settings.GlassEffectMode
import com.viel.oto.ui.common.OtoDialogTemplate
import com.viel.oto.ui.common.theme.OtoTheme
import dev.chrisbanes.haze.HazeState

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun BookmarkListView(
    modifier: Modifier = Modifier,
    bookmarks: List<PlayerBookmarkItem>,
    bookmarkToDelete: PlayerBookmarkItem? = null,
    bookmarkToEdit: PlayerBookmarkItem? = null,
    bookmarkEditTitle: String = "",
    onBookmarkClick: (Long) -> Unit,
    onRequestDelete: (PlayerBookmarkItem) -> Unit = {},
    onRequestEdit: (PlayerBookmarkItem) -> Unit = {},
    onEditTitleChange: (String) -> Unit = {},
    onConfirmDelete: () -> Unit = {},
    onConfirmUpdate: () -> Unit = {},
    onDismissDialogs: () -> Unit = {},
    hazeState: HazeState? = null,
    glassEffectMode: GlassEffectMode = GlassEffectMode.Material,
    currentPosition: Long = 0L
) {
    val bookmarkOpenActionLabel = stringResource(R.string.bookmark_open_action)
    val bookmarkEditActionLabel = stringResource(R.string.bookmark_edit_action)
    val bookmarkDeleteActionLabel = stringResource(R.string.bookmark_delete_title)

    if (bookmarkToDelete != null) {
        OtoDialogTemplate(
            onDismissRequest = onDismissDialogs,
            hazeState = hazeState,
            glassEffectMode = glassEffectMode,
            scrollable = false,
            title = { Text(stringResource(R.string.bookmark_delete_title)) },
            body = {
                Text(stringResource(R.string.bookmark_delete_body))
            },
            actions = {
                TextButton(onClick = onDismissDialogs) {
                    Text(stringResource(R.string.action_cancel))
                }
                TextButton(
                    onClick = {
                        onConfirmDelete()
                        onDismissDialogs()
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text(stringResource(R.string.action_delete))
                }
            }
        )
    }

    if (bookmarkToEdit != null) {
        OtoDialogTemplate(
            onDismissRequest = onDismissDialogs,
            hazeState = hazeState,
            glassEffectMode = glassEffectMode,
            scrollable = false,
            title = { Text(stringResource(R.string.bookmark_edit_title)) },
            body = {
                Column {
                    Text(stringResource(R.string.bookmark_update_label))
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = bookmarkEditTitle,
                        onValueChange = onEditTitleChange,
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                }
            },
            actions = {
                TextButton(onClick = onDismissDialogs) {
                    Text(stringResource(R.string.action_cancel))
                }
                TextButton(
                    onClick = {
                        onConfirmUpdate()
                        onDismissDialogs()
                    }
                ) {
                    Text(stringResource(R.string.action_save))
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
                        onClickLabel = bookmarkOpenActionLabel,
                        onClick = { onBookmarkClick(bookmark.globalPositionMs) },
                        onLongClickLabel = bookmarkEditActionLabel,
                        onLongClick = { onRequestEdit(bookmark) }
                    )
                    .semantics {
                        customActions = listOf(
                            CustomAccessibilityAction(bookmarkOpenActionLabel) {
                                onBookmarkClick(bookmark.globalPositionMs)
                                true
                            },
                            CustomAccessibilityAction(bookmarkEditActionLabel) {
                                onRequestEdit(bookmark)
                                true
                            },
                            CustomAccessibilityAction(bookmarkDeleteActionLabel) {
                                onRequestDelete(bookmark)
                                true
                            }
                        )
                    }
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

                IconButton(onClick = { onRequestDelete(bookmark) }) {
                    Icon(
                        Icons.Rounded.Delete,
                        contentDescription = stringResource(R.string.bookmark_delete_title),
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
        PlayerBookmarkItem("1", "id", 0L, title = "Introduction", createdAt = 0L),
        PlayerBookmarkItem("2", "id", 300000L, title = "Chapter 1", createdAt = 300000L),
        PlayerBookmarkItem("3", "id", 1200000L, title = "Chapter 2", createdAt = 1200000L)
    )

    OtoTheme(darkTheme = true) {
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
