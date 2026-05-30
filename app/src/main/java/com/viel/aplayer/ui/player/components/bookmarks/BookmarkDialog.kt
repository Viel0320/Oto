package com.viel.aplayer.ui.player.components.bookmarks

import android.widget.Toast
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.viel.aplayer.ui.theme.APlayerTheme

// 
// 彻底将打字状态隔离在 BookmarkDialog 内部。
// 外部不再提供 onTitleChange 高频回传通道，而只在点击“Save”保存的一瞬间，一次性将 localTitle 回调出去。
@Composable
fun BookmarkDialog(
    isVisible: Boolean,
    defaultTitle: String,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit
) {
    if (isVisible) {
        // 升级 remember 为 rememberSaveable(isVisible)，在 Dialog 显示时初始化 localTitle，
        // 并在设备屏幕旋转等配置变更（Configuration Changes）时自动从 Bundle 完美恢复内容，彻底修复输入丢失问题。
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
                val context = LocalContext.current
                TextButton(
                    onClick = {
                        onSave(localTitle)
                        Toast.makeText(context, "Bookmark added", Toast.LENGTH_SHORT).show()
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