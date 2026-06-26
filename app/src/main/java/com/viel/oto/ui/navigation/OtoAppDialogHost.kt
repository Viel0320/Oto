package com.viel.oto.ui.navigation

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.viel.oto.shared.R
import com.viel.oto.application.library.settings.SettingsRootItem
import com.viel.oto.event.feedback.FeedbackMessage
import com.viel.oto.event.feedback.render
import com.viel.oto.shared.formatDate
import com.viel.oto.shared.formatTime
import com.viel.oto.shared.settings.GlassEffectMode
import com.viel.oto.ui.common.OtoDialogTemplate
import com.viel.oto.ui.libraryManagement.AddLibrarySourceDialog
import com.viel.oto.ui.player.PlaybackViewModel
import dev.chrisbanes.haze.HazeState

/**
 * Derives app-level feedback and playback dialogs from shell state.
 *
 * Keeps global feedback dialogs and playback confirmations out of OtoApp while preserving the
 * app-level HazeState sampling source used by top-level overlays.
 */
@Composable
fun OtoAppDialogHost(
    hazeState: HazeState?,
    glassEffectMode: GlassEffectMode,
    feedbackDialogMessage: FeedbackMessage?,
    absProgressConflictState: PlaybackViewModel.AbsProgressConflictDialogState,
    trackUnavailableState: PlaybackViewModel.TrackUnavailableDialogState,
    onDismissFeedbackDialog: () -> Unit,
    onDismissAbsProgressConflict: () -> Unit,
    onAcceptRemoteAbsProgressConflict: () -> Unit,
    onAcceptLocalAbsProgressConflict: () -> Unit,
    onDismissTrackUnavailable: () -> Unit,
    onSkipToNextAvailableTrack: (String, Int) -> Unit,
    showAddLibraryDialog: Boolean,
    onDismissAddLibrary: () -> Unit,
    onAddLibraryPickSaf: () -> Unit,
    onAddLibraryPickWebDav: () -> Unit,
    onAddLibraryPickAbs: () -> Unit,
    rootActionsTarget: SettingsRootItem?,
    onDismissRootActions: () -> Unit,
    onEditRoot: (SettingsRootItem) -> Unit,
    onSyncRoot: (SettingsRootItem) -> Unit,
    onRescanRoot: (SettingsRootItem) -> Unit,
    onRequestDeleteRoot: (SettingsRootItem) -> Unit,
    rootPendingDelete: SettingsRootItem?,
    onConfirmDeleteRoot: () -> Unit,
    onDismissDeleteRoot: () -> Unit
) {
    if (showAddLibraryDialog) {
        AddLibrarySourceDialog(
            glassEffectMode = glassEffectMode,
            hazeState = hazeState,
            onPickSaf = onAddLibraryPickSaf,
            onPickWebDav = onAddLibraryPickWebDav,
            onPickAbs = onAddLibraryPickAbs,
            onDismiss = onDismissAddLibrary
        )
    }

    if (rootActionsTarget != null) {
        LibraryRootActionsDialog(
            root = rootActionsTarget,
            glassEffectMode = glassEffectMode,
            hazeState = hazeState,
            onEdit = onEditRoot,
            onSync = onSyncRoot,
            onRescan = onRescanRoot,
            onRequestDelete = onRequestDeleteRoot,
            onDismiss = onDismissRootActions
        )
    }

    if (rootPendingDelete != null) {
        DeleteLibraryRootDialog(
            glassEffectMode = glassEffectMode,
            hazeState = hazeState,
            onConfirm = onConfirmDeleteRoot,
            onDismiss = onDismissDeleteRoot
        )
    }

    if (feedbackDialogMessage != null) {
        val context = LocalContext.current
        OtoDialogTemplate(
            onDismissRequest = onDismissFeedbackDialog,
            hazeState = hazeState,
            glassEffectMode = glassEffectMode,
            scrollable = true,
            body = {
                Text(
                    text = feedbackDialogMessage.render(context),
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            actions = {
                TextButton(onClick = onDismissFeedbackDialog) {
                    Text(stringResource(R.string.action_ok))
                }
            }
        )
    }

    if (absProgressConflictState.show) {
        OtoDialogTemplate(
            onDismissRequest = onDismissAbsProgressConflict,
            hazeState = hazeState,
            glassEffectMode = glassEffectMode,
            scrollable = true,
            title = {
                Text(
                    text = stringResource(R.string.abs_progress_conflict_title),
                    style = MaterialTheme.typography.titleLarge
                )
            },
            body = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    val noneText = stringResource(R.string.abs_progress_none)
                    val localProgressLabel = stringResource(R.string.abs_progress_local_label)
                    val localUpdatedLabel = stringResource(R.string.abs_progress_local_updated_label)
                    val remoteProgressLabel = stringResource(R.string.abs_progress_remote_label)
                    val remoteUpdatedLabel = stringResource(R.string.abs_progress_remote_updated_label)
                    val finishedSuffix = stringResource(R.string.abs_progress_finished_suffix)
                    Text(
                        text = absProgressConflictState.bookTitle.ifBlank {
                            stringResource(R.string.abs_progress_current_book_fallback)
                        },
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = buildString {
                            append(localProgressLabel)
                            append(absProgressConflictState.localPositionMs?.let(::formatTime) ?: noneText)
                            append(absFinishedSuffix(absProgressConflictState.localFinished, finishedSuffix))
                            absProgressConflictState.localUpdatedAt?.let { updatedAt ->
                                append("\n")
                                append(localUpdatedLabel)
                                append(formatDate(updatedAt))
                            }
                            append("\n\n")
                            append(remoteProgressLabel)
                            append(formatTime(absProgressConflictState.remotePositionMs))
                            append(absFinishedSuffix(absProgressConflictState.remoteFinished, finishedSuffix))
                            absProgressConflictState.remoteUpdatedAt?.let { updatedAt ->
                                append("\n")
                                append(remoteUpdatedLabel)
                                append(formatDate(updatedAt))
                            }
                        },
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            },
            actions = {
                TextButton(onClick = onAcceptLocalAbsProgressConflict) {
                    Text(stringResource(R.string.abs_progress_use_local))
                }
                TextButton(onClick = onAcceptRemoteAbsProgressConflict) {
                    Text(stringResource(R.string.abs_progress_use_remote))
                }
            }
        )
    }

    if (trackUnavailableState.show) {
        OtoDialogTemplate(
            onDismissRequest = onDismissTrackUnavailable,
            hazeState = hazeState,
            glassEffectMode = glassEffectMode,
            scrollable = true,
            title = {
                Text(
                    text = stringResource(R.string.track_unavailable_title),
                    style = MaterialTheme.typography.titleLarge
                )
            },
            body = {
                val stoppedScope = trackUnavailableState.bookTitle.trim().takeIf { it.isNotBlank() }?.let { title ->
                    stringResource(R.string.feedback_playback_stopped_scope, title)
                }.orEmpty()
                Text(
                    text = stringResource(R.string.track_unavailable_body) + stoppedScope,
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            actions = {
                TextButton(onClick = onDismissTrackUnavailable) {
                    Text(stringResource(R.string.action_cancel))
                }
                TextButton(
                    onClick = {
                        onSkipToNextAvailableTrack(
                            trackUnavailableState.bookId,
                            trackUnavailableState.queueIndex
                        )
                        onDismissTrackUnavailable()
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text(stringResource(R.string.track_unavailable_confirm_skip))
                }
            }
        )
    }
}

/**
 * Adds a compact semantic marker beside progress positions.
 *
 * Keeps the dialog text construction readable while making completed-vs-incomplete conflicts visible to users.
 */
private fun absFinishedSuffix(isFinished: Boolean, finishedSuffix: String): String =
    if (isFinished) finishedSuffix else ""
