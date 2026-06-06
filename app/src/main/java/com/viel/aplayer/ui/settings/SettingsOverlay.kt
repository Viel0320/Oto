package com.viel.aplayer.ui.settings

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
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
    glassEffectMode: GlassEffectMode
) {
    // Collect settings visibility state (To reactively trigger transition animations)
    // Synchronizes the show/hide state from SettingsViewModel to drive AnimatedVisibility scope.
    val isVisible by settingsViewModel.isVisible.collectAsStateWithLifecycle()

    // Settings Haze Source (Provide a local backdrop for settings-owned dialogs)
    // Registers the settings surface itself as the blur source so Settings dialogs do not sample stale Home/Search content behind the overlay.
    val settingsHazeState = remember { HazeState() }
    val isBlur = glassEffectMode == GlassEffectMode.Haze

    AnimatedVisibility(
        visible = isVisible,
        enter = if (isBlur) {
            fadeIn(animationSpec = tween(300))
        } else {
            slideInVertically(initialOffsetY = { it }, animationSpec = tween(300)) + fadeIn(animationSpec = tween(300))
        },
        exit = if (isBlur) {
            fadeOut(animationSpec = tween(300))
        } else {
            slideOutVertically(targetOffsetY = { it }, animationSpec = tween(300)) + fadeOut(animationSpec = tween(300))
        },
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
            modifier = Modifier
                .fillMaxSize()
                .then(
                    if (isBlur) {
                        Modifier.hazeSource(settingsHazeState)
                    } else {
                        Modifier
                    }
                ),
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
                        onBack = { showAboutLibraries = false }
                    )
                } else {
                    // Collect settings business data (To populate settings menu and capture actions)
                    // Listens to settings state parameters reactively from SettingsViewModel.
                    val settingsState by settingsViewModel.settingsState.collectAsStateWithLifecycle()
                    val libraryRootDisplays by settingsViewModel.libraryRootDisplays.collectAsStateWithLifecycle()
                    val absConnectionState by settingsViewModel.absConnectionState.collectAsStateWithLifecycle()
                    val webDavConnectionState by settingsViewModel.webDavConnectionState.collectAsStateWithLifecycle()

                    SettingsScreen(
                        onBack = { settingsViewModel.setVisible(false) },
                        onLibraryRootSelected = { uri -> settingsViewModel.onLibraryRootSelected(uri) },
                        onSafRootRelocated = { id, uri -> settingsViewModel.onSafRootRelocated(id, uri) },
                        onWebDavRootSubmitted = { url, username, password, displayName, basePath ->
                            settingsViewModel.onWebDavRootSubmitted(url, username, password, displayName, basePath)
                        },
                        onWebDavRootUpdated = { id, url, username, password, displayName, basePath ->
                            settingsViewModel.updateWebDavRoot(id, url, username, password, displayName, basePath)
                        },
                        webDavConnectionState = webDavConnectionState,
                        onWebDavConnectionTest = { url, username, password, basePath, editingRootId ->
                            settingsViewModel.testWebDavConnection(url, username, password, basePath, editingRootId)
                        },
                        onResetWebDavConnectionState = {
                            settingsViewModel.resetWebDavConnectionState()
                        },
                        onResetAbsConnectionState = {
                            settingsViewModel.resetAbsConnectionState()
                        },
                        onAbsConnectionTest = { baseUrl, username, password, editingRootId ->
                            settingsViewModel.testAbsConnection(baseUrl, username, password, editingRootId)
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
                        onAbsSync = { rootId ->
                            settingsViewModel.syncAbsRoot(rootId)
                        },
                        onRescan = { settingsViewModel.triggerRescan() },
                        libraryRootDisplays = libraryRootDisplays,
                        absConnectionState = absConnectionState,
                        isChapterProgressMode = settingsState.isChapterProgressMode,
                        onChapterProgressModeChange = { settingsViewModel.toggleChapterProgressMode(it) },
                        isCleartextTrafficAllowed = settingsState.isCleartextTrafficAllowed,
                        onCleartextTrafficAllowedChange = { settingsViewModel.toggleCleartextTrafficAllowed(it) },
                        onDeleteLibraryRoot = { settingsViewModel.deleteLibraryRoot(it) },
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
                        glassEffectMode = settingsState.glassEffectMode,
                        settingsDialogHazeState = if (isBlur) settingsHazeState else null,
                        onGlassEffectModeChange = { settingsViewModel.updateGlassEffectMode(it) },
                        autoRewindSeconds = settingsState.autoRewindSeconds,
                        onAutoRewindSecondsChange = { settingsViewModel.updateAutoRewindSeconds(it) },
                        isNotificationAvoidanceEnabled = settingsState.isNotificationAvoidanceEnabled,
                        onNotificationAvoidanceEnabledChange = { settingsViewModel.toggleNotificationAvoidanceEnabled(it) },
                        onAboutLibrariesClick = { showAboutLibraries = true }
                    )
                }
            }
        }
    }
}
