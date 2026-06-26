package com.viel.oto.ui.settings

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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mikepenz.aboutlibraries.entity.Library
import com.viel.oto.application.library.settings.SettingsRootItem
import com.viel.oto.i18n.AppLocaleController
import com.viel.oto.shared.settings.GlassEffectMode
import com.viel.oto.ui.common.uiPerformanceTrace
import com.viel.oto.ui.settings.about.AboutLibrariesScreen
import com.viel.oto.ui.settings.downloads.DownloadManagementScreen
import com.viel.oto.ui.settings.recovery.DeletedBookRecoveryRoute
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel

/**
 * Stateless Settings Overlay Shell.
 *
 * Hosts the settings overlay interface inside MainActivity, completely replacing the independent SettingsActivity.
 * Controls visual presentation through fade-in and slide-in transitions, maintaining premium layout animations.
 * Manual download retry dispatches the cache command immediately, then requests notification permission only
 * for progress visibility so denial cannot block offline caching. About metadata is forwarded from
 * the app shell so this overlay can remain portable when the UI package becomes its own module.
 */
@Composable
fun SettingsOverlay(
    modifier: Modifier = Modifier,
    appVersionName: String = "unknown",
    aboutLibraries: List<Library>? = emptyList(),
    settingsViewModel: SettingsViewModel = koinViewModel(),
    glassEffectMode: GlassEffectMode,
    openDownloadManagementRequest: Boolean = false,
    onOpenDownloadManagementConsumed: () -> Unit = {},
    onRequestAddLibrary: () -> Unit = {},
    onRequestRootActions: (SettingsRootItem) -> Unit = {},
    appHazeState: HazeState? = null
) {
    val isVisible by settingsViewModel.isVisible.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val settingsHazeState = remember { HazeState() }
    val aboutHazeState = remember { HazeState() }
    val deletedRecoveryHazeState = remember { HazeState() }
    val downloadManagementHazeState = remember { HazeState() }
    val isBlur = glassEffectMode == GlassEffectMode.Haze
    val scope = rememberCoroutineScope()
    val settingsDialogController = rememberSettingsDialogController()

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
        uri?.let { pickedUri ->
            scope.launch {
                val manifest = settingsViewModel.peekImportManifest(pickedUri)
                settingsDialogController.dialogState =
                    SettingsDialogState.ImportConfirm(pickedUri, manifest)
            }
        }
    }
    val downloadPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            settingsViewModel.onDownloadNotificationPermissionDenied()
        }
    }
    val requestSettingsDownload: (String) -> Unit = { bookId ->
        val notificationPermissionGranted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        settingsViewModel.downloadBook(bookId)
        if (!notificationPermissionGranted) {
            downloadPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    LaunchedEffect(isVisible) {
        if (!isVisible) {
            settingsDialogController.dialogState = SettingsDialogState.None
        }
    }

    AnimatedVisibility(
        visible = isVisible,
        enter = slideInVertically(initialOffsetY = { it }, animationSpec = tween(300)) + fadeIn(animationSpec = tween(300)),
        exit = slideOutVertically(targetOffsetY = { it }, animationSpec = tween(300)) + fadeOut(animationSpec = tween(300)),
        modifier = modifier
    ) {
        var activeSettingsPage by remember { mutableStateOf(SettingsOverlayPage.Main) }

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
                activeSettingsPage = SettingsOverlayPage.DownloadManagement
                onOpenDownloadManagementConsumed()
            }
        }

        androidx.activity.compose.PredictiveBackHandler(
            enabled = isVisible && activeSettingsPage != SettingsOverlayPage.Main
        ) { progressFlow ->
            try {
                progressFlow.collect { }
                activeSettingsPage = SettingsOverlayPage.Main
            } catch (_: kotlin.coroutines.cancellation.CancellationException) {
            }
        }

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
            } finally {
                isPredictiveBackActive = false
                predictiveBackProgress = 0f
            }
        }

        val maxPredictiveTranslationY = with(density) { 200.dp.toPx() }
        val settingsTraceState = "visible=$isVisible,page=$activeSettingsPage," +
            "predictiveBack=$isPredictiveBackActive"

        Surface(
            modifier = Modifier
                .fillMaxSize()
                .then(
                    if (isBlur && appHazeState != null) Modifier.hazeSource(appHazeState) else Modifier
                )
                .uiPerformanceTrace(
                    node = "SettingsOverlay",
                    route = "Settings",
                    state = settingsTraceState
                )
                .graphicsLayer {
                    if (isPredictiveBackActive) {
                        translationY = predictiveBackProgress * maxPredictiveTranslationY
                        alpha = 1f - predictiveBackProgress * 0.3f
                    }
                }
                .clip(RoundedCornerShape(topStart = cornerRadiusDp, topEnd = cornerRadiusDp)),
            color = MaterialTheme.colorScheme.background
        ) {
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
                            appVersionName = appVersionName,
                            libraries = aboutLibraries,
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
                        val downloadTasks by settingsViewModel.downloadTasks.collectAsStateWithLifecycle()
                        DownloadManagementScreen(
                            tasks = downloadTasks,
                            onBack = { activeSettingsPage = SettingsOverlayPage.Main },
                            onPauseDownload = settingsViewModel::pauseDownload,
                            onResumeDownload = settingsViewModel::resumeDownload,
                            onRetryDownload = requestSettingsDownload,
                            onDeleteDownload = settingsViewModel::deleteDownload,
                            onDeleteAllDownloads = settingsViewModel::deleteAllDownloads,
                            glassEffectMode = glassEffectMode,
                            downloadHazeState = if (isBlur) downloadManagementHazeState else null
                        )
                    }

                    SettingsOverlayPage.Main -> {
                    val settingsState by settingsViewModel.settingsState.collectAsStateWithLifecycle()
                    val libraryRootDisplays by settingsViewModel.libraryRootDisplays.collectAsStateWithLifecycle()
                    val downloadTasks by settingsViewModel.downloadTasks.collectAsStateWithLifecycle()
                    val effectiveAppLanguage = AppLocaleController.resolveEffectiveLanguage(context, settingsState.appLanguage)

                    Box(modifier = Modifier.fillMaxSize()) {
                        Box(modifier = Modifier.fillMaxSize()) {
                            SettingsScreen(
                                onBack = { settingsViewModel.setVisible(false) },
                                libraryRootDisplays = libraryRootDisplays,
                                onRootClick = onRequestRootActions,
                                onAddLibraryClick = onRequestAddLibrary,
                                onDeletedBookRecoveryClick = {
                                    activeSettingsPage = SettingsOverlayPage.DeletedBookRecovery
                                },
                                downloadTaskCount = downloadTasks.size,
                                isDownloadWifiOnly = settingsState.isDownloadWifiOnly,
                                onDownloadWifiOnlyChange = { settingsViewModel.preferencesHandler.toggleDownloadWifiOnly(it) },
                                onDownloadManagementClick = {
                                    activeSettingsPage = SettingsOverlayPage.DownloadManagement
                                },
                                isChapterProgressMode = settingsState.isChapterProgressMode,
                                onChapterProgressModeChange = { settingsViewModel.preferencesHandler.toggleChapterProgressMode(it) },
                                isCleartextTrafficAllowed = settingsState.isCleartextTrafficAllowed,
                                onCleartextTrafficAllowedChange = { settingsViewModel.preferencesHandler.toggleCleartextTrafficAllowed(it) },
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
                                onExportClick = {
                                    val timestamp = java.time.LocalDateTime.now()
                                        .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
                                    exportLauncher.launch("oto_backup_$timestamp.zip")
                                },
                                onImportClick = { importLauncher.launch(arrayOf("*/*")) },
                                onAboutLibrariesClick = { activeSettingsPage = SettingsOverlayPage.AboutLibraries }
                            )
                        }

                        SettingsDialogHost(
                            controller = settingsDialogController,
                            glassEffectMode = settingsState.glassEffectMode,
                            settingsDialogHazeState = if (isBlur) settingsHazeState else null,
                            appLanguage = effectiveAppLanguage,
                            onAppLanguageChange = { settingsViewModel.preferencesHandler.updateAppLanguage(it) },
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
 * Local navigation state for pages hosted inside SettingsOverlay.
 * Avoids adding app-wide routes for settings-only panels while keeping back behavior explicit and testable.
 */
private enum class SettingsOverlayPage {
    Main,
    AboutLibraries,
    DeletedBookRecovery,
    DownloadManagement
}
