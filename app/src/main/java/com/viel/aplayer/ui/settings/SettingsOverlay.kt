package com.viel.aplayer.ui.settings

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.viel.aplayer.R
import com.viel.aplayer.i18n.AppLocaleController
import com.viel.aplayer.shared.settings.GlassEffectMode
import com.viel.aplayer.ui.common.uiPerformanceTrace
import com.viel.aplayer.ui.settings.about.AboutLibrariesScreen
import com.viel.aplayer.ui.settings.downloads.DownloadManagementScreen
import com.viel.aplayer.ui.settings.recovery.DeletedBookRecoveryRoute
import dev.chrisbanes.haze.HazeState

/**
 * SettingsOverlay Composable (Stateless Settings Overlay Shell)
 *
 * Hosts the settings overlay interface inside MainActivity, completely replacing the independent SettingsActivity.
 * Controls visual presentation through fade-in and slide-in transitions, maintaining premium layout animations.
 */
@Composable
fun SettingsOverlay(
    modifier: Modifier = Modifier,
    settingsViewModel: SettingsViewModel = viewModel(),
    glassEffectMode: GlassEffectMode,
    openDownloadManagementRequest: Boolean = false,
    onOpenDownloadManagementConsumed: () -> Unit = {}
) {
    // Collect settings visibility state (To reactively trigger transition animations)
    // Synchronizes the show/hide state from SettingsViewModel to drive AnimatedVisibility scope.
    val isVisible by settingsViewModel.isVisible.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // Settings Haze Source (Provide a local backdrop for settings chrome)
    // SettingsScreen registers its content layer with this state so the shared glass top bar samples settings content without changing the app-level dialog sampler.
    val settingsHazeState = remember { HazeState() }
    // About Haze Source (Separate license-page sampling from settings-page transitions)
    // AnimatedContent can keep Settings and About composed briefly at the same time, so About uses its own source to avoid competing registrations on one HazeState.
    val aboutHazeState = remember { HazeState() }
    // Recovery Haze Source (Separate deleted-book recovery sampling from other settings sub-pages)
    val deletedRecoveryHazeState = remember { HazeState() }
    // Download Management Haze Source (Separate manual-cache task sampling from other settings sub-pages)
    val downloadManagementHazeState = remember { HazeState() }
    val isBlur = glassEffectMode == GlassEffectMode.Haze
    // Settings Dialog Overlay Controller (Own settings modal state above the sampled page content)
    // Keeping this in SettingsOverlay lets dialogs render as siblings of SettingsScreen instead of being held by the page that registers hazeSource.
    val settingsDialogController = rememberSettingsDialogController()
    val libraryRootLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        uri?.let {
            val editingSafRootId = settingsDialogController.editingSafRootId
            if (editingSafRootId != null) {
                settingsViewModel.onSafRootRelocated(editingSafRootId, it)
                settingsDialogController.editingSafRootId = null
            } else {
                settingsViewModel.onLibraryRootSelected(it)
            }
        }
    }

    // Title: Initialize Backup/Restore SAF Launchers (Register ActivityResult Contracts for ZIP backup files export/import)
    // Registers CreateDocument and OpenDocument launchers to execute secure file picker overlays.
    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        uri?.let {
            settingsViewModel.exportUserData(it)
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            settingsDialogController.dialogState = SettingsDialogState.ImportConfirm(it)
        }
    }
    var pendingDownloadBookId by remember { mutableStateOf<String?>(null) }
    var showDownloadPermissionDialog by remember { mutableStateOf(false) }
    val downloadPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        val requestedBookId = pendingDownloadBookId
        pendingDownloadBookId = null
        if (granted && requestedBookId != null) {
            settingsViewModel.downloadBook(requestedBookId)
        } else {
            settingsViewModel.onDownloadNotificationPermissionDenied()
        }
    }
    val requestSettingsDownload: (String) -> Unit = { bookId ->
        // Settings Download Permission Gate (Prevent invisible foreground retry downloads on Android 13+)
        // The settings management page retries only after the same presentation-level notification preflight used by DetailRoute.
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        ) {
            settingsViewModel.downloadBook(bookId)
        } else {
            pendingDownloadBookId = bookId
            showDownloadPermissionDialog = true
        }
    }
    LaunchedEffect(isVisible) {
        if (!isVisible) {
            // Settings Dialog Lifecycle Reset (Clear overlay-owned modal state when the settings overlay closes)
            // The controller outlives SettingsScreen now, so closing settings must explicitly drop active dialogs and transient form input.
            settingsDialogController.dialogState = SettingsDialogState.None
            settingsDialogController.editingSafRootId = null
            settingsDialogController.resetWebDavForm()
            settingsDialogController.resetAbsForm()
        }
    }

    AnimatedVisibility(
        visible = isVisible,
        // Settings Overlay Enter Motion (Unify Material and Haze presentation)
        // The settings page always slides upward from the bottom while fading in over 300ms, so switching glass mode no longer changes navigation feel.
        enter = slideInVertically(initialOffsetY = { it }, animationSpec = tween(300)) + fadeIn(animationSpec = tween(300)),
        // Settings Overlay Exit Motion (Mirror enter with downward slide and fade)
        // The page exits toward the bottom over the same 300ms duration to keep close behavior consistent across visual modes.
        exit = slideOutVertically(targetOffsetY = { it }, animationSpec = tween(300)) + fadeOut(animationSpec = tween(300)),
        modifier = modifier
    ) {
        // Settings Sub-Page Navigation (Track local overlay destinations without expanding the app navigation di)
        // Settings, About, and Deleted Book Recovery remain inside one overlay lifecycle while each page keeps its own UI state.
        var activeSettingsPage by remember { mutableStateOf(SettingsOverlayPage.Main) }
        
        // Predictive back gesture state definitions (Track gesture execution and progress values)
        var isPredictiveBackActive by remember { mutableStateOf(false) }
        var predictiveBackProgress by remember { mutableFloatStateOf(0f) }
        val density = LocalDensity.current
        val view = LocalView.current
        val systemCornerRadius = remember(view) {
            val insets = view.rootWindowInsets
            insets?.getRoundedCorner(android.view.RoundedCorner.POSITION_TOP_LEFT)?.radius ?: 0
        }
        val cornerRadiusDp = with(density) { systemCornerRadius.toDp().coerceAtLeast(24.dp) }

        LaunchedEffect(openDownloadManagementRequest, isVisible) {
            if (openDownloadManagementRequest && isVisible) {
                // Download Notification Page Routing (Open the task list after the settings overlay becomes visible)
                // MainActivity only carries an external intent flag; the settings overlay owns its local child page state.
                activeSettingsPage = SettingsOverlayPage.DownloadManagement
                onOpenDownloadManagementConsumed()
            }
        }

        // Sub-page level back handler (Intercepts system back to slide back to main settings page)
        androidx.activity.compose.PredictiveBackHandler(
            enabled = isVisible && activeSettingsPage != SettingsOverlayPage.Main
        ) { progressFlow ->
            try {
                progressFlow.collect { }
                activeSettingsPage = SettingsOverlayPage.Main
            } catch (_: kotlin.coroutines.cancellation.CancellationException) {
                // Swipe gesture aborted
            }
        }

        // Overlay level back handler (Minimizes settings overlay with downward slide and fade animations)
        androidx.activity.compose.PredictiveBackHandler(
            enabled = isVisible && activeSettingsPage == SettingsOverlayPage.Main
        ) { progressFlow ->
            try {
                progressFlow.collect { backEvent ->
                    isPredictiveBackActive = true
                    predictiveBackProgress = backEvent.progress
                }
                settingsViewModel.setVisible(false)
            } catch (_: kotlin.coroutines.cancellation.CancellationException) {
                // Swipe gesture aborted
            } finally {
                isPredictiveBackActive = false
                predictiveBackProgress = 0f
            }
        }

        val maxPredictiveTranslationY = with(density) { 200.dp.toPx() }
        // Settings Trace State (Keep diagnostics at the overlay navigation boundary)
        // Sub-page, predictive-back, and permission-dialog flags explain most settings redraw bursts without exposing form input.
        val settingsTraceState = "visible=$isVisible,page=$activeSettingsPage," +
            "predictiveBack=$isPredictiveBackActive,downloadPermissionDialog=$showDownloadPermissionDialog"

        Surface(
            // Settings Overlay Surface Boundary (Leave Haze source ownership to screen content)
            // SettingsScreen now registers its content Scaffold as the blur source so the overlay top bar samples settings content without capturing its own chrome.
            modifier = Modifier
                .fillMaxSize()
                .uiPerformanceTrace(
                    node = "SettingsOverlay",
                    route = "Settings",
                    state = settingsTraceState
                )
                .graphicsLayer {
                    // Gesture drag translates downwards with fade out, matching the Detail / Player screen back animations
                    if (isPredictiveBackActive) {
                        translationY = predictiveBackProgress * maxPredictiveTranslationY
                        alpha = 1f - predictiveBackProgress * 0.3f
                    }
                }
                .clip(RoundedCornerShape(topStart = cornerRadiusDp, topEnd = cornerRadiusDp)),
            color = MaterialTheme.colorScheme.background
        ) {
            // Apply Horizontal Sub-Page Transitions (Switch settings sub-screens fluidly)
            // AnimatedContent moves between the main settings page and local child pages without adding app-wide routes.
            AnimatedContent(
                targetState = activeSettingsPage,
                transitionSpec = {
                    if (targetState != SettingsOverlayPage.Main) {
                        (slideInHorizontally(animationSpec = tween(300)) { width -> width } + fadeIn(animationSpec = tween(300)))
                            .togetherWith(slideOutHorizontally(animationSpec = tween(300)) { width -> -width } + fadeOut(animationSpec = tween(300)))
                    } else {
                        (slideInHorizontally(animationSpec = tween(300)) { width -> -width } + fadeIn(animationSpec = tween(300)))
                            .togetherWith(slideOutHorizontally(animationSpec = tween(300)) { width -> width } + fadeOut(animationSpec = tween(300)))
                    }
                },
                label = "SettingsSubPageTransition"
            ) { page ->
                when (page) {
                    SettingsOverlayPage.AboutLibraries -> {
                        AboutLibrariesScreen(
                            onBack = { activeSettingsPage = SettingsOverlayPage.Main },
                            glassEffectMode = glassEffectMode,
                            aboutHazeState = if (isBlur) aboutHazeState else null
                        )
                    }
                    SettingsOverlayPage.DeletedBookRecovery -> {
                        DeletedBookRecoveryRoute(
                            onBack = { activeSettingsPage = SettingsOverlayPage.Main },
                            glassEffectMode = glassEffectMode,
                            recoveryHazeState = if (isBlur) deletedRecoveryHazeState else null
                        )
                    }
                    SettingsOverlayPage.DownloadManagement -> {
                        // Download Management State Collection (Bind settings-hosted task stream to the local sub-page)
                        // The page still receives only read-model projections and ViewModel command callbacks.
                        val downloadTasks by settingsViewModel.downloadTasks.collectAsStateWithLifecycle()
                        DownloadManagementScreen(
                            tasks = downloadTasks,
                            onBack = { activeSettingsPage = SettingsOverlayPage.Main },
                            onPauseDownload = settingsViewModel::pauseDownload,
                            onResumeDownload = settingsViewModel::resumeDownload,
                            onRetryDownload = requestSettingsDownload,
                            onDeleteDownload = settingsViewModel::deleteDownload,
                            // Title: Delete All Downloads Link (Connect the screen delete-all callback to the ViewModel action)
                            onDeleteAllDownloads = settingsViewModel::deleteAllDownloads,
                            glassEffectMode = glassEffectMode,
                            downloadHazeState = if (isBlur) downloadManagementHazeState else null
                        )
                        if (showDownloadPermissionDialog) {
                            SettingsTemplateDialog(
                                onDismissRequest = {
                                    pendingDownloadBookId = null
                                    showDownloadPermissionDialog = false
                                },
                                hazeState = if (isBlur) downloadManagementHazeState else null,
                                glassEffectMode = glassEffectMode,
                                title = { Text(stringResource(R.string.detail_download_permission_title)) },
                                text = { Text(stringResource(R.string.detail_download_permission_body)) },
                                confirmButton = {
                                    TextButton(
                                        onClick = {
                                            showDownloadPermissionDialog = false
                                            downloadPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                        }
                                    ) {
                                        Text(stringResource(R.string.detail_download_permission_confirm))
                                    }
                                },
                                dismissButton = {
                                    TextButton(
                                        onClick = {
                                            pendingDownloadBookId = null
                                            showDownloadPermissionDialog = false
                                        }
                                    ) {
                                        Text(stringResource(R.string.action_cancel))
                                    }
                                }
                            )
                        }
                    }

                    SettingsOverlayPage.Main -> {
                    // Collect settings business data (To populate settings menu and capture actions)
                    // Listens to settings state parameters reactively from SettingsViewModel.
                    val settingsState by settingsViewModel.settingsState.collectAsStateWithLifecycle()
                    val libraryRootDisplays by settingsViewModel.libraryRootDisplays.collectAsStateWithLifecycle()
                    val absConnectionState by settingsViewModel.absConnectionState.collectAsStateWithLifecycle()
                    val webDavConnectionState by settingsViewModel.webDavConnectionState.collectAsStateWithLifecycle()
                    val downloadTasks by settingsViewModel.downloadTasks.collectAsStateWithLifecycle()
                    // Effective App Language (Reflect platform per-app language choices when Android owns the locale)
                    // The settings row should show a system-selected app locale even if DataStore still contains the default System value.
                    val effectiveAppLanguage = AppLocaleController.resolveEffectiveLanguage(context, settingsState.appLanguage)

                    Box(modifier = Modifier.fillMaxSize()) {
                        // Settings Private Haze Content (Keep the long-lived settings page registered only to its own sampler)
                        // The same local HazeState is shared with settings dialogs so no app-level page registration is needed.
                        Box(modifier = Modifier.fillMaxSize()) {
                            SettingsScreen(
                                onBack = { settingsViewModel.setVisible(false) },
                                libraryRootDisplays = libraryRootDisplays,
                                // Settings Dialog Intents (Forward page interactions to the overlay-owned dialog controller)
                                // The page no longer renders modal surfaces; it only announces which settings flow should open.
                                onRootClick = { settingsDialogController.dialogState = SettingsDialogState.RootActions(it) },
                                onAddLibraryClick = { settingsDialogController.dialogState = SettingsDialogState.AddLibraryType },
                                // Deleted Book Recovery Navigation (Open the local recovery sub-page without triggering scan or sync)
                                // This keeps the settings entry as pure navigation into the restore workflow.
                                onDeletedBookRecoveryClick = {
                                    activeSettingsPage = SettingsOverlayPage.DeletedBookRecovery
                                },
                                // Download & Cache Configuration Settings (Binds task list navigation, wifi policy switch, and buffer capacity)
                                downloadTaskCount = downloadTasks.size,
                                isDownloadWifiOnly = settingsState.isDownloadWifiOnly,
                                onDownloadWifiOnlyChange = { settingsViewModel.preferencesHandler.toggleDownloadWifiOnly(it) },
                                playbackBufferMaxBytes = settingsState.playbackBufferMaxBytes,
                                onPlaybackBufferMaxBytesChange = { settingsViewModel.preferencesHandler.updatePlaybackBufferMaxBytes(it) },
                                onDownloadManagementClick = {
                                    activeSettingsPage = SettingsOverlayPage.DownloadManagement
                                },
                                // Title: Delegate Settings Screen Updates (Route preference toggles and connection tests to handler parameters)
                                isChapterProgressMode = settingsState.isChapterProgressMode,
                                onChapterProgressModeChange = { settingsViewModel.preferencesHandler.toggleChapterProgressMode(it) },
                                isCleartextTrafficAllowed = settingsState.isCleartextTrafficAllowed,
                                onCleartextTrafficAllowedChange = { settingsViewModel.preferencesHandler.toggleCleartextTrafficAllowed(it) },
                                // Insecure TLS Config Link: Bind local settings state for allowing insecure TLS connections and its callback trigger.
                                isAllowInsecureTls = settingsState.isAllowInsecureTls,
                                onAllowInsecureTlsChange = { settingsViewModel.preferencesHandler.toggleAllowInsecureTls(it) },
                                isSkipSilenceEnabled = settingsState.isSkipSilenceEnabled,
                                onSkipSilenceEnabledChange = { settingsViewModel.preferencesHandler.toggleSkipSilenceEnabled(it) },
                                isSleepFadeOutEnabled = settingsState.isSleepFadeOutEnabled,
                                onSleepFadeOutEnabledChange = { settingsViewModel.preferencesHandler.toggleSleepFadeOutEnabled(it) },
                                isShakeToResetEnabled = settingsState.isShakeToResetEnabled,
                                onShakeToResetEnabledChange = { settingsViewModel.preferencesHandler.toggleShakeToResetEnabled(it) },
                                sleepMode = settingsState.sleepMode,
                                onSleepModeChange = { settingsViewModel.preferencesHandler.updateSleepMode(it) },
                                appLanguage = effectiveAppLanguage,
                                onLanguageClick = {
                                    settingsDialogController.dialogState = SettingsDialogState.LanguagePicker
                                },
                                themeMode = settingsState.themeMode,
                                onThemeModeChange = { settingsViewModel.preferencesHandler.updateThemeMode(it) },
                                // Pipe Dynamic Color Settings (Forward dynamic color configuration parameters to downstream SettingsScreen) Binds dynamic color state and callback.
                                isDynamicColorEnabled = settingsState.isDynamicColorEnabled,
                                onDynamicColorEnabledChange = { settingsViewModel.preferencesHandler.toggleDynamicColorEnabled(it) },
                                isAmoledEnabled = settingsState.isAmoledEnabled,
                                onAmoledEnabledChange = { settingsViewModel.preferencesHandler.toggleAmoledEnabled(it) },
                                glassEffectMode = settingsState.glassEffectMode,
                                settingsHazeState = if (isBlur) settingsHazeState else null,
                                onGlassEffectModeChange = { settingsViewModel.preferencesHandler.updateGlassEffectMode(it) },
                                autoRewindSeconds = settingsState.autoRewindSeconds,
                                onAutoRewindSecondsChange = { settingsViewModel.preferencesHandler.updateAutoRewindSeconds(it) },
                                playbackSeekStepConfig = settingsState.playbackSeekStepConfig,
                                onSeekBackwardStepChange = { settingsViewModel.preferencesHandler.updateSeekBackwardSeconds(it) },
                                onSeekForwardStepChange = { settingsViewModel.preferencesHandler.updateSeekForwardSeconds(it) },
                                isNotificationAvoidanceEnabled = settingsState.isNotificationAvoidanceEnabled,
                                onNotificationAvoidanceEnabledChange = { settingsViewModel.preferencesHandler.toggleNotificationAvoidanceEnabled(it) },
                                onExportClick = { exportLauncher.launch("aplayer_backup.zip") },
                                onImportClick = { importLauncher.launch(arrayOf("*/*")) },
                                onAboutLibrariesClick = { activeSettingsPage = SettingsOverlayPage.AboutLibraries }
                            )
                        }

                        // Settings Overlay Dialog Host (Render modal surfaces outside the sampled page subtree)
                        // Dialogs are short-lived, but they still sample the Settings-owned HazeState so the app shell does not own settings glass state.
                        SettingsDialogHost(
                            controller = settingsDialogController,
                            glassEffectMode = settingsState.glassEffectMode,
                            settingsDialogHazeState = if (isBlur) settingsHazeState else null,
                            appLanguage = effectiveAppLanguage,
                            onAppLanguageChange = { settingsViewModel.preferencesHandler.updateAppLanguage(it) },
                            webDavConnectionState = webDavConnectionState,
                            onWebDavConnectionTest = { url, username, password, basePath, editingRootId ->
                                settingsViewModel.connectionHandler.testWebDavConnection(url, username, password, basePath, editingRootId)
                            },
                            onResetWebDavConnectionState = {
                                settingsViewModel.connectionHandler.resetWebDavConnectionState()
                            },
                            onWebDavRootSubmitted = { url, username, password, displayName, basePath ->
                                settingsViewModel.connectionHandler.onWebDavRootSubmitted(url, username, password, displayName, basePath)
                            },
                            onWebDavRootUpdated = { id, url, username, password, displayName, basePath ->
                                settingsViewModel.updateWebDavRoot(id, url, username, password, displayName, basePath)
                            },
                            absConnectionState = absConnectionState,
                            onAbsConnectionTest = { baseUrl, username, password, editingRootId ->
                                settingsViewModel.connectionHandler.testAbsConnection(baseUrl, username, password, editingRootId)
                            },
                            onResetAbsConnectionState = {
                                settingsViewModel.connectionHandler.resetAbsConnectionState()
                            },
                            onAbsRootSubmitted = { baseUrl, username, password, libraryId, libraryName, editingRootId ->
                                settingsViewModel.connectionHandler.addAbsServerWithPassword(baseUrl, username, password, libraryId, libraryName, editingRootId)
                            },
                            getWebDavCredentials = { credentialId ->
                                settingsViewModel.connectionHandler.getWebDavCredentials(credentialId)
                            },
                            getAbsCredential = { credentialId ->
                                settingsViewModel.connectionHandler.getAbsCredential(credentialId)
                            },
                            onAbsSync = { rootId -> settingsViewModel.connectionHandler.syncAbsRoot(rootId) },
                            onRescan = { settingsViewModel.connectionHandler.triggerRescan() },
                            onDeleteLibraryRoot = { settingsViewModel.deleteLibraryRoot(it) },
                            onLaunchSafRootPicker = { libraryRootLauncher.launch(null) },
                            onImportConfirm = { uri -> settingsViewModel.importUserData(uri) }
                        )
                    }
                    }
                }
            }
        }
    }
}

/**
 * Settings Overlay Page (Local navigation state for pages hosted inside SettingsOverlay)
 * Avoids adding app-wide routes for settings-only panels while keeping back behavior explicit and testable.
 */
private enum class SettingsOverlayPage {
    // Main settings page
    Main,
    AboutLibraries,
    DeletedBookRecovery,
    DownloadManagement
}
