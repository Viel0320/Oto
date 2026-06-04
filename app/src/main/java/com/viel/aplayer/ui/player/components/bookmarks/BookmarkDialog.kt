package com.viel.aplayer.ui.player.components.bookmarks

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.viel.aplayer.ui.common.theme.APlayerTheme

// Isolate dialog input state (To decouple high-frequency text changes from external observers)
// Restricts typing state within the component scope and emits the string payload upon clicking save.
@Composable
fun BookmarkDialog(
    isVisible: Boolean,
    defaultTitle: String,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit
) {
    if (isVisible) {
        // Cache input state locally (To preserve bookmark title draft across device orientation changes)
        // Uses rememberSaveable bound to isVisible key to retain state in Android Bundles.
        var localTitle by rememberSaveable(isVisible) { mutableStateOf(defaultTitle) }

        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Add Bookmark") },
            text = {
                BookmarkDialogContent(
                    title = localTitle,
                    onTitleChange = { localTitle = it }
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onSave(localTitle)
                    }
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) {
                    Text("Cancel")
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
        label = { Text("Bookmark Title") },
        placeholder = { Text("Enter a name for this bookmark") },
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