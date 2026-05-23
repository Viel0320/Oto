package com.viel.aplayer.ui.detail.layouts

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.dp
import com.viel.aplayer.data.entity.BookEntity
import com.viel.aplayer.data.store.GlassEffectMode
import com.viel.aplayer.ui.detail.DetailUiState
import com.viel.aplayer.ui.detail.components.DetailControlPanel
import com.viel.aplayer.ui.detail.components.DetailHeader
import com.viel.aplayer.ui.detail.components.DetailSummary
import com.viel.aplayer.ui.player.components.PlayerCover
import top.yukonga.miuix.kmp.blur.LayerBackdrop

/**
 * 平板/大屏自适应布局 (Tablets / Medium-Expanded)
 * 采用经典的双栏设计，左侧为固定书籍元数据与操作区，右侧为简介。
 */
@Composable
fun DetailTablet(
    book: BookEntity?,
    uiState: DetailUiState,
    padding: PaddingValues,
    safeDrawingPadding: PaddingValues,
    glassEffectMode: GlassEffectMode,
    detailBackdrop: LayerBackdrop,
    onPlayPressed: () -> Unit,
    onPlayClick: () -> Unit,
    onSearchClick: (String) -> Unit,
    onShowInfo: (String, String) -> Unit,
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
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(topSpacerHeight))
            PlayerCover(
                coverPath = book?.coverPath,
                isPlaying = false,
                coverLastUpdated = book?.lastScannedAt ?: 0L,
                onAdjustVolume = {},
                onNextChapter = {},
                onPreviousChapter = {},
                sizeRatio = 0.7f,
                gesturesEnabled = false,
                modifier = Modifier.fillMaxWidth().aspectRatio(1f)
            )

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
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            Spacer(modifier = Modifier.height(topSpacerHeight))
            DetailSummary(description = book?.description ?: "")
            Spacer(modifier = Modifier.height(padding.calculateBottomPadding()))
        }
    }
}
