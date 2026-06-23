package com.viel.aplayer.ui.detail.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.viel.aplayer.R
import com.viel.aplayer.application.download.BookCacheState
import com.viel.aplayer.application.download.BookCacheStatus
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import dev.chrisbanes.haze.materials.HazeMaterials

/**
 * Render one compact manual-cache status control.
 * Maps the selected book's cache state to a stable icon button while leaving download commands and permission checks to the route layer.
 */
@OptIn(ExperimentalHazeMaterialsApi::class)
@Composable
fun DetailDownloadAction(
    cacheStatus: BookCacheStatus,
    onClick: () -> Unit,
    useHazeSurface: Boolean,
    hazeState: HazeState?,
    isLandscape: Boolean,
    modifier: Modifier = Modifier
) {
    val icon = when (cacheStatus.state) {
        BookCacheState.COMPLETED -> Icons.Rounded.CheckCircle
        BookCacheState.PAUSED -> Icons.Rounded.Pause
        BookCacheState.FAILED -> Icons.Rounded.Error
        BookCacheState.NONE,
        BookCacheState.QUEUED,
        BookCacheState.DOWNLOADING,
        BookCacheState.LOCAL -> Icons.Rounded.CloudDownload
    }
    val description = when (cacheStatus.state) {
        BookCacheState.NONE -> R.string.detail_download_action_none
        BookCacheState.QUEUED,
        BookCacheState.DOWNLOADING -> R.string.detail_download_action_active
        BookCacheState.PAUSED -> R.string.detail_download_action_paused
        BookCacheState.COMPLETED -> R.string.detail_download_action_completed
        BookCacheState.FAILED -> R.string.detail_download_action_failed
        BookCacheState.LOCAL -> R.string.detail_download_action_none
    }
    val tint = when (cacheStatus.state) {
        BookCacheState.FAILED -> MaterialTheme.colorScheme.error
        BookCacheState.COMPLETED -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurface
    }
    val shape = RoundedCornerShape(if (isLandscape) 12.dp else 16.dp)
    val iconSize = if (isLandscape) 20.dp else 24.dp
    val buttonColors = if (cacheStatus.state == BookCacheState.FAILED) {
        ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer
        )
    } else {
        ButtonDefaults.buttonColors()
    }
    val buttonTint = if (cacheStatus.state == BookCacheState.FAILED) {
        MaterialTheme.colorScheme.onErrorContainer
    } else {
        MaterialTheme.colorScheme.onPrimary
    }
    val iconContent = @Composable { iconTint: Color ->
        Icon(
            imageVector = icon,
            contentDescription = stringResource(description),
            tint = iconTint,
            modifier = Modifier.size(iconSize)
        )
    }

    if (useHazeSurface) {
        Surface(
            onClick = onClick,
            modifier = modifier
                .let {
                    if (hazeState != null) {
                        it
                            .clip(shape)
                            .hazeEffect(
                                state = hazeState,
                                style = HazeMaterials.ultraThin()
                            )
                    } else {
                        it
                    }
                },
            shape = shape,
            color = Color.Transparent,
            border = null,
            contentColor = tint
        ) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                iconContent(tint)
            }
        }
    } else {
        Button(
            onClick = onClick,
            modifier = modifier,
            shape = shape,
            contentPadding = PaddingValues(0.dp),
            colors = buttonColors
        ) {
            iconContent(buttonTint)
        }
    }
}
