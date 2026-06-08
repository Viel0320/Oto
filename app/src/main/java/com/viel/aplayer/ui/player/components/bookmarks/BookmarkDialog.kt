package com.viel.aplayer.ui.player.components.bookmarks

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.viel.aplayer.R
import com.viel.aplayer.data.store.GlassEffectMode
import com.viel.aplayer.ui.common.APlayerDialogTemplate
import com.viel.aplayer.ui.common.theme.APlayerTheme
import dev.chrisbanes.haze.HazeState

// Isolate dialog input state (To decouple high-frequency text changes from external observers)
// Restricts typing state within the component scope and emits the string payload upon clicking save.
@Composable
fun BookmarkDialog(
    isVisible: Boolean,
    defaultTitle: String,
    // Bookmark Dialog Backdrop Source (Use the player page sampling source for add-bookmark dialogs)
    // PlayerScreen passes the resolved player HazeState so this dialog shares the same glass source as player overlays.
    hazeState: HazeState? = null,
    // Bookmark Dialog Glass Mode (Delegate visual mode to the shared dialog shell)
    // Keeps the component independent from app settings lookup while still rendering Material or Haze consistently.
    glassEffectMode: GlassEffectMode,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit
) {
    if (isVisible) {
        // Cache input state locally (To preserve bookmark title draft across device orientation changes)
        // Uses rememberSaveable bound to isVisible key to retain state in Android Bundles.
        var localTitle by rememberSaveable(isVisible) { mutableStateOf(defaultTitle) }

        APlayerDialogTemplate(
            onDismissRequest = onDismiss,
            hazeState = hazeState,
            glassEffectMode = glassEffectMode,
            scrollable = false,
            title = { Text(stringResource(R.string.bookmark_add_title)) },
            body = {
                // Bookmark Creation Body (Keep draft input local while sharing dialog chrome)
                // The text draft remains scoped to this composable and is emitted only when Save is clicked.
                BookmarkDialogContent(
                    title = localTitle,
                    onTitleChange = { localTitle = it }
                )
            },
            actions = {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.action_cancel))
                }
                TextButton(
                    onClick = {
                        onSave(localTitle)
                    }
                ) {
                    Text(stringResource(R.string.action_save))
                }
            }
        )
    }
}

@Composable
fun BookmarkDialogContent(
    title: String,
    onTitleChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    OutlinedTextField(
        value = title,
        onValueChange = onTitleChange,
        // Bookmark Field Copy (Resource-backed label and placeholder for the bookmark naming form)
        // The entered bookmark title is user data, but the form chrome belongs to app UI and must follow the selected language.
        label = { Text(stringResource(R.string.bookmark_title_label)) },
        placeholder = { Text(stringResource(R.string.bookmark_title_placeholder)) },
        singleLine = true,
        modifier = modifier.fillMaxWidth()
    )
}

@Preview(showBackground = true, apiLevel = 36, backgroundColor = 0xFF141218)
@Composable
fun BookmarkDialogPreview() {
    APlayerTheme(darkTheme = true, dynamicColor = false) {
        Surface {
            BookmarkDialogContent(
                title = "Preview Bookmark",
                onTitleChange = {},
                modifier = Modifier.padding(16.dp)
            )
        }
    }
}
