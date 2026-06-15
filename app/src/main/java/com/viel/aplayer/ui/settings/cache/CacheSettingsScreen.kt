package com.viel.aplayer.ui.settings.cache

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.viel.aplayer.R
import com.viel.aplayer.application.download.CacheStatistics
import com.viel.aplayer.data.store.AppSettings
import com.viel.aplayer.data.store.GlassEffectMode
import com.viel.aplayer.ui.common.APlayerGlassTopBar
import com.viel.aplayer.ui.common.formatFileSize
import com.viel.aplayer.ui.settings.SettingsTemplateDialog
import com.viel.aplayer.ui.settings.components.SettingsSectionHeader
import com.viel.aplayer.ui.settings.components.SettingsToggleItem
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource

/**
 * Cache Settings Screen (Settings-hosted manual-cache and playback-buffer controls)
 * Displays durable cache statistics and writes only persisted buffer settings, leaving DownloadManager runtime ownership in DownloadGraph.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CacheSettingsScreen(
    settings: AppSettings,
    cacheStatistics: CacheStatistics,
    onBack: () -> Unit,
    onRefreshStatistics: () -> Unit,
    onClearManualDownloads: () -> Unit,
    onPlaybackBufferMaxBytesChange: (Long) -> Unit,
    onDownloadWifiOnlyChange: (Boolean) -> Unit,
    glassEffectMode: GlassEffectMode,
    modifier: Modifier = Modifier,
    cacheHazeState: HazeState? = null
) {
    val safeDrawingPadding = WindowInsets.safeDrawing.exclude(WindowInsets.ime).asPaddingValues()
    val layoutDirection = LocalLayoutDirection.current
    val startPadding = safeDrawingPadding.calculateStartPadding(layoutDirection)
    val endPadding = safeDrawingPadding.calculateEndPadding(layoutDirection)
    val density = LocalDensity.current
    var topBarHeightPx by remember { mutableIntStateOf(0) }
    var clearTarget by remember { mutableStateOf<CacheClearTarget?>(null) }
    val resolvedHazeState = cacheHazeState.takeIf { glassEffectMode == GlassEffectMode.Haze }
    // Cache Settings Top Bar Offset (Reserve list space for the overlay chrome)
    // This mirrors the settings sub-page pattern so statistics rows do not slide under the glass header.
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
                            // Cache Settings Haze Source (Expose cache rows as top-bar backdrop)
                            // Drawing the top bar as a sibling keeps toolbar icons out of the sampled blur source.
                            Modifier.hazeSource(resolvedHazeState)
                        } else {
                            Modifier
                        }
                    ),
                containerColor = if (resolvedHazeState != null) MaterialTheme.colorScheme.background else Color.Transparent,
                contentWindowInsets = WindowInsets.safeDrawing.exclude(WindowInsets.ime)
            ) { innerPadding ->
                CacheSettingsList(
                    settings = settings,
                    cacheStatistics = cacheStatistics,
                    onPlaybackBufferMaxBytesChange = onPlaybackBufferMaxBytesChange,
                    onDownloadWifiOnlyChange = onDownloadWifiOnlyChange,
                    onClearManualDownloads = { clearTarget = CacheClearTarget.ManualDownloads },
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
                title = { Text(stringResource(R.string.settings_cache_settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = stringResource(R.string.back_content_description)
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onRefreshStatistics) {
                        Icon(
                            Icons.Rounded.Refresh,
                            contentDescription = stringResource(R.string.settings_cache_refresh_action)
                        )
                    }
                }
            )
        }
    }

    clearTarget?.let { target ->
        SettingsTemplateDialog(
            onDismissRequest = { clearTarget = null },
            hazeState = resolvedHazeState,
            glassEffectMode = glassEffectMode,
            title = { Text(stringResource(target.titleRes)) },
            text = { Text(stringResource(target.bodyRes)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        clearTarget = null
                        when (target) {
                            CacheClearTarget.ManualDownloads -> onClearManualDownloads()
                        }
                    }
                ) {
                    Text(
                        text = stringResource(target.confirmRes),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { clearTarget = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }
}

/**
 * Cache Settings List (Render manual cache statistics, playback buffer settings, and network policy)
 * Keeps durable manual download storage separate from memory-only playback buffering so settings do not imply playback disk storage.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CacheSettingsList(
    settings: AppSettings,
    cacheStatistics: CacheStatistics,
    onPlaybackBufferMaxBytesChange: (Long) -> Unit,
    onDownloadWifiOnlyChange: (Boolean) -> Unit,
    onClearManualDownloads: () -> Unit,
    contentPadding: PaddingValues
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = contentPadding
    ) {
        item {
            SettingsSectionHeader(title = stringResource(R.string.settings_cache_manual_section_title))
            CacheStatisticRow(
                title = stringResource(R.string.settings_cache_completed_books_title),
                value = cacheStatistics.completedManualBooks.toString(),
                icon = Icons.Rounded.CloudDownload
            )
            CacheStatisticRow(
                title = stringResource(R.string.settings_cache_manual_files_title),
                value = cacheStatistics.manualCacheFileCount.toString(),
                icon = Icons.Rounded.Storage
            )
            CacheStatisticRow(
                title = stringResource(R.string.settings_cache_manual_size_title),
                value = formatFileSize(cacheStatistics.manualCacheBytes),
                icon = Icons.Rounded.CloudDownload
            )
            CacheCommandRow(
                title = stringResource(R.string.settings_cache_clear_manual_title),
                subtitle = stringResource(R.string.settings_cache_clear_manual_subtitle),
                onClick = onClearManualDownloads
            )
        }
        item {
            SettingsSectionHeader(title = stringResource(R.string.settings_cache_playback_section_title))
            CacheStatisticRow(
                title = stringResource(R.string.settings_cache_playback_size_title),
                value = stringResource(
                    R.string.settings_cache_playback_usage_value,
                    formatFileSize(settings.playbackBufferMaxBytes)
                ),
                icon = Icons.Rounded.CheckCircle
            )
            PlaybackBufferSizeSelector(
                selectedBytes = settings.playbackBufferMaxBytes,
                onSelected = onPlaybackBufferMaxBytesChange
            )
        }
        item {
            SettingsSectionHeader(title = stringResource(R.string.settings_cache_download_policy_section_title))
            SettingsToggleItem(
                title = stringResource(R.string.settings_download_wifi_only_title),
                subtitle = stringResource(R.string.settings_download_wifi_only_subtitle),
                icon = Icons.Rounded.Wifi,
                checked = settings.isDownloadWifiOnly,
                onCheckedChange = onDownloadWifiOnlyChange
            )
        }
        item {
            SettingsSectionHeader(title = stringResource(R.string.settings_cache_total_section_title))
            CacheStatisticRow(
                title = stringResource(R.string.settings_cache_total_files_title),
                value = cacheStatistics.manualCacheFileCount.toString(),
                icon = Icons.Rounded.Storage
            )
            CacheStatisticRow(
                title = stringResource(R.string.settings_cache_total_size_title),
                value = formatFileSize(cacheStatistics.manualCacheBytes),
                icon = Icons.Rounded.CheckCircle
            )
        }
    }
}

/**
 * Cache Command Row (Render a confirmed destructive cache maintenance entry)
 * The row only opens confirmation state; actual cache mutation is delegated back to SettingsViewModel commands.
 */
@Composable
private fun CacheCommandRow(
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        leadingContent = {
            Icon(
                imageVector = Icons.Rounded.Delete,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error
            )
        },
        trailingContent = {
            TextButton(onClick = onClick) {
                Text(
                    text = stringResource(R.string.settings_cache_clear_action),
                    color = MaterialTheme.colorScheme.error
                )
            }
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
    )
}

/**
 * Cache Statistic Row (Display one read-only cache metric)
 * Read-only metrics use ListItem instead of toggle rows so they cannot be mistaken for immediate cache mutation commands.
 */
@Composable
private fun CacheStatisticRow(
    title: String,
    value: String,
    icon: ImageVector
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = {
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        leadingContent = {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
    )
}

/**
 * Playback Buffer Size Selector (Persist the memory buffer target size)
 * The current player runtime is not rebuilt here; ExoPlayerFactory applies the stored value when the player is recreated.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlaybackBufferSizeSelector(
    selectedBytes: Long,
    onSelected: (Long) -> Unit
) {
    val options = remember { playbackBufferSizeOptions() }
    val resolvedSelectedBytes = options.firstOrNull { option -> option.bytes == selectedBytes }?.bytes
        ?: AppSettings.DEFAULT_PLAYBACK_BUFFER_MAX_BYTES
    ListItem(
        headlineContent = { Text(stringResource(R.string.settings_cache_playback_capacity_title)) },
        supportingContent = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = stringResource(R.string.settings_cache_playback_capacity_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                SingleChoiceSegmentedButtonRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                ) {
                    options.forEachIndexed { index, option ->
                        SegmentedButton(
                            selected = resolvedSelectedBytes == option.bytes,
                            onClick = { onSelected(option.bytes) },
                            shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size)
                        ) {
                            Text(
                                text = option.label,
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    }
                }
            }
        },
        leadingContent = {
            Icon(
                imageVector = Icons.Rounded.Storage,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
    )
}

/**
 * Playback Buffer Size Option (Stable UI value object for documented memory sizes)
 * Labels are generated from the same byte values written to DataStore so display and persistence cannot drift.
 */
private data class PlaybackBufferSizeOption(
    val bytes: Long,
    val label: String
)

// Playback Buffer Size Options (Mirror safe memory-buffer size choices)
// The repository still clamps restored values, while the UI writes only these documented capacities.
private fun playbackBufferSizeOptions(): List<PlaybackBufferSizeOption> =
    listOf(
        32L * 1024L * 1024L,
        64L * 1024L * 1024L,
        128L * 1024L * 1024L,
        256L * 1024L * 1024L
    ).map { bytes ->
        PlaybackBufferSizeOption(
            bytes = bytes,
            label = formatFileSize(bytes)
        )
    }

/**
 * Cache Clear Target (Dialog copy and command routing for destructive cache actions)
 * Keeps confirmation text selection tied to the target cache tier instead of branching inline inside the dialog.
 */
private enum class CacheClearTarget(
    val titleRes: Int,
    val bodyRes: Int,
    val confirmRes: Int
) {
    ManualDownloads(
        titleRes = R.string.settings_cache_clear_manual_confirm_title,
        bodyRes = R.string.settings_cache_clear_manual_confirm_body,
        confirmRes = R.string.settings_cache_clear_manual_confirm_action
    )
}
