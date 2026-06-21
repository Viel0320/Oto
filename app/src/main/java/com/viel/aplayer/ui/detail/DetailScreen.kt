package com.viel.aplayer.ui.detail

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.viel.aplayer.application.library.LibraryReadStatus
import com.viel.aplayer.shared.settings.GlassEffectMode
import dev.chrisbanes.haze.HazeState

/**
 * L2 Container Component.
 *
 * L2 container level component DetailScreen.
 * Serves purely as a controller for state transmission and event bridging, containing no direct visual rendering logic.
 */
@Composable
fun DetailScreen(
    uiState: DetailUiState,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier,
    onPlayPressed: () -> Unit = {},
    onPlayClick: () -> Unit = {},
    onSearchClick: (String) -> Unit = {},
    onEditBook: (String) -> Unit = {},
    onUpdateReadStatus: (String, LibraryReadStatus) -> Unit = { _, _ -> },
    onForceRegenerate: (String) -> Unit = {},
    onDeleteBook: (String) -> Unit = {},
    onDownloadBook: (String) -> Unit = {},
    onPauseDownload: (String) -> Unit = {},
    onResumeDownload: (String) -> Unit = {},
    onDeleteDownload: (String) -> Unit = {},
    glassEffectMode: GlassEffectMode,
    fullPageHazeState: HazeState? = null,
    coverColor: androidx.compose.ui.graphics.Color?,
    onColorExtracted: (androidx.compose.ui.graphics.Color) -> Unit,
) {
    DetailContent(
        uiState = uiState,
        onBackClick = onBackClick,
        modifier = modifier,
        onPlayPressed = onPlayPressed,
        onPlayClick = onPlayClick,
        onSearchClick = onSearchClick,
        onEditBook = onEditBook,
        onUpdateReadStatus = onUpdateReadStatus,
        onForceRegenerate = onForceRegenerate,
        onDeleteBook = onDeleteBook,
        onDownloadBook = onDownloadBook,
        onPauseDownload = onPauseDownload,
        onResumeDownload = onResumeDownload,
        onDeleteDownload = onDeleteDownload,
        glassEffectMode = glassEffectMode,
        fullPageHazeState = fullPageHazeState,
        coverColor = coverColor,
        onColorExtracted = onColorExtracted
    )
}
