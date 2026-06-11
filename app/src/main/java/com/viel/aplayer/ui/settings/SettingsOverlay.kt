package com.viel.aplayer.ui.settings

import androidx.activity.compose.BackHandler
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.viel.aplayer.data.store.GlassEffectMode
import com.viel.aplayer.i18n.AppLocaleController
import com.viel.aplayer.ui.settings.about.AboutLibrariesScreen
import com.viel.aplayer.ui.settings.recovery.DeletedBookRecoveryRoute
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource

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
    // App-Level Dialog Haze Source (Stable sampler for settings dialogs)
    // Settings page chrome still uses settingsHazeState, but modal dialogs share the app-level source used by Search and playback overlays.
    appHazeState: HazeState? = null
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
    // Local sub-pages can overlap during AnimatedContent transitions, so the recovery list owns its own top-bar sampler.
    val deletedRecoveryHazeState = remember { HazeState() }
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
        // Settings Sub-Page Navigation (Track local overlay destinations without expanding the app navigation graph)
        // Settings, About, and Deleted Book Recovery remain inside one overlay lifecycle while each page keeps its own UI state.
        var activeSettingsPage by remember { mutableStateOf(SettingsOverlayPage.Main) }

        // Handle physical back gestures (To prevent closing MainActivity accidentally)
        // Intercepts Android back operations when SettingsOverlay is active.
        // If a settings sub-page is shown, resets back to settings list; otherwise, hides the overlay.
        BackHandler(enabled = isVisible) {
            if (activeSettingsPage != SettingsOverlayPage.Main) {
                activeSettingsPage = SettingsOverlayPage.Main
            } else {
                settingsViewModel.setVisible(false)
            }
        }

        Surface(
            // Settings Overlay Surface Boundary (Leave Haze source ownership to screen content)
            // SettingsScreen now registers its content Scaffold as the blur source so the overlay top bar samples settings content without capturing its own chrome.
            modifier = Modifier.fillMaxSize(),
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
                    SettingsOverlayPage.Main -> {
                    // Collect settings business data (To populate settings menu and capture actions)
                    // Listens to settings state parameters reactively from SettingsViewModel.
                    val settingsState by settingsViewModel.settingsState.collectAsStateWithLifecycle()
                    val libraryRootDisplays by settingsViewModel.libraryRootDisplays.collectAsStateWithLifecycle()
                    val absConnectionState by settingsViewModel.absConnectionState.collectAsStateWithLifecycle()
                    val webDavConnectionState by settingsViewModel.webDavConnectionState.collectAsStateWithLifecycle()
                    // Effective App Language (Reflect platform per-app language choices when Android owns the locale)
                    // The settings row should show a system-selected app locale even if DataStore still contains the default System value.
                    val effectiveAppLanguage = AppLocaleController.resolveEffectiveLanguage(context, settingsState.appLanguage)

                    Box(modifier = Modifier.fillMaxSize()) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .then(
                                    if (isBlur && appHazeState != null) {
                                        // Settings App-Level Source Registration (Expose settings content to global modal samplers)
                                        // Dialogs use appHazeState outside this Box, so registering only the page subtree prevents sampling through to Home without including the dialogs themselves.
                                        Modifier.hazeSource(appHazeState)
                                    } else {
                                        Modifier
                                    }
                                )
                        ) {
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
                                onAboutLibrariesClick = { activeSettingsPage = SettingsOverlayPage.AboutLibraries }
                            )
                        }

                        // Settings Overlay Dialog Host (Render modal surfaces outside the settings hazeSource tree)
                        // Dialogs share the app-level sampler while SettingsScreen only registers page content for the glass top bar.
                        SettingsDialogHost(
                            controller = settingsDialogController,
                            glassEffectMode = settingsState.glassEffectMode,
                            settingsDialogHazeState = if (isBlur) appHazeState else null,
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
                            onLaunchSafRootPicker = { libraryRootLauncher.launch(null) }
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
    Main,
    AboutLibraries,
    DeletedBookRecovery
}
