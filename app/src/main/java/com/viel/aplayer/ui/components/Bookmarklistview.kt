package com.viel.aplayer.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.viel.aplayer.ui.theme.APlayerTheme

import java.util.Locale

data class BookmarkItem(
    val time: Long,
    val title: String
)

@Composable
fun BookmarkListView(
    bookmarks: List<BookmarkItem>,
    onBookmarkClick: (Long) -> Unit,
    modifier: Modifier = Modifier,
    currentPosition: Long = 0L
) {
    val sortedBookmarks = bookmarks.sortedBy { it.time }

    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        items(sortedBookmarks) { bookmark ->
            val isActive = currentPosition >= bookmark.time 
            // 简单的逻辑：如果当前位置在书签之后，且没有下一个更接近的书签，则视为“活动”
            // 这里为了模仿 SubtitlesView 的高亮风格，我们简单处理
            
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onBookmarkClick(bookmark.time) },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = bookmark.title,
                    style = MaterialTheme.typography.headlineSmall.copy(
                        fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal,
                        fontSize = 18.sp
                    ),
                    color = if (isActive) Color.White else Color.White.copy(alpha = 0.3f),
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = formatTime(bookmark.time),
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontSize = 14.sp
                    ),
                    color = if (isActive) Color.White.copy(alpha = 0.7f) else Color.White.copy(alpha = 0.2f)
                )
            }
        }
    }
}

private fun formatTime(ms: Long): String {
    val totalSeconds = ms / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        String.format(Locale.getDefault(), "%02d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)
    }
}

@Preview
@Composable
fun BookmarkListViewPreview() {
    val sampleBookmarks = listOf(
        BookmarkItem(0L, "开始"),
        BookmarkItem(300000L, "第二章：相遇"),
        BookmarkItem(650000L, "第三章：冲突"),
        BookmarkItem(1200000L, "第四章：转机"),
        BookmarkItem(1800000L, "第五章：高潮"),
        BookmarkItem(2400000L, "第六章：结局"),
        BookmarkItem(2700000L, "后记"),
        BookmarkItem(3000000L, "访谈"),
        BookmarkItem(3300000L, "致谢"),
        BookmarkItem(3600000L, "预告")
    )

    APlayerTheme {
        Surface(color = Color(0xFF101418)) {
            BookmarkListView(
                bookmarks = sampleBookmarks,
                onBookmarkClick = {},
                currentPosition = 700000L // 假设当前在第三章
            )
        }
    }
}
