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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.SuggestionChipDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.viel.aplayer.data.store.GlassEffectMode
import com.viel.aplayer.ui.common.HazePresets
import com.viel.aplayer.ui.theme.APlayerTheme
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeEffect

@OptIn(dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi::class)
@Composable
fun ChapterDisplay(
    currentChapterTitle: String?,
    onChapterClick: () -> Unit,
    onBookmarkClick: () -> Unit,
    modifier: Modifier = Modifier,
    title: String? = null,
    glassEffectMode: GlassEffectMode = GlassEffectMode.Material,
    hazeState: HazeState? = null
) {
    val isHaze = glassEffectMode == GlassEffectMode.Haze && hazeState != null

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        SuggestionChip(
            onClick = onChapterClick,
            modifier = Modifier
                .weight(1f, fill = false)
                .then(
                    if (isHaze) {
                        Modifier
                            .clip(RoundedCornerShape(12.dp))
                            // 为每一次改动添加详尽的中文注释：在此引入全局高灵动性“高雅白羽雾化”毛玻璃 HazeStyle，对章节选择胶囊背景开启背景高斯模糊渲染管线
                            .hazeEffect(state = hazeState, style = HazePresets.HazeStyle)
                    } else {
                        Modifier
                    }
                ),
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
            colors = if (isHaze) {
                // 为每一次改动添加详尽的中文注释：Haze 模式下选用全局高透光率的半透蒙版背景色与主色图标，展现晶莹剔透的高奢质感
                SuggestionChipDefaults.suggestionChipColors(
                    containerColor = HazePresets.BackgroundColor,
                    labelColor = MaterialTheme.colorScheme.primary,
                    iconContentColor = MaterialTheme.colorScheme.primary
                )
            } else {
                SuggestionChipDefaults.suggestionChipColors(
                    labelColor = LocalContentColor.current,
                    iconContentColor = LocalContentColor.current
                )
            },
            border = if (isHaze) {
                // 为每一次改动添加详尽的中文注释：Haze 模式下采用全局微光描边规范，精致亮眼
                HazePresets.Border
            } else {
                SuggestionChipDefaults.suggestionChipBorder(
                    enabled = true,
                    borderColor = LocalContentColor.current
                )
            }
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