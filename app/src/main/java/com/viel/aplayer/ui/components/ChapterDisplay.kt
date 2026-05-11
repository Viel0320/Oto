package com.viel.aplayer.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.List
import androidx.compose.material.icons.rounded.BookmarkAdd
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.viel.aplayer.ui.theme.APlayerTheme

@Composable
fun ChapterDisplay(
    currentChapterTitle: String?,
    onChapterClick: () -> Unit,
    onBookmarkClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        SuggestionChip(
            onClick = onChapterClick,
            modifier = Modifier.weight(1f, fill = false),
            label = {
                Text(
                    text = currentChapterTitle ?: "No Chapters",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            },
            icon = {
                Icon(
                    Icons.AutoMirrored.Rounded.List,
                    contentDescription = currentChapterTitle ?: "No Chapters",
                    modifier = Modifier.size(AssistChipDefaults.IconSize)
                )
            },
            shape = RoundedCornerShape(12.dp)
        )

        IconButton(onClick = onBookmarkClick) {
            Icon(Icons.Rounded.BookmarkAdd, contentDescription = "Bookmark")
        }
    }
}

@Preview(showBackground = true)
@Composable
fun ChapterDisplayPreview() {
    APlayerTheme {
        Surface {
            ChapterDisplay(
                currentChapterTitle = "Chapter 1: The Beginning",
                onChapterClick = {},
                onBookmarkClick = {}
            )
        }
    }
}
