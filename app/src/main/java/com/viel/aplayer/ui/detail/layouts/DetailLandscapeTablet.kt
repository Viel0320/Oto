package com.viel.aplayer.ui.detail.layouts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.viel.aplayer.data.entity.BookEntity
import com.viel.aplayer.data.store.GlassEffectMode
import com.viel.aplayer.ui.common.PlayerCover
import com.viel.aplayer.ui.detail.DetailUiState
import com.viel.aplayer.ui.detail.components.DetailControlPanel
import com.viel.aplayer.ui.detail.components.DetailHeader
import com.viel.aplayer.ui.detail.components.DetailSummary
import com.viel.aplayer.ui.theme.APlayerTheme
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop

/**
 * 平板/大屏横屏自适应布局 (DetailTabletLandscape)
 * 专门在平板横屏大屏幕或折叠屏展开的横屏状态下提供双栏排版：
 * 左侧为固定封面、元数据与操作播放控制区，右侧为详情简介。
 */
@Composable
fun DetailTabletLandscape(
    book: BookEntity?, // 书籍元数据实体
    uiState: DetailUiState, // UI状态模型
    padding: PaddingValues, // scaffold 内边距
    safeDrawingPadding: PaddingValues, // 物理安全区
    glassEffectMode: GlassEffectMode, // 玻璃视效选择模式
    detailBackdrop: LayerBackdrop, // 背景采样源
    onPlayPressed: () -> Unit, // 播放物理触发前防抖回调
    onPlayClick: () -> Unit, // 确认播放操作回调
    onSearchClick: (String) -> Unit, // 搜索回调
    onShowInfo: (String, String) -> Unit, // 弹窗详情展示回调
    modifier: Modifier = Modifier
) {
    val configuration = LocalConfiguration.current
    val layoutDirection = LocalLayoutDirection.current
    val screenWidthDp = configuration.screenWidthDp.dp
    
    val sidePadding = screenWidthDp * 0.04f
    val startPadding = sidePadding + safeDrawingPadding.calculateStartPadding(layoutDirection)
    val endPadding = sidePadding + safeDrawingPadding.calculateEndPadding(layoutDirection)
    val topSpacerHeight = padding.calculateTopPadding()

    Row(
        modifier = modifier
            .fillMaxSize()
            .padding(start = startPadding, end = endPadding),
        horizontalArrangement = Arrangement.spacedBy(screenWidthDp * 0.06f)
    ) {
        // 左栏：元数据 + 播放按钮
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(topSpacerHeight))
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                PlayerCover(
                    coverPath = book?.coverPath,
                    isPlaying = false,
                    coverLastUpdated = book?.lastScannedAt ?: 0L,
                    onAdjustVolume = {},
                    onNextChapter = {},
                    onPreviousChapter = {},
                    sizeRatio = 1f,
                    gesturesEnabled = false,
                    modifier = Modifier
                        .fillMaxHeight()
                        .aspectRatio(1f)
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            DetailHeader(
                title = book?.title ?: "",
                author = book?.author ?: "",
                narrator = book?.narrator ?: "",
                onAuthorClick = { 
                    book?.author?.takeIf { it.isNotBlank() && !it.equals("unknown", true) }?.let { onSearchClick("Author:$it ") } 
                },
                onAuthorLongClick = { 
                    if (!book?.author.isNullOrBlank() && !book.author.equals("unknown", true)) {
                        onShowInfo("Author", book.author)
                    }
                },
                onNarratorClick = {
                    book?.narrator?.takeIf { it.isNotBlank() && !it.equals("unknown", true) }?.let { onSearchClick("Narrator:$it ") } 
                },
                onNarratorLongClick = { 
                    if (!book?.narrator.isNullOrBlank() && !book.narrator.equals("unknown", true)) {
                        onShowInfo("Narrator", book.narrator)
                    }
                },
                isLandscape = true
            )

            Spacer(modifier = Modifier.height(16.dp))
            DetailControlPanel(
                book = book,
                uiState = uiState,
                glassEffectMode = glassEffectMode,
                backdrop = detailBackdrop,
                onPlayPressed = onPlayPressed,
                onPlayClick = onPlayClick,
                isLandscape = true
            )
            Spacer(modifier = Modifier.height(padding.calculateBottomPadding()))
        }

        // 右栏：概要简介
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            Spacer(modifier = Modifier.height(topSpacerHeight))
            DetailSummary(
                description = book?.description ?: "",
                isScrollable = true,
                modifier = Modifier.weight(1f)
            )
            Spacer(modifier = Modifier.height(padding.calculateBottomPadding()))
        }
    }
}

@Preview(showBackground = true, apiLevel = 36, widthDp = 1280, heightDp = 800)
@Composable
fun DetailTabletLandscapePreview() {
    APlayerTheme {
        Surface {
            DetailTabletLandscape(
                book = BookEntity(id = "1", rootId = "root", sourceType = "LOCAL", title = "三体", author = "刘慈欣", narrator = "王明", description = "这是一部科幻巨著。"),
                uiState = DetailUiState(),
                padding = PaddingValues(24.dp),
                safeDrawingPadding = PaddingValues(0.dp),
                glassEffectMode = GlassEffectMode.Material,
                detailBackdrop = rememberLayerBackdrop(),
                onPlayPressed = {},
                onPlayClick = {},
                onSearchClick = {},
                onShowInfo = { _, _ -> }
            )
        }
    }
}
