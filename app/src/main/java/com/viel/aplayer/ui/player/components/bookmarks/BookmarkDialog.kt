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
import com.viel.aplayer.shared.settings.GlassEffectMode
import com.viel.aplayer.ui.common.APlayerDialogTemplate
import com.viel.aplayer.ui.common.theme.APlayerTheme
import dev.chrisbanes.haze.HazeState

@Composable
fun BookmarkDialog(
    isVisible: Boolean,
    defaultTitle: String,
    hazeState: HazeState? = null,
    glassEffectMode: GlassEffectMode,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit
) {
    if (isVisible) {
        var localTitle by rememberSaveable(isVisible) { mutableStateOf(defaultTitle) }

        APlayerDialogTemplate(
            onDismissRequest = onDismiss,
            hazeState = hazeState,
            glassEffectMode = glassEffectMode,
            scrollable = false,
            title = { Text(stringResource(R.string.bookmark_add_title)) },
            body = {
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
