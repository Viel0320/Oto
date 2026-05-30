package com.viel.aplayer.ui.detail.layouts

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
 * 竖屏自适应布局 (Compact)
 * 采用经典的垂直滚动流式布局。
 */
@Composable
fun DetailPortrait(
    book: BookEntity?,
    uiState: DetailUiState,
    padding: PaddingValues,
    glassEffectMode: GlassEffectMode,
    detailBackdrop: LayerBackdrop,
    onPlayPressed: () -> Unit,
    onPlayClick: () -> Unit,
    onSearchClick: (String) -> Unit,
    onShowInfo: (String, String) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(padding),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // 封面：占据较大比例
        PlayerCover(
            coverPath = book?.coverPath,
            isPlaying = false,
            coverLastUpdated = book?.lastScannedAt ?: 0L,
            onAdjustVolume = {},
            onNextChapter = {},
            onPreviousChapter = {},
            sizeRatio = 0.9f,
            gesturesEnabled = false,
            modifier = Modifier.fillMaxWidth().aspectRatio(1f)
        )

        // 标题与作者
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
            modifier = Modifier.padding(horizontal = 24.dp),
            isLandscape = false
        )

        // 控制面板
        DetailControlPanel(
            book = book,
            uiState = uiState,
            glassEffectMode = glassEffectMode,
            backdrop = detailBackdrop,
            onPlayPressed = onPlayPressed,
            onPlayClick = onPlayClick,
            isLandscape = false,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp)
        )

        if (uiState.fullSourcePath.isEmpty()) {
            Spacer(modifier = Modifier.height(16.dp))
        }

        // 简介内容
        DetailSummary(
            description = book?.description ?: "",
            modifier = Modifier.padding(horizontal = 24.dp)
        )

        Spacer(modifier = Modifier.height(100.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()))
    }
}

@Preview(showBackground = true, apiLevel = 36)
@Composable
fun DetailPortraitPreview() {
    APlayerTheme {
        Surface {
            DetailPortrait(
                book = BookEntity(id = "1", rootId = "root", sourceType = "LOCAL", title = "三体", author = "刘慈欣", narrator = "王明", description = "这是一部科幻巨著。"),
                uiState = DetailUiState(),
                padding = PaddingValues(0.dp),
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
