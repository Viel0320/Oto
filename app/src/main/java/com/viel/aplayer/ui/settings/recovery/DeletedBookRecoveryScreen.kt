package com.viel.aplayer.ui.settings.recovery

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.lifecycle.viewmodel.compose.viewModel
import com.viel.aplayer.R
import com.viel.aplayer.application.library.recovery.DeletedBookRecoveryItem
import com.viel.aplayer.data.store.GlassEffectMode
import com.viel.aplayer.ui.common.APlayerGlassTopBar
import com.viel.aplayer.ui.common.theme.LocalWindowClass
import com.viel.aplayer.ui.settings.SettingsTemplateDialog
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import com.viel.aplayer.ui.home.components.ListItem as BookListItem

/**
 * Deleted Book Recovery Route (Connects the recovery ViewModel to the stateless screen)
 * Keeps lifecycle collection and modal actions outside the pure list renderer.
 */
@Composable
fun DeletedBookRecoveryRoute(
    onBack: () -> Unit,
    glassEffectMode: GlassEffectMode,
    recoveryHazeState: HazeState? = null,
    viewModel: DeletedBookRecoveryViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    DeletedBookRecoveryScreen(
        uiState = uiState,
        onBack = onBack,
        onRestoreClick = viewModel::restoreBook,
        onConfirmPartialRestore = viewModel::confirmPartialRestore,
        onDismissDialog = viewModel::dismissDialog,
        glassEffectMode = glassEffectMode,
        recoveryHazeState = recoveryHazeState
    )
}

/**
 * Deleted Book Recovery Screen (Renders recoverable books as a focused settings sub-page)
 * Uses the existing book list row body while replacing the trailing action with a restore command.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeletedBookRecoveryScreen(
    uiState: DeletedBookRecoveryUiState,
    onBack: () -> Unit,
    onRestoreClick: (String) -> Unit,
    onConfirmPartialRestore: () -> Unit,
    onDismissDialog: () -> Unit,
    glassEffectMode: GlassEffectMode,
    recoveryHazeState: HazeState? = null,
    modifier: Modifier = Modifier
) {
    val safeDrawingPadding = WindowInsets.safeDrawing.exclude(WindowInsets.ime).asPaddingValues()
    val layoutDirection = androidx.compose.ui.platform.LocalLayoutDirection.current
    val startPadding = safeDrawingPadding.calculateStartPadding(layoutDirection)
    val endPadding = safeDrawingPadding.calculateEndPadding(layoutDirection)
    val density = LocalDensity.current
    val useWideLayout = LocalWindowClass.current.isWideScreen
    var topBarHeightPx by remember { mutableIntStateOf(0) }
    val resolvedHazeState = recoveryHazeState.takeIf { glassEffectMode == GlassEffectMode.Haze }
    // Recovery Top Bar Height Resolution (Reserve room for overlay chrome before the first measured frame)
    // Matching Settings and About spacing prevents the restore list from sliding under the glass header.
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
                // Recovery Panel Width (Match Settings and About centered panel behavior on wide screens)
                // Keeping the restore page at the same width prevents local sub-navigation from visually jumping between panels.
                .fillMaxWidth(if (useWideLayout) 0.8f else 1f)
        ) {
            Scaffold(
                modifier = Modifier
                    .fillMaxSize()
                    .then(
                        if (resolvedHazeState != null) {
                            // Recovery Content Haze Source (Expose the restore list as the top-bar blur source)
                            // The top bar is drawn above the list, so the sampled layer must be the content surface only.
                            Modifier.hazeSource(resolvedHazeState)
                        } else {
                            Modifier
                        }
                    ),
                // Title: Avoid Recovery Scaffold Background Overdraw (Set Scaffold containerColor to transparent)
                // Overrides Scaffold background containerColor to transparent so it won't draw another background layer over SettingsOverlay surface.
                containerColor = Color.Transparent,
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
                    )
                )
            }
            APlayerGlassTopBar(
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
        onConfirmPartialRestore = onConfirmPartialRestore,
        onDismissDialog = onDismissDialog,
        glassEffectMode = glassEffectMode,
        hazeState = resolvedHazeState
    )
}

/**
 * Deleted Book Recovery List (Displays recoverable books or a simple empty state)
 * Keeps the recovery page as a plain list so it visually aligns with existing catalog rows.
 */
@Composable
private fun DeletedBookRecoveryList(
    items: List<DeletedBookRecoveryItem>,
    restoringBookIds: Set<String>,
    onRestoreClick: (String) -> Unit,
    contentPadding: PaddingValues
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = contentPadding
    ) {
        if (items.isEmpty()) {
            item {
                Box(
                    modifier = Modifier
                        .fillParentMaxSize()
                        .padding(horizontal = 32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stringResource(R.string.deleted_book_recovery_empty),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            items(items, key = { item -> item.bookId }) { item ->
                val isRestoring = restoringBookIds.contains(item.bookId)
                BookListItem(
                    bookId = item.bookId,
                    title = item.title,
                    author = item.author,
                    narrator = item.narrator,
                    duration = item.durationMs,
                    coverPath = item.coverPath,
                    coverLastUpdated = item.coverLastUpdated,
                    progressPercent = item.progressPercent,
                    onClick = { if (!isRestoring) onRestoreClick(item.bookId) },
                    onPlayClick = { if (!isRestoring) onRestoreClick(item.bookId) },
                    openActionLabel = stringResource(R.string.deleted_book_recovery_restore_action),
                    playActionLabel = stringResource(R.string.deleted_book_recovery_restore_action),
                    trailingContent = {
                        DeletedBookRecoveryTrailingAction(
                            isRestoring = isRestoring,
                            onRestoreClick = { onRestoreClick(item.bookId) }
                        )
                    }
                )
            }
        }
    }
}

/**
 * Deleted Book Recovery Trailing Action (Renders restore button or per-row progress)
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
 * Deleted Book Recovery Dialogs (Renders failure acknowledgement and partial-restore confirmation)
 * Uses the settings dialog template so recovery feedback matches the existing settings overlay surfaces.
 */
@Composable
private fun DeletedBookRecoveryDialogs(
    dialogState: DeletedBookRecoveryDialogState?,
    onConfirmPartialRestore: () -> Unit,
    onDismissDialog: () -> Unit,
    glassEffectMode: GlassEffectMode,
    hazeState: HazeState?
) {
    when (dialogState) {
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
