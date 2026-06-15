package com.viel.aplayer.ui.settings.downloads

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.viel.aplayer.R
import com.viel.aplayer.application.download.BookCacheState
import com.viel.aplayer.application.download.BookCacheStatus
import com.viel.aplayer.application.download.ManualDownloadDisplayTextPolicy
import com.viel.aplayer.application.download.ManualDownloadTaskItem
import com.viel.aplayer.data.store.GlassEffectMode
import com.viel.aplayer.ui.common.APlayerGlassTopBar
import com.viel.aplayer.ui.common.formatFileSize
import com.viel.aplayer.ui.settings.SettingsTemplateDialog
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource

/**
 * Download Management Screen (Settings-hosted manual cache task list)
 * Shows only user-requested L1 manual download aggregates so memory-buffered playback bytes are never treated as queue entries.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadManagementScreen(
    tasks: List<ManualDownloadTaskItem>,
    onBack: () -> Unit,
    onPauseDownload: (String) -> Unit,
    onResumeDownload: (String) -> Unit,
    onRetryDownload: (String) -> Unit,
    onDeleteDownload: (String) -> Unit,
    glassEffectMode: GlassEffectMode,
    modifier: Modifier = Modifier,
    downloadHazeState: HazeState? = null
) {
    val safeDrawingPadding = WindowInsets.safeDrawing.exclude(WindowInsets.ime).asPaddingValues()
    val layoutDirection = LocalLayoutDirection.current
    val startPadding = safeDrawingPadding.calculateStartPadding(layoutDirection)
    val endPadding = safeDrawingPadding.calculateEndPadding(layoutDirection)
    val density = LocalDensity.current
    var topBarHeightPx by remember { mutableIntStateOf(0) }
    var deleteCandidate by remember { mutableStateOf<ManualDownloadTaskItem?>(null) }
    val resolvedHazeState = downloadHazeState.takeIf { glassEffectMode == GlassEffectMode.Haze }
    // Download Management Top Bar Offset (Reserve list space for the overlay chrome)
    // The measured value matches Settings and Recovery sub-pages, while the fallback protects first composition.
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
                            // Download Management Haze Source (Expose the task list as top-bar backdrop)
                            // The toolbar is drawn above the Scaffold, so the sampled layer must be content-only.
                            Modifier.hazeSource(resolvedHazeState)
                        } else {
                            Modifier
                        }
                    ),
                containerColor = if (resolvedHazeState != null) MaterialTheme.colorScheme.background else Color.Transparent,
                contentWindowInsets = WindowInsets.safeDrawing.exclude(WindowInsets.ime)
            ) { innerPadding ->
                DownloadManagementList(
                    tasks = tasks,
                    onPauseDownload = onPauseDownload,
                    onResumeDownload = onResumeDownload,
                    onRetryDownload = onRetryDownload,
                    onDeleteRequest = { task -> deleteCandidate = task },
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
                title = { Text(stringResource(R.string.settings_download_management_title)) },
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

    deleteCandidate?.let { task ->
        SettingsTemplateDialog(
            onDismissRequest = { deleteCandidate = null },
            hazeState = resolvedHazeState,
            glassEffectMode = glassEffectMode,
            title = { Text(stringResource(R.string.download_management_delete_confirm_title)) },
            text = { Text(stringResource(R.string.download_management_delete_confirm_body, task.title)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        deleteCandidate = null
                        onDeleteDownload(task.bookId)
                    }
                ) {
                    Text(
                        text = stringResource(R.string.detail_download_delete_action),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteCandidate = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }
}

/**
 * Download Management List (Render manual download rows or empty state)
 * Keeps task actions inside row-level icon buttons while retry stays on the permission-gated settings command path and destructive deletion still passes through a confirmation dialog.
 */
@Composable
private fun DownloadManagementList(
    tasks: List<ManualDownloadTaskItem>,
    onPauseDownload: (String) -> Unit,
    onResumeDownload: (String) -> Unit,
    onRetryDownload: (String) -> Unit,
    onDeleteRequest: (ManualDownloadTaskItem) -> Unit,
    contentPadding: PaddingValues
) {
    if (tasks.isEmpty()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding)
                .padding(horizontal = 32.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = stringResource(R.string.download_management_empty),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = contentPadding
        ) {
            items(tasks, key = { task -> task.bookId }) { task ->
                DownloadTaskRow(
                    task = task,
                    onPauseDownload = onPauseDownload,
                    onResumeDownload = onResumeDownload,
                    onRetryDownload = onRetryDownload,
                    onDeleteRequest = onDeleteRequest
                )
            }
        }
    }
}

/**
 * Download Task Row (Display one book-level manual cache aggregate)
 * The row uses BookCacheStatus fields only, keeping Room and Media3 objects outside the settings presentation layer.
 */
@Composable
private fun DownloadTaskRow(
    task: ManualDownloadTaskItem,
    onPauseDownload: (String) -> Unit,
    onResumeDownload: (String) -> Unit,
    onRetryDownload: (String) -> Unit,
    onDeleteRequest: (ManualDownloadTaskItem) -> Unit
) {
    val status = task.cacheStatus
    ListItem(
        headlineContent = {
            Text(
                text = task.headlineText(status),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        supportingContent = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = task.subtitleText(status),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                LinearProgressIndicator(
                    progress = { status.progressPercent / 100f },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                )
            }
        },
        leadingContent = {
            Icon(
                imageVector = status.statusIcon(),
                contentDescription = stringResource(status.statusContentDescriptionRes()),
                tint = status.statusTint()
            )
        },
        trailingContent = {
            DownloadTaskActions(
                task = task,
                    onPauseDownload = onPauseDownload,
                    onResumeDownload = onResumeDownload,
                    onRetryDownload = onRetryDownload,
                    onDeleteRequest = onDeleteRequest
                )
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
    )
}

/**
 * Download Task Actions (Map aggregate state to row-level commands)
 * Failed retries are routed through SettingsOverlay so new DownloadRequests still pass notification-permission preflight.
 */
@Composable
private fun DownloadTaskActions(
    task: ManualDownloadTaskItem,
    onPauseDownload: (String) -> Unit,
    onResumeDownload: (String) -> Unit,
    onRetryDownload: (String) -> Unit,
    onDeleteRequest: (ManualDownloadTaskItem) -> Unit
) {
    when (task.cacheStatus.state) {
        BookCacheState.QUEUED,
        BookCacheState.DOWNLOADING -> {
            IconButton(onClick = { onPauseDownload(task.bookId) }) {
                Icon(
                    Icons.Rounded.Pause,
                    contentDescription = stringResource(R.string.detail_download_pause_action)
                )
            }
        }
        BookCacheState.PAUSED -> {
            IconButton(onClick = { onResumeDownload(task.bookId) }) {
                Icon(
                    Icons.Rounded.PlayArrow,
                    contentDescription = stringResource(R.string.detail_download_resume_action)
                )
            }
        }
        BookCacheState.FAILED -> {
            IconButton(onClick = { onRetryDownload(task.bookId) }) {
                Icon(
                    Icons.Rounded.Replay,
                    contentDescription = stringResource(R.string.detail_download_retry_action)
                )
            }
        }
        BookCacheState.NONE,
        BookCacheState.COMPLETED -> Unit
    }
    IconButton(onClick = { onDeleteRequest(task) }) {
        Icon(
            Icons.Rounded.Delete,
            contentDescription = stringResource(R.string.detail_download_delete_action),
            tint = MaterialTheme.colorScheme.error
        )
    }
}

// Download Row Headline (Match notification progress-first title formatting)
// Long authors are compacted before Compose ellipsizing so the work title remains visible in each management row.
private fun ManualDownloadTaskItem.headlineText(status: BookCacheStatus): String =
    ManualDownloadDisplayTextPolicy.progressBookLabel(
        progressPercent = status.progressPercent,
        author = author,
        bookTitle = title
    )

// Download Row Subtitle (Build compact supplemental text from aggregate cache state)
// The progress percentage already appears in the headline and bar, so the subtitle keeps only state, numeric file count, and optional byte range.
@Composable
private fun ManualDownloadTaskItem.subtitleText(status: BookCacheStatus): String {
    val stateText = stringResource(status.labelRes())
    val downloadedSizeText = status.totalBytes
        .takeIf { totalBytes -> totalBytes > 0L }
        ?.let { formatFileSize(status.downloadedBytes) }
    val totalSizeText = status.totalBytes
        .takeIf { totalBytes -> totalBytes > 0L }
        ?.let { totalBytes -> formatFileSize(totalBytes) }
    return ManualDownloadDisplayTextPolicy.taskSupplementalLabel(
        statusText = stateText,
        completedFiles = status.completedFiles,
        totalFiles = status.totalFiles,
        downloadedSizeText = downloadedSizeText,
        totalSizeText = totalSizeText
    )
}

// Download Status Label (Map application cache states to stable localized row labels)
// The labels are shared by icon descriptions and visible row status text.
private fun BookCacheStatus.labelRes(): Int =
    when (state) {
        BookCacheState.NONE -> R.string.download_management_status_none
        BookCacheState.QUEUED -> R.string.download_management_status_queued
        BookCacheState.DOWNLOADING -> R.string.download_management_status_downloading
        BookCacheState.PAUSED -> R.string.download_management_status_paused
        BookCacheState.COMPLETED -> R.string.download_management_status_completed
        BookCacheState.FAILED -> R.string.download_management_status_failed
    }

// Download Status Icon (Render a compact visual state marker)
// Icons mirror the detail top-bar states while adding a completed/error distinction for list scanning.
private fun BookCacheStatus.statusIcon() =
    when (state) {
        BookCacheState.COMPLETED -> Icons.Rounded.CheckCircle
        BookCacheState.PAUSED -> Icons.Rounded.Pause
        BookCacheState.FAILED -> Icons.Rounded.Error
        BookCacheState.NONE,
        BookCacheState.QUEUED,
        BookCacheState.DOWNLOADING -> Icons.Rounded.CloudDownload
    }

// Download Status Content Description (Expose row icon meaning to accessibility services)
// Reuses the visible status labels so screen readers and text UI stay aligned.
private fun BookCacheStatus.statusContentDescriptionRes(): Int = labelRes()

// Download Status Tint (Emphasize terminal success and failure states)
// Active and paused states keep the normal primary tint to avoid implying an error.
@Composable
private fun BookCacheStatus.statusTint(): Color =
    when (state) {
        BookCacheState.FAILED -> MaterialTheme.colorScheme.error
        BookCacheState.COMPLETED -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
