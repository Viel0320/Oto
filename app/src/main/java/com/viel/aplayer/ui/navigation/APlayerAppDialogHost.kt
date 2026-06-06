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
import androidx.compose.ui.unit.dp
import com.viel.aplayer.data.store.GlassEffectMode
import com.viel.aplayer.ui.common.APlayerDialogTemplate
import com.viel.aplayer.ui.common.formatDate
import com.viel.aplayer.ui.common.formatTime
import com.viel.aplayer.ui.player.PlayerViewModel
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
    absProgressConflictState: PlayerViewModel.AbsProgressConflictDialogState,
    trackUnavailableState: PlayerViewModel.TrackUnavailableDialogState,
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
                    text = "选择播放进度",
                    style = MaterialTheme.typography.titleLarge
                )
            },
            body = {
                // ABS Progress Conflict Body (Render the local-vs-remote checkpoint comparison)
                // The host owns the visual comparison while PlayerViewModel owns the conflict decision commands and pending playback request.
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = absProgressConflictState.bookTitle.ifBlank { "当前书籍" },
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = buildString {
                            append("本机进度：")
                            append(absProgressConflictState.localPositionMs?.let(::formatTime) ?: "无")
                            append(absFinishedSuffix(absProgressConflictState.localFinished))
                            absProgressConflictState.localUpdatedAt?.let { updatedAt ->
                                append("\n本机更新：")
                                append(formatDate(updatedAt))
                            }
                            append("\n\n服务器进度：")
                            append(formatTime(absProgressConflictState.remotePositionMs))
                            append(absFinishedSuffix(absProgressConflictState.remoteFinished))
                            absProgressConflictState.remoteUpdatedAt?.let { updatedAt ->
                                append("\n服务器更新：")
                                append(formatDate(updatedAt))
                            }
                        },
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            },
            actions = {
                TextButton(onClick = onAcceptLocalAbsProgressConflict) {
                    Text("使用本机")
                }
                TextButton(onClick = onAcceptRemoteAbsProgressConflict) {
                    Text("使用服务器")
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
                    text = "分轨文件不可用",
                    style = MaterialTheme.typography.titleLarge
                )
            },
            body = {
                // Track Unavailable Body (Explain the forced skip consequence before mutating playback)
                // The dialog stays app-level because unavailable-track events originate from playback service feedback rather than a specific page surface.
                Text(
                    text = "当前收听的分轨物理文件不存在或损坏。是否跳过该分轨并播放下一首可用分轨？\n\n注意：强制跳轨可能会打乱原本预定的收听进度。",
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            actions = {
                TextButton(onClick = onDismissTrackUnavailable) {
                    Text("取消")
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
                    Text("确认跳过", color = MaterialTheme.colorScheme.error)
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
private fun absFinishedSuffix(isFinished: Boolean): String =
    if (isFinished) "（已完成）" else ""
