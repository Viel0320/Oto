package com.viel.aplayer.ui.detail.layouts

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
 * Responsive vertical stack for compact viewports.
 *
 * Implements a standard vertically scrolling list layout.
 * Uses scaffold padding only for the top app bar offset and applies safe-drawing
 * side/bottom insets locally to keep the scroll content away from system bars.
 * Delegates the compact cache shortcut to DetailControlPanel so playback-adjacent
 * availability actions stay with the selected book controls.
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
    onDownloadActionClick: () -> Unit,
    onSearchClick: (String) -> Unit,
    onShowInfo: (String, String) -> Unit,
    modifier: Modifier = Modifier
) {
    val titleInfoDialogLabel = stringResource(R.string.title_label)
    val authorInfoDialogLabel = stringResource(R.string.author_label)
    val narratorInfoDialogLabel = stringResource(R.string.narrator_label)
    val home2DetailTargetScope = LocalHomeRecent2DetailTargetScope.current
    val windowClass = LocalAppWindowSizeClass.current
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
        BoxWithConstraints(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            val coverWidthFraction = 0.6f

            val coverSlotSize = (maxWidth * coverWidthFraction)

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
                modifier = Modifier.size(coverSlotSize)
            )
        }

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
            isLandscape = false
        )

        DetailControlPanel(
            book = book,
            uiState = uiState,
            glassEffectMode = glassEffectMode,
            hazeState = detailHazeState,
            onPlayPressed = onPlayPressed,
            onPlayClick = onPlayClick,
            onDownloadActionClick = onDownloadActionClick,
            isLandscape = false
        )


        Spacer(modifier = Modifier.height(16.dp))

        DetailSummary(
            description = book?.description ?: ""
        )

        Spacer(modifier = Modifier.height(100.dp + safeDrawingPadding.calculateBottomPadding()))
    }
}
