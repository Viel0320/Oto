package com.viel.aplayer.ui.detail.layouts

// Setup Haze Integration (Import dev.chrisbanes.haze libraries) Import HazeState class for layouts.
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
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
 * Tablet Landscape Layout Specification (Responsive dual-pane for large/foldable landscape viewports)
 *
 * Designed to show a dual-column configuration:
 * Left column features the fixed cover image, book metadata, and playback controls.
 * Right column displays the scrollable synopsis details.
 */
@Composable
fun DetailLandscapeTablet(
    book: DetailBookItem?, // The Room-free detail metadata item.
    uiState: DetailUiState, // The UI state model.
    padding: PaddingValues, // Inner padding for Scaffold.
    safeDrawingPadding: PaddingValues, // The physical safe drawing area.
    glassEffectMode: GlassEffectMode, // Selected glass effect mode.
    // Setup HazeState Parameter (Map detailBackdrop parameter to HazeState) Changed LayerBackdrop to HazeState.
    detailHazeState: HazeState,
    onPlayPressed: () -> Unit, // Playback trigger debounce callback.
    onPlayClick: () -> Unit, // Confirm playback action callback.
    onSearchClick: (String) -> Unit, // Search callback.
    onShowInfo: (String, String) -> Unit, // Dialog detail display callback.
    modifier: Modifier = Modifier
) {
    // Tablet Detail Item (Render from the scene projection instead of a database entity)
    // Wide layouts need the same metadata as phones without expanding the Detail UI boundary back to Room.
    val windowClass = LocalAppWindowSizeClass.current
    val layoutDirection = LocalLayoutDirection.current
    val home2DetailTargetScope = LocalHomeRecent2DetailTargetScope.current
    /*
     * Tablet Detail Motion Channel (Entry-source based target binding)
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
     * Tablet Detail Source Corner (Entry-source shape alignment)
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
    // Title: Standardize landscape tablet detail layout dimensions (Use screenHorizontalPadding and fixed spacing)
    // Avoids dynamic screen width calculations scaling paddings and spaces inconsistently on wide displays.
    val sidePadding = windowClass.screenHorizontalPadding
    val startPadding = sidePadding + safeDrawingPadding.calculateStartPadding(layoutDirection)
    val endPadding = sidePadding + safeDrawingPadding.calculateEndPadding(layoutDirection)
    val topSpacerHeight = padding.calculateTopPadding()

    Row(
        modifier = modifier
            .fillMaxSize()
            .padding(start = startPadding, end = endPadding),
        horizontalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        // Left Column: Features album cover, book details, and primary controls
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
                    sizeRatio = 1f,
                    /*
                     * Tablet Detail Cover Shared Element Key (Destination artwork endpoint)
                     *
                     * Aligns the tablet detail cover with the Home recent-card artwork key so the
                     * shared-element transition remains available in wide split detail layouts.
                    */
                    sharedElementKey = detailSharedElementKey,
                    sharedElementVisibilityScope = detailSharedElementVisibilityScope,
                    /*
                     * Tablet Detail Source Corner (Home recent card shape alignment)
                     *
                     * Matches the selected recent-card cover radius so the target cover does not
                     * begin from the mini-player's 8.dp playback radius during overlay entry.
                    */
                    sharedElementStartCornerRadius = detailSharedElementStartCornerRadius,
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
                // DetailLandscapeTablet Reversion (Remove series parameter pass per user instruction)
                // Reverts series visualization to align with design decision of not displaying series on the details page.
                isLandscape = true
            )

            Spacer(modifier = Modifier.height(16.dp))
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
            Spacer(modifier = Modifier.height(padding.calculateBottomPadding()))
        }

        // Right Column: Displays the scrollable audiobook synopsis
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
