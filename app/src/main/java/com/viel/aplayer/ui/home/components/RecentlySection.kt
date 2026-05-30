package com.viel.aplayer.ui.home.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.viel.aplayer.data.entity.BookWithProgress
import com.viel.aplayer.data.store.GlassEffectMode

/**
 * 首页“最近播放/最近添加”有声书的横向滚动列表解耦组件。
 * 
 * 经过物理拆分，将该区块完全从 HomeScreen 大类中独立出来，
 * 践行了界面与组件解耦的设计理念，规避了大组件过度负载，同时使“最近”栏的卡片在排版上更加高内聚。
 * 
 * @param recentTitle 横向区块的主标题文本（例如：最近添加、最近播放）。
 * @param recentBooks 最近的有声书数据集合。
 * @param recentListState 横向滚动的 LazyListState 状态托管句柄，用于在主网格滚动时保持横向偏好位置不变。
 * @param glassEffectMode 全局磨砂玻璃视效雾化模式。
 * @param screenHorizontalPadding 动态自适应算出的视觉对齐左缩进边距。
 * @param onNavigateToDetail 点击卡片跳转至对应书籍详情页的回调函数。
 * @param onBookLongClick 长按卡片唤起有声书一级操作菜单的回调函数。
 */
@Composable
fun RecentlyAddedSection(
    recentTitle: String,
    recentBooks: List<BookWithProgress>,
    recentListState: LazyListState,
    glassEffectMode: GlassEffectMode,
    screenHorizontalPadding: Dp,
    onNavigateToDetail: (String) -> Unit,
    onBookLongClick: (BookWithProgress) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth()
    ) {
        // 详尽的中文注释：绘制“最近播放/添加”的主体大标题，并通过 screenHorizontalPadding 确保与其上方和下方的文字安全边距对齐
        Text(
            text = recentTitle,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = screenHorizontalPadding, vertical = 16.dp)
        )

        // 详尽的中文注释：横向滚动视图，为了保证首张封面卡片的物理边缘能与上方标题文字完美垂直对齐，
        // 将左右 contentPadding 设置为 (业务边距 - 卡片自带的物理外间距 8dp)，实现极佳的视觉对齐补偿。
        LazyRow(
            state = recentListState,
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = screenHorizontalPadding - 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // M-20 物理修复：使用唯一的 book.id 作为 LazyList 的稳定 key，杜绝因为列表频繁加载刷新导致卡片封面绘制闪烁或错位
            items(recentBooks, key = { it.book.id }) { book ->
                RecentlyItem(
                    title = book.book.title,
                    author = book.book.author,
                    narrator = book.book.narrator,
                    // 详尽的中文注释：如果当前有声书有具体的阅读进度，则在其标签上回填显示进度百分比，否则回填 "NEW" 表示最新导入
                    progressText = if (book.progressPercent > 0) "${book.progressPercent}%" else "NEW",
                    coverPath = book.book.thumbnailPath ?: book.book.coverPath,
                    // 详尽的中文注释：将最后扫描更新时间戳传递给卡片的 Coil，使其在缓存重新提取后可声明式直接强刷重绘，不经历磁盘 IO
                    coverLastUpdated = book.book.lastScannedAt,
                    onClick = { onNavigateToDetail(book.book.id) },
                    onLongClick = { onBookLongClick(book) },
                    glassEffectMode = glassEffectMode,
                    // 详尽的中文注释：将数据库中为该书籍物理提取并持久化缓存的 ARGB 主色调传递给卡片，以渲染氛围底色
                    coverColorArgb = book.book.backgroundColorArgb
                )
            }
        }
    }
}
