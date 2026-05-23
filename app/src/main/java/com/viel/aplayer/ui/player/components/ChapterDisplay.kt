package com.viel.aplayer.ui.player.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
// 为每一次改动添加详尽的中文注释：引入 Spacer 和 width 布局扩展，用以精细编排自定义磨砂药丸内的图标与文字间距
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
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
import com.viel.aplayer.ui.theme.APlayerTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop


import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.viel.aplayer.ui.player.BookMetadataState
import com.viel.aplayer.ui.player.PlayerActions
import com.viel.aplayer.ui.player.PlayerViewModel

/**
 * 章节标题显示的有状态局部隔间。
 * 本组件局部订阅极其低频的章节变化通道 currentChapterState。
 * 只有在真正切换音频章节的边界临界点时才会触发重组，实现了极致的重组频率隔离。
 */
@Composable
fun ChapterDisplayStateful(
    viewModel: PlayerViewModel,
    metadata: BookMetadataState,
    actions: PlayerActions,
    glassEffectMode: GlassEffectMode,
    backdrop: LayerBackdrop?,
    modifier: Modifier = Modifier
) {
    val isPreview = androidx.compose.ui.platform.LocalInspectionMode.current
    val currentChapter = if (isPreview) {
        com.viel.aplayer.data.entity.ChapterEntity(
            id = "chapter_1",
            bookId = "book_1",
            bookFileId = "file_1",
            index = 1,
            title = "第一章：危机纪元",
            startPositionMs = 0L,
            durationMs = 360000L,
            fileOffsetMs = 0L,
            source = "EMBEDDED"
        )
    } else {
        viewModel.currentChapterState.collectAsStateWithLifecycle().value
    }
    ChapterDisplay(
        currentChapterTitle = currentChapter?.title ?: metadata.title,
        onChapterClick = actions.content.onShowChapterList,
        onBookmarkClick = actions.bookmarks.onShowDialog,
        glassEffectMode = glassEffectMode,
        backdrop = backdrop,
        modifier = modifier
    )
}

@Composable
fun ChapterDisplay(
    currentChapterTitle: String?,
    onChapterClick: () -> Unit,
    onBookmarkClick: () -> Unit,
    modifier: Modifier = Modifier,
    title: String? = null,
    glassEffectMode: GlassEffectMode = GlassEffectMode.Material,
    backdrop: LayerBackdrop? = null
) {
    // 为每一次改动添加详尽的中文注释：
    // 为每一次改动添加详尽的中文注释：全屏播放器背景已具有大半径 (64.dp) 的高阶氛围模糊，因此前景卡片在 miuix-blur 模式下无需重复采样高斯模糊。
    // 我们直接开启 isBlur 蒙版绘制，免去了 drawBackdrop 的高昂 GPU 计算，彻底避开 Vulkan Feedback Loop 崩溃温床。同时对齐 isBlur。
    val isBlur = glassEffectMode == GlassEffectMode.MiuixBlur

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (isBlur) {
            // 为每一次改动添加详尽的中文注释：
            // 彻底弃用任何 Material 3 高度封装交互容器（如 SuggestionChip 或 Surface），
            // 直接采用最底层的纯净 Box 容器，并按照极其规范的 Modifier 链排列：
            // Modifier.clip -> background(极透底色) -> background(物理反光) -> clickable -> border(折射描边) -> padding
            // 这能够强制所有的测量边界、水波纹波澜、乳白底色和极细白透描边百分之百完美地基于同心 chipShape 进行同心绘制。
            // 从而在任何短字长（如单个字符 "3"）或极端宽度下，实现像素级绝对尺寸自适应，彻底根治内外双重圆角边框嵌套与尺寸截断冲突的严重视觉 Bug。
            val chipShape = RoundedCornerShape(12.dp)
            // 为每一次改动添加详尽的中文注释：通过 local state 获取系统是否为深色模式，以精准施加自适应对比度蒙版与轮廓银丝描边，完全摆脱对外部描边预设的物理耦合。
            val isDark = androidx.compose.foundation.isSystemInDarkTheme()
            
            // 为每一次改动添加详尽的中文注释：
            // 1. 极致清透的高透明度自适应磨砂底色 (Mask Brush)，深色使用 [0.08f -> 0.03f] 的微白透亮层，浅色使用 [0.60f -> 0.35f] 的高雅乳白渐变，彻底保障文字清晰度的同时消灭背景发灰。
            val maskBrush = Brush.linearGradient(
                colors = if (isDark) {
                    listOf(Color.White.copy(alpha = 0.08f), Color.White.copy(alpha = 0.03f))
                } else {
                    listOf(Color.White.copy(alpha = 0.60f), Color.White.copy(alpha = 0.35f))
                }
            )
            
            Box(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .clip(chipShape)
                    .background(maskBrush)
                    // 为每一次改动添加详尽的中文注释：
                    // 2. 链式覆盖高光斜向白色物理扫掠折射层 (Specular Glare)，赋予药丸微缩水滴的剔透立体感与光亮厚度。
                    .background(
                        brush = Brush.linearGradient(
                            colors = listOf(
                                Color.White.copy(alpha = 0.10f),
                                Color.White.copy(alpha = 0.02f),
                                Color.Transparent,
                                Color.White.copy(alpha = 0.05f)
                            )
                        ),
                        shape = chipShape
                    )
                    .clickable(onClick = onChapterClick)
                    // 为每一次改动添加详尽的中文注释：
                    // 3. 链式添加 0.8.dp 极致精细的“微光折射渐变描边 (Refraction Edge)”，防止在大面积杂色背景上边缘发生粘连，与整体液态玻璃对齐。
                    .border(
                        width = 0.8.dp,
                        brush = Brush.linearGradient(
                            colors = if (isDark) {
                                listOf(
                                    Color.White.copy(alpha = 0.18f),
                                    Color.White.copy(alpha = 0.02f),
                                    Color.Transparent,
                                    Color.White.copy(alpha = 0.08f)
                                )
                            } else {
                                listOf(
                                    Color.White.copy(alpha = 0.45f),
                                    Color.White.copy(alpha = 0.10f),
                                    Color.Transparent,
                                    Color.White.copy(alpha = 0.25f)
                                )
                            }
                        ),
                        shape = chipShape
                    )
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.List,
                        contentDescription = null,
                        modifier = Modifier.size(SuggestionChipDefaults.IconSize),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = currentChapterTitle ?: title ?: "No Chapters",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        } else {
            // 为每一次改动添加详尽的中文注释：Material 模式下维持原有高度封装的 Material 3 实色 SuggestionChip，完美向下兼容
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
        }

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
fun ChapterDisplayStatefulPreview() {
    APlayerTheme {
        Surface {
            ChapterDisplayStateful(
                viewModel = PlayerViewModel(),
                metadata = BookMetadataState(title = "三体"),
                actions = PlayerActions(),
                glassEffectMode = GlassEffectMode.Material,
                backdrop = rememberLayerBackdrop(),
                modifier = Modifier.padding(16.dp)
            )
        }
    }
}

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