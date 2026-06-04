package com.viel.aplayer.ui.detail.layouts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.viel.aplayer.data.entity.BookEntity
import com.viel.aplayer.data.store.GlassEffectMode
import com.viel.aplayer.ui.common.CoverImageSourceSelector
import com.viel.aplayer.ui.common.PlayerCover
import com.viel.aplayer.ui.common.theme.APlayerTheme
import com.viel.aplayer.ui.common.theme.LocalWindowClass
import com.viel.aplayer.ui.common.theme.WindowClass
import com.viel.aplayer.ui.detail.DetailUiState
import com.viel.aplayer.ui.detail.components.DetailControlPanel
import com.viel.aplayer.ui.detail.components.DetailHeader
import com.viel.aplayer.ui.detail.components.DetailSummary
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop

/**
 * Phone Landscape Layout Specification (Optimized horizontal splitting for compact screens)
 *
 * Caters to viewports with restricted vertical heights by dividing content left and right.
 * Places the playback controls at the top of the right-hand column.
 */
@Composable
fun DetailLandscapePhone(
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
    // Adaptive Parameter Extraction (Retrieve logical width from LocalWindowClass)
    // Accesses logical screen width through the global `LocalWindowClass` composition local.
    // This avoids querying the platform config directly, improving component encapsulation.
    val windowClass = LocalWindowClass.current
    val layoutDirection = LocalLayoutDirection.current
    val screenWidthDp = windowClass.screenWidthDp
    
    // Spacing Configuration (Adjust padding weights for phone landscape mode)
    val sidePadding = screenWidthDp * 0.04f
    val startPadding = sidePadding + safeDrawingPadding.calculateStartPadding(layoutDirection)
    val endPadding = sidePadding + safeDrawingPadding.calculateEndPadding(layoutDirection)
    val topSpacerHeight = padding.calculateTopPadding() / 2

    Row(
        modifier = modifier
            .fillMaxSize()
            .padding(start = startPadding, end = endPadding),
        horizontalArrangement = Arrangement.spacedBy(screenWidthDp * 0.06f)
    ) {
        // Left Column: Displays the primary cover and metadata text
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
                // Cover Selection Priority (Prioritize high-definition raw images for primary detail viewport)
                // The landscape detail view renders a large cover surface. Consequently, it shares the "Main1200" priority logic
                // to load the raw image, preventing resolution degradation on wider screens.
                coverPath = CoverImageSourceSelector.main(
                    coverPath = book?.coverPath,
                    thumbnailPath = book?.thumbnailPath
                ),
                isPlaying = false,
                coverLastUpdated = book?.lastScannedAt ?: 0L,
                coverScene = "detail-main-cover",
                onAdjustVolume = {},
                onNextChapter = {},
                onPreviousChapter = {},
                sizeRatio = 1f,
                gesturesEnabled = false,
                modifier = Modifier.fillMaxWidth()
            )}

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
            Spacer(modifier = Modifier.height(padding.calculateBottomPadding()))
        }

        // Right Column: Houses the playback action panel and scrollable synopsis text
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            Spacer(modifier = Modifier.height(topSpacerHeight))

            DetailControlPanel(
                book = book,
                uiState = uiState,
                glassEffectMode = glassEffectMode,
                backdrop = detailBackdrop,
                onPlayPressed = onPlayPressed,
                onPlayClick = onPlayClick,
                isLandscape = true
            )
            
            Spacer(modifier = Modifier.height(24.dp))

            DetailSummary(description = book?.description ?: "")
            
            Spacer(modifier = Modifier.height(padding.calculateBottomPadding()))
        }
    }
}

@Preview(showBackground = true, apiLevel = 36, widthDp = 720, heightDp = 360)
@Composable
fun DetailLandscapePhonePreview() {
    APlayerTheme {
        // Preview Window Configuration (Verify landscape phone rendering)
        CompositionLocalProvider(
            LocalWindowClass provides WindowClass.LandscapePhone
        ) {
            Surface {
                DetailLandscapePhone(
                    book = BookEntity(id = "1", rootId = "root", sourceType = "LOCAL", title = "三体", author = "刘慈欣", narrator = "王明", description = "这是一部科幻巨著。"),
                    uiState = DetailUiState(),
                    padding = PaddingValues(16.dp),
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
}
