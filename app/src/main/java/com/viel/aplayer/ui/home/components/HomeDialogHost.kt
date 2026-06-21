package com.viel.aplayer.ui.home.components

import androidx.compose.runtime.Composable
import com.viel.aplayer.application.library.LibraryReadStatus
import com.viel.aplayer.application.library.home.HomeBookItem
import com.viel.aplayer.shared.settings.GlassEffectMode
import com.viel.aplayer.ui.common.AudiobookActionDialog
import com.viel.aplayer.ui.common.AudiobookActionDialogBook
import com.viel.aplayer.ui.home.HomeDialogState
import dev.chrisbanes.haze.HazeState

/**
 * Derives concrete dialogs from Home page events.
 *
 * Maps [HomeDialogState] to Home-owned dialog UI while keeping HomeContent free of dialog rendering and dialog-local state.
 * The host receives the resolved dialog HazeState explicitly so HomeScreen can prefer the app-level backdrop while keeping app bar sampling page-local.
 */
@Composable
fun HomeDialogHost(
    state: HomeDialogState,
    hazeState: HazeState?,
    glassEffectMode: GlassEffectMode,
    onDismissRequest: () -> Unit,
    onEditBook: (String) -> Unit,
    onUpdateReadStatus: (String, LibraryReadStatus) -> Unit,
    onForceRegenerate: (String) -> Unit,
    onDeleteBook: (String) -> Unit
) {
    when (state) {
        HomeDialogState.None -> Unit
        is HomeDialogState.AudiobookActions -> {
            AudiobookActionDialog(
                book = state.book.toAudiobookActionDialogBook(),
                hazeState = hazeState,
                glassEffectMode = glassEffectMode,
                coverRequestScene = HOME_ACTION_DIALOG_COVER_SCENE,
                onDismissRequest = onDismissRequest,
                onEditBook = onEditBook,
                onUpdateReadStatus = onUpdateReadStatus,
                onForceRegenerate = onForceRegenerate,
                onDeleteBook = onDeleteBook
            )
        }
    }
}

private const val HOME_ACTION_DIALOG_COVER_SCENE = "home-action-dialog-cover"

/**
 * Adapt Home catalog projection to the shared dialog model.
 *
 * Keeps the common audiobook action dialog independent from [HomeBookItem] while passing only the fields required for identity, cover rendering, and read-status commands.
 */
private fun HomeBookItem.toAudiobookActionDialogBook(): AudiobookActionDialogBook =
    AudiobookActionDialogBook(
        id = id,
        title = title,
        author = author,
        narrator = narrator,
        coverPath = coverPath,
        thumbnailPath = thumbnailPath,
        lastScannedAt = lastScannedAt,
        readStatus = readStatus
    )
