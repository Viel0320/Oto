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
import com.viel.aplayer.R
import com.viel.aplayer.application.library.player.PlayerBookmarkItem
import com.viel.aplayer.data.store.GlassEffectMode
import com.viel.aplayer.shared.formatDate
import com.viel.aplayer.shared.formatTime
import com.viel.aplayer.ui.common.APlayerDialogTemplate
import com.viel.aplayer.ui.common.theme.APlayerTheme
import dev.chrisbanes.haze.HazeState

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun BookmarkListView(
    modifier: Modifier = Modifier,
    bookmarks: List<PlayerBookmarkItem>,
    // Flattened state delegates (To decouple BookmarkDialogsState parameters and enforce stateless layouts design)
    bookmarkToDelete: PlayerBookmarkItem? = null,
    bookmarkToEdit: PlayerBookmarkItem? = null,
    bookmarkEditTitle: String = "",
    onBookmarkClick: (Long) -> Unit,
    // Request deletion confirmation (To delegate deletion checks to ViewModel callbacks)
    onRequestDelete: (PlayerBookmarkItem) -> Unit = {},
    // Request modification confirmation (To delegate edit overlays checks to ViewModel callbacks)
    onRequestEdit: (PlayerBookmarkItem) -> Unit = {},
    // Update edit text (To forward edit inputs changes back to ViewModel scope)
    onEditTitleChange: (String) -> Unit = {},
    // Confirm bookmark deletion (To invoke bookmark deletion transactions)
    onConfirmDelete: () -> Unit = {},
    // Confirm title updates (To invoke bookmark renaming transactions)
    onConfirmUpdate: () -> Unit = {},
    // Clear dialog states (To invoke dialogs dismissal callbacks)
    onDismissDialogs: () -> Unit = {},
    // Bookmark List Dialog Backdrop Source (Use the active player content haze source)
    // Layout variants pass their chapter/player HazeState so edit and delete dialogs sample the same visible player surface.
    hazeState: HazeState? = null,
    // Bookmark List Dialog Glass Mode (Render edit/delete confirmations through the shared dialog shell)
    // Defaults to Material for previews while production callers pass the current player glass setting.
    glassEffectMode: GlassEffectMode = GlassEffectMode.Material,
    currentPosition: Long = 0L
) {
    // Bookmark Assistive Action Labels (Expose row commands through named semantics)
    // Bookmark rows contain seek, edit, and delete commands, so the row advertises custom actions in addition to the visible delete button.
    val bookmarkOpenActionLabel = stringResource(R.string.bookmark_open_action)
    val bookmarkEditActionLabel = stringResource(R.string.bookmark_edit_action)
    val bookmarkDeleteActionLabel = stringResource(R.string.bookmark_delete_content_description)

    // Deletion confirmation overlay (To show alert layout using primitive variables passed from container)
    if (bookmarkToDelete != null) {
        APlayerDialogTemplate(
            onDismissRequest = onDismissDialogs,
            hazeState = hazeState,
            glassEffectMode = glassEffectMode,
            scrollable = false,
            title = { Text(stringResource(R.string.bookmark_delete_title)) },
            body = {
                // Bookmark Delete Body (Confirm destructive bookmark removal)
                // The selected bookmark entity remains owned by PlayerViewModel while this component only renders the confirmation step.
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

    // Modification dialog overlay (To preserve active drafts titles during configuration changes)
    if (bookmarkToEdit != null) {
        APlayerDialogTemplate(
            onDismissRequest = onDismissDialogs,
            hazeState = hazeState,
            glassEffectMode = glassEffectMode,
            scrollable = false,
            title = { Text(stringResource(R.string.bookmark_edit_title)) },
            body = {
                // Bookmark Edit Body (Expose ViewModel-owned draft title to the shared dialog shell)
                // Title changes are forwarded immediately so rotation and layout changes preserve the active edit draft.
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
            // Configure stable key (To assign unique identifier key to prevent index recycling glitches)
            key = { it.id }
        ) { bookmark ->
            val isActive = currentPosition >= bookmark.globalPositionMs

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .combinedClickable(
                        onClickLabel = bookmarkOpenActionLabel,
                        onClick = { onBookmarkClick(bookmark.globalPositionMs) },
                        // Long click triggers modification (To route long-press edit commands directly to ViewModel)
                        onLongClickLabel = bookmarkEditActionLabel,
                        onLongClick = { onRequestEdit(bookmark) }
                    )
                    .semantics {
                        // Bookmark Row Custom Actions (Mirror row-level and trailing commands for assistive tech)
                        // Switch Access users can jump, edit, or delete the bookmark from the row action menu without locating hidden long-press gestures or the nested icon.
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
                            // Localized Bookmark Date Prefix (Format bookmark creation dates through resources)
                            // The timestamp is bookmark data, while the visible prefix and spacing are app-authored UI formatting.
                            text = stringResource(R.string.bookmark_created_at_label, formatDate(bookmark.createdAt)),
                            style = MaterialTheme.typography.labelLarge.copy(
                                fontSize = 14.sp
                            ),
                            color = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                        )
                    }
                }

                // Deletion click trigger (To delegate bookmark removals commands to ViewModel request queue)
                IconButton(onClick = { onRequestDelete(bookmark) }) {
                    Icon(
                        Icons.Rounded.Delete,
                        contentDescription = stringResource(R.string.bookmark_delete_content_description),
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
