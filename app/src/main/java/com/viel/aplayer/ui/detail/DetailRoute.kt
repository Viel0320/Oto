package com.viel.aplayer.ui.detail

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
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
import com.viel.aplayer.R
import com.viel.aplayer.data.db.AudiobookSchema
import com.viel.aplayer.data.store.GlassEffectMode
import com.viel.aplayer.media.parser.ImageProcessor
import com.viel.aplayer.ui.common.APlayerDialogTemplate
import com.viel.aplayer.ui.common.theme.DynamicColorSchemeHelper
import com.viel.aplayer.ui.common.theme.LocalDarkTheme
import dev.chrisbanes.haze.HazeState

/**
 * Detail Route (Stateful detail page route adapter)
 *
 * Owns DetailViewModel collection, dynamic cover color state, and user-effect callbacks before handing
 * pure rendering work to DetailOverlay and DetailScreen.
 */
@Composable
fun DetailRoute(
    detailViewModel: DetailViewModel,
    canStartNavigation: () -> Boolean,
    onPlayBook: (String) -> Unit,
    onNavigateToSearch: (String) -> Unit,
    // Detail Action Edit Route (Delegate selected-book editing to the app shell)
    // DetailRoute forwards the Detail action-dialog command without owning EditBookViewModel or overlay lifecycle.
    onEditBookRequested: (String) -> Unit,
    // Detail Action Read Status Route (Delegate manual status changes)
    // The home/library scene still owns the persistence command, while DetailRoute only carries the selected Detail id.
    // Update Read Status: Update readStatus parameter type to ReadStatus enum.
    onUpdateReadStatus: (String, AudiobookSchema.ReadStatus) -> Unit,
    // Detail Action Metadata Refresh Route (Delegate forced regeneration)
    // Regeneration remains a library command so DetailRoute avoids media parsing or cache work.
    onForceRegenerate: (String) -> Unit,
    // Detail Action Delete Route (Delegate destructive removal)
    // App-level cleanup coordinates playback, Detail visibility, and catalog deletion around this callback.
    onDeleteBook: (String) -> Unit,
    // Glass Effect Route Input (Receives app-level visual mode without hard-coded defaults)
    // DetailRoute passes this value through to both the overlay shell and stateless screen.
    glassEffectMode: GlassEffectMode,
    modifier: Modifier = Modifier,
    // Haze Route Inputs (Separate app-level and detail-local sampling sources)
    // Route-level wiring keeps glass source ownership explicit while DetailScreen remains stateless.
    hazeState: HazeState? = null,
    detailHazeState: HazeState? = null,
    // Detail Transition Idle Callback (Expose overlay animation lifecycle to the app shell)
    // The navigation layer uses this signal to defer rapid Detail re-entry until the previous shared-element return chain finishes.
    onTransitionIdleChanged: (Boolean) -> Unit = {},
) {
    val detailUiState by detailViewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val darkTheme = LocalDarkTheme.current
    val currentColorScheme = MaterialTheme.colorScheme
    // Detail Cover Seed (Resolve artwork from the scene snapshot instead of a Room entity)
    // Dynamic theme state follows the selected detail item while keeping route code on the Detail boundary type.
    val coverPath = detailUiState.book?.item?.coverPath

    // Cover Color Route State (Reset per selected artwork and seed the detail theme from cached extraction)
    // The color is route state because it coordinates theme selection and render callbacks without belonging to the stateless screen.
    var coverColor by remember(coverPath) {
        mutableStateOf<Color?>(ImageProcessor.getCachedColor(coverPath)?.let { Color(it) })
    }

    val detailColorScheme = remember(coverColor, darkTheme, currentColorScheme) {
        coverColor?.let { color ->
            DynamicColorSchemeHelper.generateColorSchemeFromSeed(color, darkTheme, currentColorScheme)
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
        // Download Notification Permission Gate (Prevent invisible foreground downloads on Android 13+)
        // The download domain receives only commands that passed presentation-level notification permission preflight.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        ) {
            detailViewModel.downloadBook(bookId)
        } else {
            pendingDownloadBookId = bookId
            showNotificationPermissionDialog = true
        }
    }

    DetailOverlay(
        visible = detailUiState.isVisible,
        glassEffectMode = glassEffectMode,
        modifier = modifier,
        hazeState = hazeState,
        detailHazeState = detailHazeState,
        onTransitionIdleChanged = onTransitionIdleChanged
    ) {
        val screenBlock = @Composable {
            DetailScreen(
                uiState = detailUiState,
                onBackClick = { detailViewModel.setVisible(false) },
                onPlayPressed = { detailViewModel.onPlayPressed() },
                onSearchClick = { query ->
                    if (canStartNavigation()) {
                        // In-Place Search Activation (Open search while preserving the selected detail context)
                        // Search routing remains a host-level effect and does not mutate DetailScreen visibility.
                        onNavigateToSearch(query)
                    }
                },
                onPlayClick = {
                    detailUiState.book?.let { snapshot ->
                        // Host Playback Command (Route selected detail item playback to PlayerViewModel in the app shell)
                        // DetailScreen only reports the action; route-level wiring resolves the current book identifier.
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
                hazeState = hazeState,
                fullPageHazeState = hazeState,
                coverColor = coverColor,
                onColorExtracted = { coverColor = it }
            )
            if (showNotificationPermissionDialog) {
                APlayerDialogTemplate(
                    onDismissRequest = {
                        pendingDownloadBookId = null
                        showNotificationPermissionDialog = false
                    },
                    hazeState = hazeState ?: detailHazeState,
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

        // Detail Theme Application (Apply book-seeded color only around the stateless detail screen)
        // Keeping theme selection here prevents DetailScreen from needing ImageProcessor or dynamic theme knowledge.
        if (detailColorScheme != null) {
            MaterialTheme(colorScheme = detailColorScheme, content = screenBlock)
        } else {
            screenBlock()
        }
    }
}
