package com.viel.oto.ui.settings.recovery

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.exclude
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.viel.oto.shared.R
import com.viel.oto.application.library.recovery.DeletedBookRecoveryItem
import com.viel.oto.shared.settings.GlassEffectMode
import com.viel.oto.ui.common.OtoGlassTopBar
import com.viel.oto.ui.common.layout.LocalAppWindowSizeClass
import com.viel.oto.ui.home.components.ListItem
import com.viel.oto.ui.settings.SettingsTemplateDialog
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import org.koin.androidx.compose.koinViewModel

/**
 * Connects the recovery ViewModel to the stateless screen.
 * Keeps lifecycle collection and modal actions outside the pure list renderer.
 */
@Composable
fun DeletedBookRecoveryRoute(
    onBack: () -> Unit,
    glassEffectMode: GlassEffectMode,
    recoveryHazeState: HazeState? = null,
    viewModel: DeletedBookRecoveryViewModel = koinViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    DeletedBookRecoveryScreen(
        uiState = uiState,
        onBack = onBack,
        onRestoreClick = viewModel::requestRestoreBook,
        onConfirmRestore = viewModel::restoreBook,
        onConfirmPartialRestore = viewModel::confirmPartialRestore,
        onDismissDialog = viewModel::dismissDialog,
        glassEffectMode = glassEffectMode,
        recoveryHazeState = recoveryHazeState
    )
}

/**
 * Renders recoverable books as a focused settings sub-page.
 * Uses the existing book list row body while replacing the trailing action with a restore command.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeletedBookRecoveryScreen(
    modifier: Modifier = Modifier,
    uiState: DeletedBookRecoveryUiState,
    onBack: () -> Unit,
    onRestoreClick: (String, String) -> Unit,
    onConfirmRestore: (String) -> Unit,
    onConfirmPartialRestore: () -> Unit,
    onDismissDialog: () -> Unit,
    glassEffectMode: GlassEffectMode,
    recoveryHazeState: HazeState? = null
) {
    val windowClass = LocalAppWindowSizeClass.current
    val safeDrawingPadding = WindowInsets.safeDrawing.exclude(WindowInsets.ime).asPaddingValues()
    val layoutDirection = androidx.compose.ui.platform.LocalLayoutDirection.current
    val startPadding = safeDrawingPadding.calculateStartPadding(layoutDirection)
    val endPadding = safeDrawingPadding.calculateEndPadding(layoutDirection)
    val density = LocalDensity.current
    var topBarHeightPx by remember { mutableIntStateOf(0) }
    val resolvedHazeState = recoveryHazeState.takeIf { glassEffectMode == GlassEffectMode.Haze }
    val measuredTopBarHeight = if (topBarHeightPx > 0) {
        with(density) { topBarHeightPx.toDp() }
    } else {
        safeDrawingPadding.calculateTopPadding() + 64.dp
    }

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth()
        ) {
            Scaffold(
                modifier = Modifier
                    .fillMaxSize()
                    .then(
                        if (resolvedHazeState != null) {
                            Modifier.hazeSource(resolvedHazeState)
                        } else {
                            Modifier
                        }
                    ),
                containerColor = if (resolvedHazeState != null) MaterialTheme.colorScheme.background else Color.Transparent,
                contentWindowInsets = WindowInsets.safeDrawing.exclude(WindowInsets.ime)
            ) { innerPadding ->
                DeletedBookRecoveryList(
                    items = uiState.items,
                    restoringBookIds = uiState.restoringBookIds,
                    onRestoreClick = onRestoreClick,
                    contentPadding = PaddingValues(
                        start = startPadding,
                        end = endPadding,
                        top = measuredTopBarHeight,
                        bottom = innerPadding.calculateBottomPadding()
                    ),
                    columnsCount = windowClass.columnsCount
                )
            }
            OtoGlassTopBar(
                glassEffectMode = glassEffectMode,
                hazeState = resolvedHazeState,
                onHeightChanged = { topBarHeightPx = it },
                modifier = Modifier.align(Alignment.TopCenter),
                title = { Text(stringResource(R.string.deleted_book_recovery_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = stringResource(R.string.back_content_description)
                        )
                    }
                }
            )
        }
    }

    DeletedBookRecoveryDialogs(
        dialogState = uiState.dialogState,
        onConfirmRestore = onConfirmRestore,
        onConfirmPartialRestore = onConfirmPartialRestore,
        onDismissDialog = onDismissDialog,
        glassEffectMode = glassEffectMode,
        hazeState = resolvedHazeState
    )
}

/**
 * Displays recoverable books or a simple empty state.
 * Uses grid layout with dynamic columnsCount based on device layout size, aligning with the main catalog view.
 */
@Composable
private fun DeletedBookRecoveryList(
    items: List<DeletedBookRecoveryItem>,
    restoringBookIds: Set<String>,
    onRestoreClick: (String, String) -> Unit,
    contentPadding: PaddingValues,
    columnsCount: Int
) {
    if (items.isEmpty()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding)
                .padding(horizontal = 32.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = stringResource(R.string.deleted_book_recovery_empty),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    } else {
        LazyVerticalGrid(
            columns = GridCells.Fixed(columnsCount),
            modifier = Modifier.fillMaxSize(),
            contentPadding = contentPadding
        ) {
            items(items, key = { item -> item.bookId }) { item ->
                val isRestoring = restoringBookIds.contains(item.bookId)
                ListItem(
                    bookId = item.bookId,
                    title = item.title,
                    author = item.author,
                    narrator = item.narrator,
                    duration = item.durationMs,
                    coverPath = item.coverPath,
                    coverLastUpdated = item.coverLastUpdated,
                    progressPercent = item.progressPercent,
                    onClick = { if (!isRestoring) onRestoreClick(item.bookId, item.title) },
                    onPlayClick = { if (!isRestoring) onRestoreClick(item.bookId, item.title) },
                    openActionLabel = stringResource(R.string.deleted_book_recovery_restore_action),
                    playActionLabel = stringResource(R.string.deleted_book_recovery_restore_action),
                    trailingContent = {
                        DeletedBookRecoveryTrailingAction(
                            isRestoring = isRestoring,
                            onRestoreClick = { onRestoreClick(item.bookId, item.title) }
                        )
                    }
                )
            }
        }
    }
}

/**
 * Renders restore button or per-row progress.
 * Localizes the command through contentDescription while row-level loading blocks duplicate restore attempts.
 */
@Composable
private fun DeletedBookRecoveryTrailingAction(
    isRestoring: Boolean,
    onRestoreClick: () -> Unit
) {
    IconButton(
        enabled = !isRestoring,
        onClick = onRestoreClick
    ) {
        if (isRestoring) {
            CircularProgressIndicator(
                modifier = Modifier.size(22.dp),
                strokeWidth = 2.dp
            )
        } else {
            Icon(
                imageVector = Icons.Rounded.Restore,
                contentDescription = stringResource(R.string.deleted_book_recovery_restore_action)
            )
        }
    }
}

/**
 * Renders failure acknowledgement and partial-restore confirmation.
 * Uses the settings dialog template so recovery feedback matches the existing settings overlay surfaces.
 */
@Composable
private fun DeletedBookRecoveryDialogs(
    dialogState: DeletedBookRecoveryDialogState?,
    onConfirmRestore: (String) -> Unit,
    onConfirmPartialRestore: () -> Unit,
    onDismissDialog: () -> Unit,
    glassEffectMode: GlassEffectMode,
    hazeState: HazeState?
) {
    when (dialogState) {
        is DeletedBookRecoveryDialogState.RestoreConfirmation -> {
            SettingsTemplateDialog(
                onDismissRequest = onDismissDialog,
                hazeState = hazeState,
                glassEffectMode = glassEffectMode,
                title = { Text(stringResource(R.string.deleted_book_recovery_confirm_title)) },
                text = { Text(stringResource(R.string.deleted_book_recovery_confirm_body, dialogState.bookTitle)) },
                confirmButton = {
                    TextButton(
                        onClick = {
                            onDismissDialog()
                            onConfirmRestore(dialogState.bookId)
                        }
                    ) {
                        Text(stringResource(R.string.action_confirm))
                    }
                },
                dismissButton = {
                    TextButton(onClick = onDismissDialog) {
                        Text(stringResource(R.string.action_cancel))
                    }
                }
            )
        }
        is DeletedBookRecoveryDialogState.Failure -> {
            val body = dialogState.messageArg?.takeIf { it.isNotBlank() }?.let { arg ->
                stringResource(dialogState.messageRes, arg)
            } ?: stringResource(dialogState.messageRes)
            SettingsTemplateDialog(
                onDismissRequest = onDismissDialog,
                hazeState = hazeState,
                glassEffectMode = glassEffectMode,
                title = { Text(stringResource(R.string.deleted_book_recovery_failure_title)) },
                text = { Text(body) },
                confirmButton = {
                    TextButton(onClick = onDismissDialog) {
                        Text(stringResource(R.string.action_ok))
                    }
                },
                dismissButton = {}
            )
        }
        is DeletedBookRecoveryDialogState.PartialConfirmation -> {
            SettingsTemplateDialog(
                onDismissRequest = onDismissDialog,
                hazeState = hazeState,
                glassEffectMode = glassEffectMode,
                title = { Text(stringResource(R.string.deleted_book_recovery_partial_title)) },
                text = { Text(stringResource(R.string.deleted_book_recovery_partial_body)) },
                confirmButton = {
                    TextButton(onClick = onConfirmPartialRestore) {
                        Text(stringResource(R.string.deleted_book_recovery_partial_confirm))
                    }
                },
                dismissButton = {
                    TextButton(onClick = onDismissDialog) {
                        Text(stringResource(R.string.action_cancel))
                    }
                }
            )
        }
        null -> Unit
    }
}
