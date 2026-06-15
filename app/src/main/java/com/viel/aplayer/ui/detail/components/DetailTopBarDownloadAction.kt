package com.viel.aplayer.ui.detail.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.viel.aplayer.R
import com.viel.aplayer.application.download.BookCacheState
import com.viel.aplayer.application.download.BookCacheStatus

/**
 * Detail Top Bar Download Action (Render one compact manual-cache status control)
 * Maps the selected book's cache state to a stable icon button while leaving download commands and permission checks to the route layer.
 */
@Composable
fun DetailTopBarDownloadAction(
    cacheStatus: BookCacheStatus,
    onClick: () -> Unit
) {
    val icon = when (cacheStatus.state) {
        BookCacheState.COMPLETED -> Icons.Rounded.CheckCircle
        BookCacheState.PAUSED -> Icons.Rounded.Pause
        BookCacheState.FAILED -> Icons.Rounded.Error
        BookCacheState.NONE,
        BookCacheState.QUEUED,
        BookCacheState.DOWNLOADING,
        // Local Cache Fallback Icon (Provide standard download icon for local books as a compile-safe fallback)
        BookCacheState.LOCAL -> Icons.Rounded.CloudDownload
    }
    val description = when (cacheStatus.state) {
        BookCacheState.NONE -> R.string.detail_download_action_none
        BookCacheState.QUEUED,
        BookCacheState.DOWNLOADING -> R.string.detail_download_action_active
        BookCacheState.PAUSED -> R.string.detail_download_action_paused
        BookCacheState.COMPLETED -> R.string.detail_download_action_completed
        BookCacheState.FAILED -> R.string.detail_download_action_failed
        // Local Cache Fallback Description (Provide none string resource for local books as a compile-safe fallback)
        BookCacheState.LOCAL -> R.string.detail_download_action_none
    }
    val tint = when (cacheStatus.state) {
        BookCacheState.FAILED -> MaterialTheme.colorScheme.error
        BookCacheState.COMPLETED -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurface
    }

    IconButton(onClick = onClick) {
        Icon(
            imageVector = icon,
            contentDescription = stringResource(description),
            tint = tint
        )
    }
}
