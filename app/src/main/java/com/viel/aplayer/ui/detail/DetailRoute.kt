package com.viel.aplayer.ui.detail

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.materialkolor.PaletteStyle
import com.materialkolor.dynamicColorScheme
import com.materialkolor.ktx.animateColorScheme
import com.viel.aplayer.R
import com.viel.aplayer.application.library.LibraryReadStatus
import com.viel.aplayer.media.parser.ImageProcessor
import com.viel.aplayer.shared.settings.GlassEffectMode
import com.viel.aplayer.ui.common.APlayerDialogTemplate
import com.viel.aplayer.ui.common.theme.LocalAmoled
import com.viel.aplayer.ui.common.theme.LocalDarkTheme
import com.viel.aplayer.ui.common.uiPerformanceTrace
import dev.chrisbanes.haze.HazeState

/**
 * Stateful detail page route adapter.
 *
 * Owns DetailViewModel collection, dynamic cover color state, and user-effect callbacks before handing
 * pure rendering work to DetailOverlay and DetailScreen.
 */
@Composable
fun DetailRoute(
    modifier: Modifier = Modifier,
    detailViewModel: DetailViewModel,
    canStartNavigation: () -> Boolean,
    onPlayBook: (String) -> Unit,
    onNavigateToSearch: (String) -> Unit,
    onEditBookRequested: (String) -> Unit,
    onUpdateReadStatus: (String, LibraryReadStatus) -> Unit,
    onForceRegenerate: (String) -> Unit,
    onDeleteBook: (String) -> Unit,
    glassEffectMode: GlassEffectMode,
    appHazeState: HazeState? = null,
    onTransitionIdleChanged: (Boolean) -> Unit = {},
) {
    val detailUiState by detailViewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val darkTheme = LocalDarkTheme.current
    val coverPath = detailUiState.book?.item?.coverPath
    val coverLastUpdated = detailUiState.book?.item?.lastScannedAt ?: 0L

    var coverColor by remember(coverPath, coverLastUpdated) {
        mutableStateOf(ImageProcessor.getCachedColor(coverPath, coverLastUpdated)?.let { Color(it) })
    }

    val amoled = LocalAmoled.current
    val detailColorScheme = remember(coverColor, darkTheme, amoled) {
        coverColor?.let { color ->
            dynamicColorScheme(seedColor = color, isDark = darkTheme, isAmoled = amoled, style = PaletteStyle.Content)
        }
    }
    var pendingDownloadBookId by remember { mutableStateOf<String?>(null) }
    var showNotificationPermissionDialog by remember { mutableStateOf(false) }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val requestedBookId = pendingDownloadBookId
        pendingDownloadBookId = null
        if (granted && requestedBookId != null) {
            detailViewModel.downloadBook(requestedBookId)
        } else {
            detailViewModel.onDownloadNotificationPermissionDenied()
        }
    }
    val requestManualDownload: (String) -> Unit = { bookId ->
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        ) {
            detailViewModel.downloadBook(bookId)
        } else {
            pendingDownloadBookId = bookId
            showNotificationPermissionDialog = true
        }
    }
    val detailTraceState = "visible=${detailUiState.isVisible},hasBook=${detailUiState.book != null}," +
        "source=${detailUiState.entrySource},permissionDialog=$showNotificationPermissionDialog"

    DetailOverlay(
        visible = detailUiState.isVisible,
        glassEffectMode = glassEffectMode,
        modifier = modifier.uiPerformanceTrace(
            node = "DetailRoute",
            route = "Detail",
            state = detailTraceState
        ),
        hazeState = appHazeState,
        onTransitionIdleChanged = onTransitionIdleChanged
    ) {
        val screenBlock = @Composable {
            DetailScreen(
                uiState = detailUiState,
                onBackClick = { detailViewModel.setVisible(false) },
                onPlayPressed = { detailViewModel.onPlayPressed() },
                onSearchClick = { query ->
                    if (canStartNavigation()) {
                        onNavigateToSearch(query)
                    }
                },
                onPlayClick = {
                    detailUiState.book?.let { snapshot ->
                        onPlayBook(snapshot.bookId)
                    }
                },
                onEditBook = onEditBookRequested,
                onUpdateReadStatus = onUpdateReadStatus,
                onForceRegenerate = onForceRegenerate,
                onDeleteBook = onDeleteBook,
                onDownloadBook = requestManualDownload,
                onPauseDownload = detailViewModel::pauseDownload,
                onResumeDownload = detailViewModel::resumeDownload,
                onDeleteDownload = detailViewModel::deleteDownload,
                glassEffectMode = glassEffectMode,
                fullPageHazeState = appHazeState,
                coverColor = coverColor,
                onColorExtracted = { coverColor = it }
            )
            if (showNotificationPermissionDialog) {
                APlayerDialogTemplate(
                    onDismissRequest = {
                        pendingDownloadBookId = null
                        showNotificationPermissionDialog = false
                    },
                    hazeState = appHazeState,
                    glassEffectMode = glassEffectMode,
                    title = { Text(stringResource(R.string.detail_download_permission_title)) },
                    body = {
                        Text(stringResource(R.string.detail_download_permission_body))
                    },
                    actions = {
                        TextButton(
                            onClick = {
                                pendingDownloadBookId = null
                                showNotificationPermissionDialog = false
                            }
                        ) {
                            Text(stringResource(R.string.action_cancel))
                        }
                        TextButton(
                            onClick = {
                                showNotificationPermissionDialog = false
                                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            }
                        ) {
                            Text(stringResource(R.string.detail_download_permission_confirm))
                        }
                    }
                )
            }
        }

        if (detailColorScheme != null) {
            MaterialTheme(colorScheme = animateColorScheme(detailColorScheme), content = screenBlock)
        } else {
            screenBlock()
        }
    }
}
