package com.viel.aplayer.ui.home.components

import androidx.compose.runtime.Composable
import com.viel.aplayer.data.store.GlassEffectMode
import com.viel.aplayer.ui.common.ScanResultDialog
import com.viel.aplayer.ui.home.HomeDialogState
import dev.chrisbanes.haze.HazeState

/**
 * Home Dialog Host (Derives concrete dialogs from Home page events)
 *
 * Maps [HomeDialogState] to Home-owned dialog UI while keeping HomeScreenContent free of dialog rendering and dialog-local state.
 * The host receives the resolved dialog HazeState explicitly so HomeScreen can prefer the app-level backdrop while keeping app bar sampling page-local.
 */
@Composable
fun HomeDialogHost(
    state: HomeDialogState,
    hazeState: HazeState?,
    glassEffectMode: GlassEffectMode,
    onDismissRequest: () -> Unit,
    onDismissScanResult: () -> Unit,
    onUpdateReadStatus: (String, String) -> Unit,
    onForceRegenerate: (String) -> Unit,
    onDeleteBook: (String) -> Unit
) {
    when (state) {
        HomeDialogState.None -> Unit
        is HomeDialogState.AudiobookActions -> {
            AudiobookActionDialogs(
                bookWithProgress = state.bookWithProgress,
                hazeState = hazeState,
                glassEffectMode = glassEffectMode,
                onDismissRequest = onDismissRequest,
                onUpdateReadStatus = onUpdateReadStatus,
                onForceRegenerate = onForceRegenerate,
                onDeleteBook = onDeleteBook
            )
        }
        is HomeDialogState.ScanResult -> {
            ScanResultDialog(
                session = state.session,
                hazeState = hazeState,
                glassEffectMode = glassEffectMode,
                onDismiss = onDismissScanResult
            )
        }
    }
}
