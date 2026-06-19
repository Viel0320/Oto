package com.viel.aplayer.ui.detail.layouts

// Setup Haze Integration (Import dev.chrisbanes.haze libraries) Import HazeState class for layouts.
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.viel.aplayer.R
import com.viel.aplayer.application.library.detail.DetailBookItem
import com.viel.aplayer.shared.settings.GlassEffectMode
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
 * Portrait Layout Specification (Responsive vertical stack for compact viewports)
 *
 * Implements a standard vertically scrolling list layout.
 * Uses scaffold padding only for the top app bar offset and applies safe-drawing
 * side/bottom insets locally to keep the scroll content away from system bars.
 */
@Composable
fun DetailPortrait(
    book: DetailBookItem?,
    uiState: DetailUiState,
    padding: PaddingValues,
    safeDrawingPadding: PaddingValues,
    glassEffectMode: GlassEffectMode,
    detailHazeState: HazeState,
    onPlayPressed: () -> Unit,
    onPlayClick: () -> Unit,
    onSearchClick: (String) -> Unit,
    onShowInfo: (String, String) -> Unit,
    modifier: Modifier = Modifier
) {
    val titleInfoDialogLabel = stringResource(R.string.title_label)
    val authorInfoDialogLabel = stringResource(R.string.author_label)
    val narratorInfoDialogLabel = stringResource(R.string.narrator_label)
    val home2DetailTargetScope = LocalHomeRecent2DetailTargetScope.current
    val windowClass = LocalAppWindowSizeClass.current
    /*
     * Detail Cover Motion Channel (Entry-source based target binding)
     *
     * Selects the matching shared-element key only for the opening source so Recent, List,
     * and Search thumbnails cannot bind to the same Detail cover at the same time.
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
     * Detail Cover Source Corner (Entry-source shape alignment)
     *
     * Matches Detail's first transition frame to the real source thumbnail shape: 16.dp for
     * Recent cards, 8.dp for main-list and Search thumbnails, and no override without a source.
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

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(
                start = safeDrawingPadding.calculateStartPadding(LocalLayoutDirection.current) + windowClass.screenHorizontalPadding,
                end = safeDrawingPadding.calculateEndPadding(LocalLayoutDirection.current) + windowClass.screenHorizontalPadding,
                top = padding.calculateTopPadding()
            ),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Portrait Cover Adaptive Slot (Constrain cover by window width class instead of phone-only assumptions)
        // DetailPortrait can run on phones, foldables, and portrait tablets, so the parent slot computes a bounded square target while PlayerCover remains unchanged.
        BoxWithConstraints(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            // Portrait Cover Size Policy (Use one calm width ratio across portrait form factors)
            // A uniform 60% slot keeps phones, foldables, and portrait tablets visually consistent while max-size caps still prevent large screens from dominating the page.
            val coverWidthFraction = 0.6f

            val coverSlotSize = (maxWidth * coverWidthFraction)

            // Primary Cover (Renders the album cover with adaptive portrait constraints)
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
                 * Detail Cover Shared Element Key (Destination artwork endpoint)
                 *
                 * Binds the portrait detail cover to the same Home recent-card key so the selected
                 * artwork morphs across the overlay boundary instead of fading independently.
                */
                sharedElementKey = detailSharedElementKey,
                sharedElementVisibilityScope = detailSharedElementVisibilityScope,
                /*
                 * Detail Cover Source Corner (Home recent card shape alignment)
                 *
                 * Matches the selected recent-card cover radius so the target cover does not flash
                 * through the mini-player's 8.dp start radius on the first overlay frame.
                */
                sharedElementStartCornerRadius = detailSharedElementStartCornerRadius,
                modifier = Modifier.size(coverSlotSize)
            )
        }

        // Metadata Area (Displays title, author, and narrator details)
        DetailHeader(
            title = book?.title ?: "",
            author = book?.author ?: "",
            narrator = book?.narrator ?: "",
            onTitleLongClick = {
                if (!book?.title.isNullOrBlank() && !book.title.equals("unknown", true)) {
                    onShowInfo(titleInfoDialogLabel, book.title)
                }
            },
            onAuthorClick = {
                book?.author?.takeIf { it.isNotBlank() && !it.equals("unknown", true) }
                    ?.let { onSearchClick("Author:$it ") }
            },
            onAuthorLongClick = {
                if (!book?.author.isNullOrBlank() && !book.author.equals("unknown", true)) {
                    onShowInfo(authorInfoDialogLabel, book.author)
                }
            },
            onNarratorClick = {
                book?.narrator?.takeIf { it.isNotBlank() && !it.equals("unknown", true) }
                    ?.let { onSearchClick("Narrator:$it ") }
            },
            onNarratorLongClick = {
                if (!book?.narrator.isNullOrBlank() && !book.narrator.equals("unknown", true)) {
                    onShowInfo(narratorInfoDialogLabel, book.narrator)
                }
            },
            // DetailPortrait Reversion (Remove series parameter pass per user instruction)
            // Reverts series visualization to align with design decision of not displaying series on the details page.
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
            isLandscape = false
        )

        if (uiState.fullSourcePath.isEmpty()) {
            Spacer(modifier = Modifier.height(16.dp))
        }

        // Synopsis Segment (Renders the text description of the audiobook)
        DetailSummary(
            description = book?.description ?: ""
        )

        Spacer(modifier = Modifier.height(100.dp + safeDrawingPadding.calculateBottomPadding()))
    }
}
