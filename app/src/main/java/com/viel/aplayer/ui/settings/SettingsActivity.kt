package com.viel.aplayer.ui.settings

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.viel.aplayer.ui.common.UiEvent
import com.viel.aplayer.ui.common.theme.APlayerTheme
import com.viel.aplayer.ui.settings.about.AboutLibrariesScreen

/**
 * Settings activity host (Lifecycle and state flow carrier)
 * This independent Activity acts as a stateful container shell for the settings page.
 * The detailed UI rendering logic has been decoupled into [SettingsScreen.kt].
 * This class focuses purely on SettingsViewModel state subscriptions and routing callbacks.
 */
class SettingsActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Enable edge-to-edge (To maintain visual consistency with MainActivity)
        // Invokes enableEdgeToEdge to provide an immersive full-screen experience.
        enableEdgeToEdge()

        setContent {
            APlayerTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val settingsViewModel: SettingsViewModel = viewModel()
                    val settingsState by settingsViewModel.settingsState.collectAsStateWithLifecycle()
                    val libraryRoots by settingsViewModel.libraryRoots.collectAsStateWithLifecycle()
                    val absServers by settingsViewModel.absServers.collectAsStateWithLifecycle()
                    val absConnectionState by settingsViewModel.absConnectionState.collectAsStateWithLifecycle()
                    // Settings screen mapping logic: Observe WebDAV connection state using lifecycle-aware state collectors.
                    val webDavConnectionState by settingsViewModel.webDavConnectionState.collectAsStateWithLifecycle()
                    val absSyncConfirmationState by settingsViewModel.absSyncConfirmationState.collectAsStateWithLifecycle()
                    val context = LocalContext.current

                    LaunchedEffect(Unit) {
                        settingsViewModel.uiEvents.collect { event ->
                            when (event) {
                                is UiEvent.ShowToast -> Toast.makeText(context, event.message, Toast.LENGTH_SHORT).show()
                                else -> {}
                            }
                        }
                    }

                    // Track UI navigation state (To toggle between settings main page and licenses page)
                    // Maintains a boolean state to drive display switching between SettingsScreen and AboutLibrariesScreen.
                    var showAboutLibraries by remember { mutableStateOf(false) }

                    // Handle system back navigation (To prevent closing settings screen when leaving licenses page)
                    // Intercepts the back gesture using BackHandler; resets showAboutLibraries to false instead of finishing activity.
                    BackHandler(enabled = showAboutLibraries) {
                        showAboutLibraries = false
                    }

                    // Apply navigation transitions (To provide visual feedback during transition)
                    // Uses AnimatedContent to animate sliding right for AboutLibrariesScreen and sliding left for SettingsScreen.
                    AnimatedContent(
                        targetState = showAboutLibraries,
                        transitionSpec = {
                            if (targetState) {
                                (slideInHorizontally { width -> width } + fadeIn())
                                    .togetherWith(slideOutHorizontally { width -> -width } + fadeOut())
                            } else {
                                (slideInHorizontally { width -> -width } + fadeIn())
                                    .togetherWith(slideOutHorizontally { width -> width } + fadeOut())
                            }
                        },
                        label = "AboutLibrariesTransition"
                    ) { showAbout ->
                        if (showAbout) {
                            AboutLibrariesScreen(
                                onBack = { showAboutLibraries = false }
                            )
                        } else {
                            SettingsScreen(
                                // Terminate settings activity (To return to previous screen with transition)
                                // Calls finish() to close the Activity, triggering system-defined exit animations.
                                onBack = { finish() },
                                // Dispatch SAF authorization callbacks (To update directory registry under isolated scope)
                                // Delegates SAF folder selection to SettingsViewModel directly, bypassing LibraryViewModel initialization.
                                onLibraryRootSelected = { uri -> settingsViewModel.onLibraryRootSelected(uri) },
                                // Settings screen mapping logic: Bind newly created settings actions and data source getters to SettingsViewModel.
                                onSafRootRelocated = { id, uri -> settingsViewModel.onSafRootRelocated(id, uri) },
                                // Dispatch WebDAV root submission (To register remote WebDAV folders under settings ViewModel)
                                // Forwards url, username, password, display name, and base path to SettingsViewModel.
                                onWebDavRootSubmitted = { url, username, password, displayName, basePath ->
                                    settingsViewModel.onWebDavRootSubmitted(url, username, password, displayName, basePath)
                                },
                                onWebDavRootUpdated = { id, url, username, password, displayName, basePath ->
                                    settingsViewModel.updateWebDavRoot(id, url, username, password, displayName, basePath)
                                },
                                // Settings screen mapping logic: Forward WebDAV verification states and reset hooks to SettingsViewModel.
                                webDavConnectionState = webDavConnectionState,
                                onWebDavConnectionTest = { url, username, password, basePath, editingRootId ->
                                    settingsViewModel.testWebDavConnection(url, username, password, basePath, editingRootId)
                                },
                                onResetWebDavConnectionState = {
                                    settingsViewModel.resetWebDavConnectionState()
                                },
                                // Settings screen mapping logic: Forward ABS verification reset hooks to SettingsViewModel.
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
                                onAbsBackgroundSync = { rootId ->
                                    settingsViewModel.scheduleAbsRootSync(rootId)
                                },
                                absSyncConfirmationState = absSyncConfirmationState,
                                onDismissLargeAbsSync = {
                                    settingsViewModel.dismissLargeAbsSyncConfirmation()
                                },
                                onRescan = { settingsViewModel.triggerRescan() },
                                libraryRoots = libraryRoots,
                                absServers = absServers,
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
                                // Pass sleep mode configuration (To bind local DataStore state to UI component)
                                // Binds current sleepMode from settingsState and updates it via SettingsViewModel callbacks.
                                sleepMode = settingsState.sleepMode,
                                onSleepModeChange = { settingsViewModel.updateSleepMode(it) },
                                // Pass glass effect configuration (To bind local DataStore state to UI component)
                                // Binds current glassEffectMode from settingsState and updates it via SettingsViewModel callbacks.
                                glassEffectMode = settingsState.glassEffectMode,
                                onGlassEffectModeChange = { settingsViewModel.updateGlassEffectMode(it) },
                                // Pass auto-rewind configuration (To bind local DataStore state to UI component)
                                // Binds autoRewindSeconds and writes user updates back to DataStore via SettingsViewModel.
                                autoRewindSeconds = settingsState.autoRewindSeconds,
                                onAutoRewindSecondsChange = { settingsViewModel.updateAutoRewindSeconds(it) },
                                // Pass notification avoidance configuration (To bind local DataStore state to UI component)
                                // Binds isNotificationAvoidanceEnabled and writes updates back to DataStore via SettingsViewModel.
                                isNotificationAvoidanceEnabled = settingsState.isNotificationAvoidanceEnabled,
                                onNotificationAvoidanceEnabledChange = { settingsViewModel.toggleNotificationAvoidanceEnabled(it) },
                                // Toggle license viewer (To navigate to open source libraries panel)
                                // Sets showAboutLibraries to true to trigger the horizontal sliding animation.
                                onAboutLibrariesClick = { showAboutLibraries = true }
                            )
                        }
                    }
                }
            }
        }
    }

    companion object {
        /**
         * Create intent factory (To provide a standardized entry point for SettingsActivity)
         * Constructs a launch Intent bound to SettingsActivity.
         */
        fun createIntent(context: Context): Intent =
            Intent(context, SettingsActivity::class.java)
    }
}
