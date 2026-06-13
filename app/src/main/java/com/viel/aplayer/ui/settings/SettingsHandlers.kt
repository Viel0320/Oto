package com.viel.aplayer.ui.settings

import android.app.Application
import com.viel.aplayer.R
import com.viel.aplayer.application.library.settings.AppSettingsCommands
import com.viel.aplayer.application.library.settings.SettingsAbsSyncInspection
import com.viel.aplayer.application.library.settings.SettingsCredential
import com.viel.aplayer.application.library.settings.SettingsRootCommands
import com.viel.aplayer.application.usecase.AbsConnectionReuseSnapshot
import com.viel.aplayer.application.usecase.AbsSettingsConnectionUseCase
import com.viel.aplayer.application.usecase.SettingsQueryUseCase
import com.viel.aplayer.application.usecase.TestWebDavConnectionUseCase
import com.viel.aplayer.data.store.AppLanguage
import com.viel.aplayer.data.store.GlassEffectMode
import com.viel.aplayer.data.store.SeekStepSeconds
import com.viel.aplayer.data.store.SleepMode
import com.viel.aplayer.data.store.ThemeMode
import com.viel.aplayer.event.AppEventSink
import com.viel.aplayer.event.feedback.FeedbackMessages
import com.viel.aplayer.i18n.AppLocaleController
import com.viel.aplayer.logger.AbsSettingsLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Settings Preferences Handler (Manages local configuration modification tasks)
 * Coordinates properties mutations through AppSettingsRepository inside a dedicated view coroutine scope.
 */
// Title: SettingsPreferencesHandler Decoupling (Shift SettingsPreferencesHandler dependencies to AppSettingsCommands)
// Replacing AppSettingsRepository with AppSettingsCommands interface to enforce clean architecture constraints.
class SettingsPreferencesHandler(
    private val settingsCommands: AppSettingsCommands,
    private val scope: CoroutineScope,
    private val app: Application
) {
    // Title: Toggle Chapter Progress Mode (Updates persistence regarding chapter-level elapsed tracking)
    fun toggleChapterProgressMode(enabled: Boolean) {
        scope.launch {
            settingsCommands.updateChapterProgressMode(enabled)
        }
    }

    // Title: Toggle Allow Insecure TLS (Updates persistence configuration for accepting untrusted HTTPS certificates)
    fun toggleAllowInsecureTls(enabled: Boolean) {
        scope.launch {
            settingsCommands.updateAllowInsecureTls(enabled)
        }
    }

    // Title: Toggle Cleartext Traffic Allowed (Updates persistence configurations for plain HTTP networking)
    fun toggleCleartextTrafficAllowed(enabled: Boolean) {
        scope.launch {
            settingsCommands.updateCleartextTrafficAllowed(enabled)
        }
    }

    // Title: Toggle Skip Silence (Updates persistent configuration for automatic silence skipping in playback)
    fun toggleSkipSilenceEnabled(enabled: Boolean) {
        scope.launch {
            settingsCommands.updateSkipSilenceEnabled(enabled)
        }
    }

    // Title: Toggle Sleep Fade Out (Updates sleep timer fade configuration state)
    fun toggleSleepFadeOutEnabled(enabled: Boolean) {
        scope.launch {
            settingsCommands.updateSleepFadeOutEnabled(enabled)
        }
    }

    // Title: Toggle Shake To Reset (Updates accelerometer shaking-reset behavior preferences)
    fun toggleShakeToResetEnabled(enabled: Boolean) {
        scope.launch {
            settingsCommands.updateShakeToResetEnabled(enabled)
        }
    }

    // Title: Update Sleep Mode (Updates persistent sleep timer options)
    fun updateSleepMode(mode: SleepMode) {
        scope.launch {
            settingsCommands.updateSleepMode(mode)
        }
    }

    // Title: Update Glass Effect Mode (Updates graphic container glassmorphism blur settings)
    fun updateGlassEffectMode(mode: GlassEffectMode) {
        scope.launch {
            settingsCommands.updateGlassEffectMode(mode)
        }
    }

    // Title: Update Theme Mode (Updates persistent theme preference configurations)
    fun updateThemeMode(mode: ThemeMode) {
        scope.launch {
            settingsCommands.updateThemeMode(mode)
        }
    }

    // Title: Update App Language (Persist the language config and trigger local activity locale switches)
    fun updateAppLanguage(language: AppLanguage) {
        scope.launch {
            settingsCommands.updateAppLanguage(language)
            AppLocaleController.applyPlatformLocale(app, language)
        }
    }

    // Title: Update Auto Rewind Seconds (Updates pause rewind displacement specifications)
    fun updateAutoRewindSeconds(seconds: Int) {
        scope.launch {
            settingsCommands.updateAutoRewindSeconds(seconds)
        }
    }

    // Title: Update Seek Backward Seconds (Updates transport seek step configuration backward)
    fun updateSeekBackwardSeconds(step: SeekStepSeconds) {
        scope.launch {
            settingsCommands.updateSeekBackwardSeconds(step)
        }
    }

    // Title: Update Seek Forward Seconds (Updates transport seek step configuration forward)
    fun updateSeekForwardSeconds(step: SeekStepSeconds) {
        scope.launch {
            settingsCommands.updateSeekForwardSeconds(step)
        }
    }

    // Title: Toggle Notification Avoidance (Updates persistent avoidance preference for audio notifications)
    fun toggleNotificationAvoidanceEnabled(enabled: Boolean) {
        scope.launch {
            settingsCommands.updateNotificationAvoidanceEnabled(enabled)
        }
    }

    // Title: Toggle Dynamic Color (Updates dynamic wallpaper-based color options)
    fun toggleDynamicColorEnabled(enabled: Boolean) {
        scope.launch {
            settingsCommands.updateDynamicColorEnabled(enabled)
        }
    }
}

/**
 * Settings Connection Handler (Encapsulates remote ABS and WebDAV connection verification tasks)
 * Maintains UI state flows for verification loading and dispatches command calls safely.
 */
// Title: Settings Connection Handler Decoupling (Shift connection handler dependencies to TestWebDavConnectionUseCase)
// Replacing WebDavConnectionTester and query methods with TestWebDavConnectionUseCase for testing.
class SettingsConnectionHandler(
    private val absSettingsConnectionUseCase: AbsSettingsConnectionUseCase,
    private val testWebDavConnectionUseCase: TestWebDavConnectionUseCase,
    private val settingsQueryUseCase: SettingsQueryUseCase,
    private val settingsRootCommands: SettingsRootCommands,
    // Title: Add FormatSettingsRootUseCase (Provide the format helper usecase to parse remote connection failure messages)
    private val formatSettingsRootUseCase: com.viel.aplayer.application.usecase.FormatSettingsRootUseCase,
    private val appEventSink: AppEventSink,
    private val scope: CoroutineScope,
    private val app: Application
) {
    // UI state flows
    private val _absConnectionState = MutableStateFlow(AbsConnectionUiState())
    val absConnectionState: StateFlow<AbsConnectionUiState> = _absConnectionState.asStateFlow()

    private val _webDavConnectionState = MutableStateFlow(WebDavConnectionUiState())
    val webDavConnectionState: StateFlow<WebDavConnectionUiState> = _webDavConnectionState.asStateFlow()

    private val _absSyncConfirmationState = MutableStateFlow<AbsSyncConfirmationState?>(null)

    // Title: Cache Connection Snapshot (Retain login token metadata temporarily to avoid redundant handshakes)
    private var lastSuccessfulAbsConnection: AbsConnectionReuseSnapshot? = null

    // Title: Test WebDAV Connection (Submit verification requests via UseCase and map results to states)
    fun testWebDavConnection(
        url: String,
        username: String,
        password: String,
        basePath: String,
        editingRootId: String? = null
    ) {
        scope.launch {
            _webDavConnectionState.value = WebDavConnectionUiState(isTesting = true)
            runCatching {
                testWebDavConnectionUseCase.execute(
                    url = url,
                    username = username,
                    password = password,
                    basePath = basePath,
                    editingRootId = editingRootId
                )
            }.onSuccess {
                _webDavConnectionState.value = WebDavConnectionUiState(isTesting = false, testSucceeded = true)
                appEventSink.showToast(FeedbackMessages.settingsWebDavConnectionSucceeded())
            }.onFailure { error ->
                // Title: Map WebDAV Connection Failure (Delegate parsing logic to FormatSettingsRootUseCase)
                val friendlyMessage = formatSettingsRootUseCase.resolveConnectionFailureMessage(error)
                _webDavConnectionState.value = WebDavConnectionUiState(isTesting = false, testSucceeded = false, lastError = friendlyMessage)
                appEventSink.showToast(FeedbackMessages.settingsWebDavConnectionFailed(friendlyMessage))
            }
        }
    }

    // Title: Reset WebDAV Connection State (Restores default idle state for WebDAV form dialogs)
    fun resetWebDavConnectionState() {
        _webDavConnectionState.value = WebDavConnectionUiState()
    }

    // Title: Submit WebDAV Root (Register WebDAV endpoint and schedule background import tasks)
    fun onWebDavRootSubmitted(
        url: String,
        username: String,
        password: String,
        displayName: String,
        basePath: String
    ) {
        settingsRootCommands.addWebDavRootAndScheduleSync(
            url = url,
            username = username,
            password = password,
            displayName = displayName,
            basePath = basePath
        )
    }

    // Title: Retrieve WebDAV Credentials (Delegate credential searches and map to SettingsCredential)
    // Avoids exposing WebDavCredential to UI or ViewModel layers directly.
    fun getWebDavCredentials(credentialId: String?): SettingsCredential? {
        val cred = settingsQueryUseCase.getWebDavCredential(credentialId) ?: return null
        return SettingsCredential(username = cred.username, password = cred.password)
    }

    // Title: Retrieve ABS Credentials (Delegate credential searches and map to SettingsCredential)
    // Avoids exposing AbsCredential to UI or ViewModel layers directly.
    suspend fun getAbsCredential(credentialId: String?): SettingsCredential? {
        val cred = settingsQueryUseCase.getAbsCredential(credentialId) ?: return null
        return SettingsCredential(username = cred.username ?: "", password = "")
    }

    // Title: Add ABS Server With Password (Verify, save, and launch synchronization for new remote servers)
    fun addAbsServerWithPassword(
        baseUrl: String,
        username: String,
        password: String,
        libraryId: String,
        libraryName: String,
        editingRootId: String? = null
    ) {
        scope.launch {
            AbsSettingsLogger.logAddServerStart(baseUrl, username, libraryId, libraryName)
            runCatching {
                absSettingsConnectionUseCase.saveServer(
                    baseUrl = baseUrl,
                    username = username,
                    password = password,
                    libraryId = libraryId,
                    libraryName = libraryName,
                    editingRootId = editingRootId,
                    reuseSnapshot = lastSuccessfulAbsConnection
                )
            }.onSuccess { outcome ->
                lastSuccessfulAbsConnection = outcome.snapshot
                AbsSettingsLogger.logAddServerSuccess(baseUrl, username, libraryId, outcome.rootId)
                appEventSink.showToast(FeedbackMessages.settingsAbsServerSaved(editing = editingRootId != null))
                _absConnectionState.value = AbsConnectionUiState()
                launchAutoAbsSync(outcome.rootId)
            }.onFailure { error ->
                // Title: Redact ABS Save Server Failure Message (Delegate token masking to FormatSettingsRootUseCase)
                val redactedMessage = formatSettingsRootUseCase.redactAbsError(
                    error.message ?: app.getString(R.string.feedback_settings_abs_server_save_failed_fallback)
                )
                AbsSettingsLogger.logAddServerFailure(
                    baseUrl = baseUrl,
                    username = username,
                    libraryId = libraryId,
                    errorClass = error::class.java.simpleName,
                    message = redactedMessage
                )
                appEventSink.showToast(FeedbackMessages.settingsAbsServerSaveFailed(redactedMessage))
            }
        }
    }

    // Title: Test ABS Connection (Preflight verify ABS endpoint and fetch available library options)
    fun testAbsConnection(baseUrl: String, username: String, password: String, editingRootId: String? = null) {
        scope.launch {
            val start = AbsSettingsLogger.mark()
            AbsSettingsLogger.logTestConnectionStart(baseUrl, username)
            _absConnectionState.value = AbsConnectionUiState(
                isTesting = true,
                baseUrl = baseUrl,
                username = username
            )
            runCatching {
                absSettingsConnectionUseCase.testConnection(
                    baseUrl = baseUrl,
                    username = username,
                    password = password,
                    editingRootId = editingRootId
                )
            }.onSuccess { result ->
                lastSuccessfulAbsConnection = result.snapshot
                _absConnectionState.value = AbsConnectionUiState(
                    isTesting = false,
                    baseUrl = baseUrl,
                    username = username,
                    serverVersion = result.result.serverVersion,
                    loginSucceeded = true,
                    libraries = result.result.bookLibraries.map { library ->
                        AbsLibraryOptionState(
                            id = library.id.orEmpty(),
                            name = library.name.orEmpty()
                        )
                    }
                )
                AbsSettingsLogger.logTestConnectionSuccess(
                    baseUrl = baseUrl,
                    username = username,
                    costMs = AbsSettingsLogger.elapsedMs(start),
                    libraryCount = result.result.bookLibraries.size,
                    serverVersion = result.result.serverVersion
                )
                appEventSink.showToast(FeedbackMessages.settingsAbsConnectionSucceeded(result.result.bookLibraries.size))
            }.onFailure { error ->
                lastSuccessfulAbsConnection = null
                // Title: Format ABS Test Connection Failure (Combine formatting and redacting via FormatSettingsRootUseCase)
                val friendlyMessage = formatSettingsRootUseCase.redactAbsError(
                    formatSettingsRootUseCase.resolveConnectionFailureMessage(error)
                )
                _absConnectionState.value = AbsConnectionUiState(
                    isTesting = false,
                    baseUrl = baseUrl,
                    username = username,
                    loginSucceeded = false,
                    lastError = friendlyMessage
                )
                AbsSettingsLogger.logTestConnectionFailure(
                    baseUrl = baseUrl,
                    username = username,
                    costMs = AbsSettingsLogger.elapsedMs(start),
                    errorClass = error::class.java.simpleName,
                    message = friendlyMessage
                )
                appEventSink.showToast(FeedbackMessages.settingsAbsConnectionFailed(friendlyMessage))
            }
        }
    }

    // Title: Reset ABS Connection State (Restores default verification state for ABS connection forms)
    fun resetAbsConnectionState() {
        _absConnectionState.value = AbsConnectionUiState()
    }

    // Title: Sync ABS Root (Inspect root sync preflights and schedule background worker synchronization)
    fun syncAbsRoot(rootId: String) {
        scope.launch {
            when (val inspection = settingsRootCommands.inspectManualAbsSync(rootId)) {
                SettingsAbsSyncInspection.MissingRoot -> {
                    appEventSink.showToast(FeedbackMessages.absBackgroundSyncRootMissing())
                }
                is SettingsAbsSyncInspection.Blocked -> {
                    appEventSink.showToast(FeedbackMessages.settingsRootUnavailableSyncBlocked(inspection.message))
                }
                is SettingsAbsSyncInspection.Ready -> {
                    val start = AbsSettingsLogger.mark()
                    AbsSettingsLogger.logManualSyncStart(rootId = inspection.rootId, displayName = inspection.displayName)
                    if (inspection.requiresConfirmation) {
                        AbsSettingsLogger.logManualSyncRequiresConfirmation(rootId = inspection.rootId, totalItems = inspection.totalItems)
                        _absSyncConfirmationState.value = AbsSyncConfirmationState(rootId = inspection.rootId, totalItems = inspection.totalItems)
                        return@launch
                    }
                    val scheduled = settingsRootCommands.startManualAbsSync(inspection.rootId)
                    if (scheduled) {
                        AbsSettingsLogger.logManualSyncFinished(rootId = inspection.rootId, costMs = AbsSettingsLogger.elapsedMs(start))
                        appEventSink.showToast(FeedbackMessages.settingsAbsSyncStarted())
                    } else {
                        appEventSink.showToast(FeedbackMessages.settingsAbsSyncAlreadyRunning())
                    }
                }
            }
        }
    }

    // Title: Trigger Rescan (Requests a full library Root rescan process from scheduling manager)
    fun triggerRescan() {
        settingsRootCommands.scheduleUserSync()
    }

    // Title: Launch Auto ABS Sync (Helper to launch synchronization directly after connection setup completes)
    private fun launchAutoAbsSync(rootId: String) {
        val scheduled = settingsRootCommands.startAutoAbsSync(rootId)
        if (!scheduled) {
            appEventSink.showToast(FeedbackMessages.settingsAbsSyncAlreadyRunning())
        }
    }
}
