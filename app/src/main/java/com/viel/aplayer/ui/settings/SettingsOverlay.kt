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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.viel.aplayer.data.store.GlassEffectMode
import com.viel.aplayer.ui.settings.about.AboutLibrariesScreen
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

    // Settings Haze Source (Provide a local backdrop for settings chrome)
    // SettingsScreen registers its content layer with this state so the shared glass top bar samples settings content without changing the app-level dialog sampler.
    val settingsHazeState = remember { HazeState() }
    // About Haze Source (Separate license-page sampling from settings-page transitions)
    // AnimatedContent can keep Settings and About composed briefly at the same time, so About uses its own source to avoid competing registrations on one HazeState.
    val aboutHazeState = remember { HazeState() }
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
        // Track interior navigation state (To switch between settings main menu and licenses panel)
        // Decoupled sub-navigation boolean parameter to manage local transition switches.
        var showAboutLibraries by remember { mutableStateOf(false) }

        // Handle physical back gestures (To prevent closing MainActivity accidentally)
        // Intercepts Android back operations when SettingsOverlay is active.
        // If licenses page is shown, resets back to settings list; otherwise, hides the overlay.
        BackHandler(enabled = isVisible) {
            if (showAboutLibraries) {
                showAboutLibraries = false
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
            // Apply horizontal navigation transitions (To switch settings sub-screens fluidly)
            // Utilizes AnimatedContent to animate between main SettingsScreen and AboutLibrariesScreen with slide transitions.
            AnimatedContent(
                targetState = showAboutLibraries,
                transitionSpec = {
                    if (targetState) {
                        (slideInHorizontally(animationSpec = tween(300)) { width -> width } + fadeIn(animationSpec = tween(300)))
                            .togetherWith(slideOutHorizontally(animationSpec = tween(300)) { width -> -width } + fadeOut(animationSpec = tween(300)))
                    } else {
                        (slideInHorizontally(animationSpec = tween(300)) { width -> -width } + fadeIn(animationSpec = tween(300)))
                            .togetherWith(slideOutHorizontally(animationSpec = tween(300)) { width -> width } + fadeOut(animationSpec = tween(300)))
                    }
                },
                label = "AboutLibrariesTransition"
            ) { showAbout ->
                if (showAbout) {
                    AboutLibrariesScreen(
                        onBack = { showAboutLibraries = false },
                        glassEffectMode = glassEffectMode,
                        aboutHazeState = if (isBlur) aboutHazeState else null
                    )
                } else {
                    // Collect settings business data (To populate settings menu and capture actions)
                    // Listens to settings state parameters reactively from SettingsViewModel.
                    val settingsState by settingsViewModel.settingsState.collectAsStateWithLifecycle()
                    val libraryRootDisplays by settingsViewModel.libraryRootDisplays.collectAsStateWithLifecycle()
                    val absConnectionState by settingsViewModel.absConnectionState.collectAsStateWithLifecycle()
                    val webDavConnectionState by settingsViewModel.webDavConnectionState.collectAsStateWithLifecycle()

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
                                isChapterProgressMode = settingsState.isChapterProgressMode,
                                onChapterProgressModeChange = { settingsViewModel.toggleChapterProgressMode(it) },
                                isCleartextTrafficAllowed = settingsState.isCleartextTrafficAllowed,
                                onCleartextTrafficAllowedChange = { settingsViewModel.toggleCleartextTrafficAllowed(it) },
                                // Insecure TLS Config Link: Bind local settings state for allowing insecure TLS connections and its callback trigger.
                                isAllowInsecureTls = settingsState.isAllowInsecureTls,
                                onAllowInsecureTlsChange = { settingsViewModel.toggleAllowInsecureTls(it) },
                                isSkipSilenceEnabled = settingsState.isSkipSilenceEnabled,
                                onSkipSilenceEnabledChange = { settingsViewModel.toggleSkipSilenceEnabled(it) },
                                isSleepFadeOutEnabled = settingsState.isSleepFadeOutEnabled,
                                onSleepFadeOutEnabledChange = { settingsViewModel.toggleSleepFadeOutEnabled(it) },
                                isShakeToResetEnabled = settingsState.isShakeToResetEnabled,
                                onShakeToResetEnabledChange = { settingsViewModel.toggleShakeToResetEnabled(it) },
                                sleepMode = settingsState.sleepMode,
                                onSleepModeChange = { settingsViewModel.updateSleepMode(it) },
                                themeMode = settingsState.themeMode,
                                onThemeModeChange = { settingsViewModel.updateThemeMode(it) },
                                // Pipe Dynamic Color Settings (Forward dynamic color configuration parameters to downstream SettingsScreen) Binds dynamic color state and callback.
                                isDynamicColorEnabled = settingsState.isDynamicColorEnabled,
                                onDynamicColorEnabledChange = { settingsViewModel.toggleDynamicColorEnabled(it) },
                                glassEffectMode = settingsState.glassEffectMode,
                                settingsHazeState = if (isBlur) settingsHazeState else null,
                                onGlassEffectModeChange = { settingsViewModel.updateGlassEffectMode(it) },
                                autoRewindSeconds = settingsState.autoRewindSeconds,
                                onAutoRewindSecondsChange = { settingsViewModel.updateAutoRewindSeconds(it) },
                                isNotificationAvoidanceEnabled = settingsState.isNotificationAvoidanceEnabled,
                                onNotificationAvoidanceEnabledChange = { settingsViewModel.toggleNotificationAvoidanceEnabled(it) },
                                onAboutLibrariesClick = { showAboutLibraries = true }
                            )
                        }

                        // Settings Overlay Dialog Host (Render modal surfaces outside the settings hazeSource tree)
                        // Dialogs share the app-level sampler while SettingsScreen only registers page content for the glass top bar.
                        SettingsDialogHost(
                            controller = settingsDialogController,
                            glassEffectMode = settingsState.glassEffectMode,
                            settingsDialogHazeState = if (isBlur) appHazeState else null,
                            webDavConnectionState = webDavConnectionState,
                            onWebDavConnectionTest = { url, username, password, basePath, editingRootId ->
                                settingsViewModel.testWebDavConnection(url, username, password, basePath, editingRootId)
                            },
                            onResetWebDavConnectionState = {
                                settingsViewModel.resetWebDavConnectionState()
                            },
                            onWebDavRootSubmitted = { url, username, password, displayName, basePath ->
                                settingsViewModel.onWebDavRootSubmitted(url, username, password, displayName, basePath)
                            },
                            onWebDavRootUpdated = { id, url, username, password, displayName, basePath ->
                                settingsViewModel.updateWebDavRoot(id, url, username, password, displayName, basePath)
                            },
                            absConnectionState = absConnectionState,
                            onAbsConnectionTest = { baseUrl, username, password, editingRootId ->
                                settingsViewModel.testAbsConnection(baseUrl, username, password, editingRootId)
                            },
                            onResetAbsConnectionState = {
                                settingsViewModel.resetAbsConnectionState()
                            },
                            onAbsRootSubmitted = { baseUrl, username, password, libraryId, libraryName, editingRootId ->
                                settingsViewModel.addAbsServerWithPassword(baseUrl, username, password, libraryId, libraryName, editingRootId)
                            },
                            getWebDavCredentials = { credentialId ->
                                settingsViewModel.getWebDavCredentials(credentialId)
                            },
                            getAbsCredential = { credentialId ->
                                settingsViewModel.getAbsCredential(credentialId)
                            },
                            onAbsSync = { rootId -> settingsViewModel.syncAbsRoot(rootId) },
                            onRescan = { settingsViewModel.triggerRescan() },
                            onDeleteLibraryRoot = { settingsViewModel.deleteLibraryRoot(it) },
                            onLaunchSafRootPicker = { libraryRootLauncher.launch(null) }
                        )
                    }
                }
            }
        }
    }
}
