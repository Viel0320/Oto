package com.viel.aplayer.ui.player.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.List
import androidx.compose.material.icons.rounded.BookmarkAdd
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.SuggestionChipDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
    modifier: Modifier = Modifier,
    title: String? = null
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
                    text = currentChapterTitle ?: title ?: "No Chapters",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            },
            icon = {
                Icon(
                    Icons.AutoMirrored.Rounded.List,
                    contentDescription = null,
                    modifier = Modifier.size(SuggestionChipDefaults.IconSize)
                )
            },
            shape = RoundedCornerShape(12.dp),
            colors = SuggestionChipDefaults.suggestionChipColors(
                labelColor = LocalContentColor.current,
                iconContentColor = LocalContentColor.current
            ),
            border = SuggestionChipDefaults.suggestionChipBorder(
                enabled = true,
                borderColor = LocalContentColor.current
            )
        )

        IconButton(
            onClick = onBookmarkClick,
            modifier = Modifier.padding(start = 16.dp) // 在这里增加最小间距
        ) {
            Icon(Icons.Rounded.BookmarkAdd, contentDescription = "Bookmark")
        }
    }
}

// Added apiLevel = 36 to resolve layout fidelity warning in Android Studio Preview
// when using a compileSdk higher than the layout editor's supported range.
@Preview(showBackground = true, apiLevel = 36)
@Composable
fun ChapterDisplayPreview() {
    APlayerTheme {
        Surface {
            ChapterDisplay(
                currentChapterTitle = "Chapter 1: The Beginning",
                title = "The Great Adventure",
                onChapterClick = {},
                onBookmarkClick = {}
            )
        }
    }
}