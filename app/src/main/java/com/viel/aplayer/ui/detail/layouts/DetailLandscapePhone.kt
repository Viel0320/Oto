package com.viel.aplayer.ui.detail.layouts

// Setup Haze Integration (Import dev.chrisbanes.haze libraries) Import HazeState class for layouts.
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
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.dp
import com.viel.aplayer.data.entity.BookEntity
import com.viel.aplayer.data.store.GlassEffectMode
import com.viel.aplayer.ui.common.CoverImageSourceSelector
import com.viel.aplayer.ui.common.PlayerCover
import com.viel.aplayer.ui.common.theme.LocalWindowClass
import com.viel.aplayer.ui.detail.DetailUiState
import com.viel.aplayer.ui.detail.components.DetailControlPanel
import com.viel.aplayer.ui.detail.components.DetailHeader
import com.viel.aplayer.ui.detail.components.DetailSummary
import dev.chrisbanes.haze.HazeState

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
    // Setup HazeState Parameter (Map detailBackdrop parameter to HazeState) Changed LayerBackdrop to HazeState.
    detailHazeState: HazeState,
    onPlayPressed: () -> Unit,
    onPlayClick: () -> Unit,
    onSearchClick: (String) -> Unit,
    onShowInfo: (String, String) -> Unit,
    modifier: Modifier = Modifier
) {
    val windowClass = LocalWindowClass.current
    val layoutDirection = LocalLayoutDirection.current
    val screenWidthDp = windowClass.screenWidthDp
    
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

            // Setup DetailControlPanel Haze State (Link hazeState) Passed detailHazeState parameter to control panel.
            DetailControlPanel(
                book = book,
                uiState = uiState,
                glassEffectMode = glassEffectMode,
                hazeState = detailHazeState,
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
