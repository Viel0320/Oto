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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.dp
import com.viel.aplayer.application.library.detail.DetailBookItem
import com.viel.aplayer.data.store.GlassEffectMode
import com.viel.aplayer.ui.common.CoverImageSourceSelector
import com.viel.aplayer.ui.common.PlayerCover
import com.viel.aplayer.ui.common.layout.LocalAppWindowSizeClass
import com.viel.aplayer.ui.detail.DetailEntrySource
import com.viel.aplayer.ui.detail.DetailUiState
import com.viel.aplayer.ui.detail.components.DetailControlPanel
import com.viel.aplayer.ui.detail.components.DetailHeader
import com.viel.aplayer.ui.detail.components.DetailSummary
import com.viel.aplayer.ui.motion.LocalHomeRecent2DetailTargetScope
import com.viel.aplayer.ui.motion.SharedElementKeys
import dev.chrisbanes.haze.HazeState

/**
 * Phone Landscape Layout Specification (Optimized horizontal splitting for compact screens)
 *
 * Caters to viewports with restricted vertical heights by dividing content left and right.
 * Places the playback controls at the top of the right-hand column.
 */
@Composable
fun DetailLandscapePhone(
    book: DetailBookItem?,
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
    // Color Extracted Callback (Pass color callback to downstream PlayerCover)
    // Invoked when Coil successfully decodes the Bitmap cover and retrieves its dominant color.
    onColorExtracted: (Color) -> Unit,
    modifier: Modifier = Modifier
) {
    // Landscape Detail Item (Render from the scene projection instead of a database entity)
    // The compact split layout only needs Detail-owned metadata and cover fields for rendering.
    val windowClass = LocalAppWindowSizeClass.current
    val layoutDirection = LocalLayoutDirection.current
    val home2DetailTargetScope = LocalHomeRecent2DetailTargetScope.current
    /*
     * Landscape Detail Motion Channel (Entry-source based target binding)
     *
     * Selects the matching shared-element key only for the opening source, keeping Recent,
     * main-list, and Search thumbnails on separate transition channels.
     */
    val detailSharedElementKey = book?.id?.let { bookId ->
        when (uiState.entrySource) {
            DetailEntrySource.HomeRecent -> SharedElementKeys.home2DetailCover(bookId)
            DetailEntrySource.HomeList -> SharedElementKeys.homeList2DetailCover(bookId)
            DetailEntrySource.Search -> SharedElementKeys.search2detailCover(bookId)
            DetailEntrySource.None -> null
        }
    }
    /*
     * Landscape Detail Source Corner (Entry-source shape alignment)
     *
     * Uses 16.dp for Recent card entries and 8.dp for main-list/Search thumbnail entries so
     * the first Detail frame matches the real source instead of a reused player shape.
     */
    val detailSharedElementStartCornerRadius = when (uiState.entrySource) {
        DetailEntrySource.HomeRecent -> 16.dp
        DetailEntrySource.HomeList -> 8.dp
        DetailEntrySource.Search -> 8.dp
        DetailEntrySource.None -> null
    }
    val detailSharedElementVisibilityScope = if (detailSharedElementKey != null) {
        home2DetailTargetScope
    } else {
        null
    }
    // Title: Standardize landscape phone detail layout dimensions (Use screenHorizontalPadding and fixed spacing)
    // Avoids dynamic screen width calculations scaling paddings and spaces inconsistently on wide displays.
    val sidePadding = windowClass.screenHorizontalPadding
    val startPadding = sidePadding + safeDrawingPadding.calculateStartPadding(layoutDirection)
    val endPadding = sidePadding + safeDrawingPadding.calculateEndPadding(layoutDirection)
    val topSpacerHeight = padding.calculateTopPadding() / 2

    Row(
        modifier = modifier
            .fillMaxSize()
            .padding(start = startPadding, end = endPadding),
        horizontalArrangement = Arrangement.spacedBy(24.dp)
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
                    bookId = book?.id ?: "",
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
                    /*
                     * Landscape Detail Cover Shared Element Key (Destination artwork endpoint)
                     *
                     * Keeps the landscape phone cover on the same Home recent-card motion key,
                     * preserving the cover morph when the layout switches to the split detail view.
                    */
                    sharedElementKey = detailSharedElementKey,
                    sharedElementVisibilityScope = detailSharedElementVisibilityScope,
                    /*
                     * Landscape Detail Source Corner (Home recent card shape alignment)
                     *
                     * Matches the selected recent-card cover radius so the target cover does not
                     * begin from the mini-player's 8.dp playback radius during overlay entry.
                     */
                    sharedElementStartCornerRadius = detailSharedElementStartCornerRadius,
                    onColorExtracted = onColorExtracted,
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
                // DetailLandscapePhone Reversion (Remove series parameter pass per user instruction)
                // Reverts series visualization to align with design decision of not displaying series on the details page.
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
                isLandscape = true,
            )
            
            Spacer(modifier = Modifier.height(24.dp))

            DetailSummary(description = book?.description ?: "")
            
            Spacer(modifier = Modifier.height(padding.calculateBottomPadding()))
        }
    }
}
