package com.viel.aplayer.ui.player.components

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.viel.aplayer.data.entity.BookEntity
import com.viel.aplayer.data.entity.BookWithProgress
import com.viel.aplayer.ui.common.CoverImageSourceSelector
import com.viel.aplayer.ui.common.theme.APlayerTheme
import com.viel.aplayer.ui.home.components.ListItem
import com.viel.aplayer.ui.player.components.relatedsection.RelatedSection

@Composable
fun RelatedBooksView(
    currentBookId: String,
    // 新增 heuristicBooks 参数，用于接收置顶展示的启发式智能推荐有声书列表
    heuristicBooks: List<BookWithProgress>,
    authorSections: List<RelatedSection>,
    narratorSections: List<RelatedSection>,
    recentBooks: List<BookWithProgress>,
    onBookClick: (BookWithProgress) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 24.dp)
    ) {
        // 
        // 【置顶展示启发式推荐】如果启发式推荐列表不为空，则置顶渲染 "Recommended for You" 分区。
        // 使用唯一的前缀复合键 "h:${book.book.id}"，保证即使兜底书籍与其它 section 重合时，其列表项在全局的 Compose 身份仍保持绝对唯一。
        if (heuristicBooks.isNotEmpty()) {
            item {
                RelatedSectionHeader("Recommended for You")
            }
            items(heuristicBooks, key = { "h:${it.book.id}" }) { book ->
                RelatedAudiobookItem(book, onBookClick)
            }
        }

        authorSections.forEach { section ->
            if (section.books.isNotEmpty()) {
                item {
                    RelatedSectionHeader("More by ${section.name}")
                }
                // M-20 修复 — 添加 key 让 Compose 跟踪列表项身份，避免相关书单刷新时 item 状态错位复用
                items(section.books, key = { it.book.id }) { book ->
                    RelatedAudiobookItem(book, onBookClick)
                }
            }
        }

        narratorSections.forEach { section ->
            if (section.books.isNotEmpty()) {
                item {
                    RelatedSectionHeader("More by ${section.name}")
                }
                // M-20 修复 — 旁白分区迹不同一个 section 可能包含相同 id 的书，
                // 使用 "迹 narrator:book.id" 复合键保证跨 section 唯一性
                items(section.books, key = { "n:${it.book.id}" }) { book ->
                    RelatedAudiobookItem(book, onBookClick)
                }
            }
        }

        if (recentBooks.isNotEmpty()) {
            item {
                RelatedSectionHeader("Recently Added")
            }
            // M-20 修复 — recentBooks 也可能与前面 section 重叠，
            // 使用 "r:book.id" 前缀复合键保证不同 section 间的公局唯一性
            items(recentBooks, key = { "r:${it.book.id}" }) { book ->
                RelatedAudiobookItem(book, onBookClick)
            }
        }
    }
}

@Composable
private fun RelatedSectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(start = 16.dp, top = 24.dp, end = 16.dp, bottom = 8.dp)
    )
}

@Composable
private fun RelatedAudiobookItem(
    book: BookWithProgress,
    onBookClick: (BookWithProgress) -> Unit
) {
    ListItem(
        title = book.book.title,
        author = book.book.author,
        narrator = book.book.narrator,
        duration = book.book.totalDurationMs,
        // 详尽注释：相关书籍列表与主页普通列表同属小图场景，统一走 small 选择规则；
        // 这能让推荐区、主页列表和迷你播放器在相同封面上共享 ThumbnailSmall 缓存 key。
        coverPath = CoverImageSourceSelector.small(
            thumbnailPath = book.book.thumbnailPath,
            coverPath = book.book.coverPath
        ),
        // 详尽注释：补上传递 lastScannedAt，封面自愈或手动重建后相关书籍列表会生成新 key，
        // 不再因为默认 0 时间戳继续命中旧封面缓存。
        coverLastUpdated = book.book.lastScannedAt,
        progressPercent = book.progressPercent,
        onClick = { onBookClick(book) },
        onPlayClick = { onBookClick(book) }
    )
}

@Preview(showBackground = true, backgroundColor = 0xFF101418)
@Composable
fun RelatedBooksViewPreview() {
    val mockBook = BookWithProgress(
        book = BookEntity(
            id = "id1",
            // Preview data follows the new logical-book model.
            rootId = "preview-root",
            sourceType = "SINGLE_AUDIO",
            title = "Sample Audiobook",
            author = "Author Name",
            narrator = "Narrator Name",
            totalDurationMs = 3600000L,
            addedAt = System.currentTimeMillis()
        ),
        progress = null
    )
    val mockList = listOf(mockBook, mockBook.copy(book = mockBook.book.copy(id = "id2", title = "Another Book")))

    APlayerTheme(darkTheme = true) {
        Surface(color = MaterialTheme.colorScheme.background) {
            RelatedBooksView(
                currentBookId = "id0",
                // Preview 填充传入启发式推荐有声书 Mock 数据列表
                heuristicBooks = mockList,
                authorSections = listOf(RelatedSection("Author Name", mockList)),
                narratorSections = listOf(RelatedSection("Narrator Name", mockList)),
                recentBooks = mockList,
                onBookClick = {}
            )
        }
    }
}
