package com.viel.aplayer.ui.detail.layouts

// Setup Haze Integration (Import dev.chrisbanes.haze libraries) Import HazeState class for layouts.
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
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.viel.aplayer.data.entity.BookEntity
import com.viel.aplayer.data.store.GlassEffectMode
import com.viel.aplayer.ui.common.CoverImageSourceSelector
import com.viel.aplayer.ui.common.PlayerCover
import com.viel.aplayer.ui.detail.DetailUiState
import com.viel.aplayer.ui.detail.components.DetailControlPanel
import com.viel.aplayer.ui.detail.components.DetailHeader
import com.viel.aplayer.ui.detail.components.DetailSummary
import dev.chrisbanes.haze.HazeState

/**
 * Portrait Layout Specification (Responsive vertical stack for compact viewports)
 *
 * Implements a standard vertically scrolling list layout.
 */
@Composable
fun DetailPortrait(
    book: BookEntity?,
    uiState: DetailUiState,
    padding: PaddingValues,
    glassEffectMode: GlassEffectMode,
    // Setup HazeState Parameter (Map detailBackdrop parameter to HazeState) Changed LayerBackdrop to HazeState.
    detailHazeState: HazeState,
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
        // Primary Cover (Renders the album cover with dominant ratio)
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
            sizeRatio = 0.9f,
            gesturesEnabled = false,
            modifier = Modifier.fillMaxWidth().aspectRatio(1f)
        )

        // Metadata Area (Displays title, author, and narrator details)
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

        // Playback Panel (Renders the primary play, progress, and settings controls)
        // Setup DetailControlPanel Haze State (Link hazeState) Passed detailHazeState parameter to control panel.
        DetailControlPanel(
            book = book,
            uiState = uiState,
            glassEffectMode = glassEffectMode,
            hazeState = detailHazeState,
            onPlayPressed = onPlayPressed,
            onPlayClick = onPlayClick,
            isLandscape = false,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp)
        )

        if (uiState.fullSourcePath.isEmpty()) {
            Spacer(modifier = Modifier.height(16.dp))
        }

        // Synopsis Segment (Renders the text description of the audiobook)
        DetailSummary(
            description = book?.description ?: "",
            modifier = Modifier.padding(horizontal = 24.dp)
        )

        Spacer(modifier = Modifier.height(100.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()))
    }
}
