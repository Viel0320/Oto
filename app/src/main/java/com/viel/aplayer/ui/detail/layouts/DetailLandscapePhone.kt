package com.viel.aplayer.ui.detail.layouts

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
 * Optimized horizontal splitting for compact screens.
 *
 * Caters to viewports with restricted vertical heights by dividing content left and right.
 * Places the playback controls at the top of the right-hand column.
 * Uses scaffold padding only for top-bar spacing and safe-drawing padding for the
 * physical horizontal and bottom edges that can contain navigation or cutout areas.
 */
@Composable
fun DetailLandscapePhone(
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
    val windowClass = LocalAppWindowSizeClass.current
    val home2DetailTargetScope = LocalHomeRecent2DetailTargetScope.current

    val detailSharedElementKey = book?.id?.let { bookId ->
        when (uiState.entrySource) {
            DetailEntrySource.HomeRecent -> SharedElementKeys.home2DetailCover(bookId)
            DetailEntrySource.HomeList -> SharedElementKeys.homeList2DetailCover(bookId)
            DetailEntrySource.Search -> SharedElementKeys.search2detailCover(bookId)
            DetailEntrySource.None -> null
        }
    }
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

    val topSpacerHeight = padding.calculateTopPadding() / 2

    Row(
        modifier = modifier
            .fillMaxSize()
            .padding(
                start = safeDrawingPadding.calculateStartPadding(LocalLayoutDirection.current),
                end = safeDrawingPadding.calculateEndPadding(LocalLayoutDirection.current)
            )
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .padding(horizontal = windowClass.screenHorizontalPadding),
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
                    sharedElementKey = detailSharedElementKey,
                    sharedElementVisibilityScope = detailSharedElementVisibilityScope,
                    sharedElementStartCornerRadius = detailSharedElementStartCornerRadius,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

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
                isLandscape = true
            )
            Spacer(modifier = Modifier.height(safeDrawingPadding.calculateBottomPadding()))
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .padding(horizontal = windowClass.screenHorizontalPadding)
                .verticalScroll(rememberScrollState())
        ) {
            Spacer(modifier = Modifier.height(topSpacerHeight))

            DetailControlPanel(
                book = book,
                uiState = uiState,
                glassEffectMode = glassEffectMode,
                hazeState = detailHazeState,
                onPlayPressed = onPlayPressed,
                onPlayClick = onPlayClick,
                isLandscape = true,
            )

            Spacer(modifier = Modifier.height(16.dp))

            DetailSummary(description = book?.description ?: "")

            Spacer(modifier = Modifier.height(100.dp + safeDrawingPadding.calculateBottomPadding()))
        }
    }
}
