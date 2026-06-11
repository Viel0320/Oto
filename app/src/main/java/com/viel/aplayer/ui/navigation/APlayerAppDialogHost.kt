package com.viel.aplayer.ui.navigation

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.viel.aplayer.R
import com.viel.aplayer.data.store.GlassEffectMode
import com.viel.aplayer.ui.common.APlayerDialogTemplate
import com.viel.aplayer.ui.common.formatDate
import com.viel.aplayer.ui.common.formatTime
import com.viel.aplayer.ui.player.PlaybackViewModel
import dev.chrisbanes.haze.HazeState

/**
 * APlayer App Dialog Host (Derives app-level playback dialogs from ViewModel state)
 *
 * Keeps global playback confirmation dialogs out of APlayerApp while preserving the app-level HazeState sampling source used by top-level overlays.
 */
@Composable
fun APlayerAppDialogHost(
    hazeState: HazeState?,
    glassEffectMode: GlassEffectMode,
    isFullPlayerVisible: Boolean,
    absProgressConflictState: PlaybackViewModel.AbsProgressConflictDialogState,
    trackUnavailableState: PlaybackViewModel.TrackUnavailableDialogState,
    onDismissAbsProgressConflict: () -> Unit,
    onAcceptRemoteAbsProgressConflict: () -> Unit,
    onAcceptLocalAbsProgressConflict: () -> Unit,
    onDismissTrackUnavailable: () -> Unit,
    onSkipToNextAvailableTrack: (String, Int) -> Unit
) {
    if (absProgressConflictState.show) {
        APlayerDialogTemplate(
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
                // ABS Progress Conflict Body (Render the local-vs-remote checkpoint comparison)
                // The host owns the visual comparison while PlayerViewModel owns the conflict decision commands and pending playback request.
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

    if (trackUnavailableState.show && isFullPlayerVisible) {
        APlayerDialogTemplate(
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
                // Track Unavailable Body (Explain the forced skip consequence before mutating playback)
                // The dialog stays app-level because unavailable-track events originate from playback service feedback rather than a specific page surface.
                Text(
                    text = stringResource(R.string.track_unavailable_body),
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
                    }
                ) {
                    Text(stringResource(R.string.track_unavailable_confirm_skip), color = MaterialTheme.colorScheme.error)
                }
            }
        )
    }
}

/**
 * ABS Finished Label (Adds a compact semantic marker beside progress positions)
 *
 * Keeps the dialog text construction readable while making completed-vs-incomplete conflicts visible to users.
 */
private fun absFinishedSuffix(isFinished: Boolean, finishedSuffix: String): String =
    if (isFinished) finishedSuffix else ""
