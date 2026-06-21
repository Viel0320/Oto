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
import com.viel.aplayer.event.AppEventSink
import com.viel.aplayer.event.feedback.LibraryAccessFeedbackFacts
import com.viel.aplayer.i18n.AppLocaleController
import com.viel.aplayer.logger.AbsSettingsLogger
import com.viel.aplayer.shared.settings.AppLanguage
import com.viel.aplayer.shared.settings.GlassEffectMode
import com.viel.aplayer.shared.settings.SeekStepSeconds
import com.viel.aplayer.shared.settings.SleepMode
import com.viel.aplayer.shared.settings.ThemeMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Manages local configuration modification tasks.
 * Coordinates properties mutations through AppSettingsRepository inside a dedicated view coroutine scope.
 */
class SettingsPreferencesHandler(
    private val settingsCommands: AppSettingsCommands,
    private val scope: CoroutineScope,
    private val app: Application
) {
    fun toggleChapterProgressMode(enabled: Boolean) {
        scope.launch {
            settingsCommands.updateChapterProgressMode(enabled)
        }
    }

    fun toggleAllowInsecureTls(enabled: Boolean) {
        scope.launch {
            settingsCommands.updateAllowInsecureTls(enabled)
        }
    }

    fun toggleCleartextTrafficAllowed(enabled: Boolean) {
        scope.launch {
            settingsCommands.updateCleartextTrafficAllowed(enabled)
        }
    }

    fun toggleSkipSilenceEnabled(enabled: Boolean) {
        scope.launch {
            settingsCommands.updateSkipSilenceEnabled(enabled)
        }
    }

    fun toggleSleepFadeOutEnabled(enabled: Boolean) {
        scope.launch {
            settingsCommands.updateSleepFadeOutEnabled(enabled)
        }
    }

    fun toggleShakeToResetEnabled(enabled: Boolean) {
        scope.launch {
            settingsCommands.updateShakeToResetEnabled(enabled)
        }
    }

    fun updateSleepMode(mode: SleepMode) {
        scope.launch {
            settingsCommands.updateSleepMode(mode)
        }
    }

    fun updateGlassEffectMode(mode: GlassEffectMode) {
        scope.launch {
            settingsCommands.updateGlassEffectMode(mode)
        }
    }

    fun updateThemeMode(mode: ThemeMode) {
        scope.launch {
            settingsCommands.updateThemeMode(mode)
        }
    }

    fun updateAppLanguage(language: AppLanguage) {
        scope.launch {
            settingsCommands.updateAppLanguage(language)
            AppLocaleController.applyPlatformLocale(app, language)
        }
    }

    fun updateAutoRewindSeconds(seconds: Int) {
        scope.launch {
            settingsCommands.updateAutoRewindSeconds(seconds)
        }
    }

    fun updateSeekBackwardSeconds(step: SeekStepSeconds) {
        scope.launch {
            settingsCommands.updateSeekBackwardSeconds(step)
        }
    }

    fun updateSeekForwardSeconds(step: SeekStepSeconds) {
        scope.launch {
            settingsCommands.updateSeekForwardSeconds(step)
        }
    }

    fun toggleNotificationAvoidanceEnabled(enabled: Boolean) {
        scope.launch {
            settingsCommands.updateNotificationAvoidanceEnabled(enabled)
        }
    }

    fun toggleDynamicColorEnabled(enabled: Boolean) {
        scope.launch {
            settingsCommands.updateDynamicColorEnabled(enabled)
        }
    }

    fun toggleAmoledEnabled(enabled: Boolean) {
        scope.launch {
            settingsCommands.updateAmoledEnabled(enabled)
        }
    }

    fun updatePlaybackBufferMaxBytes(bytes: Long) {
        scope.launch {
            settingsCommands.updatePlaybackBufferMaxBytes(bytes)
        }
    }

    fun toggleDownloadWifiOnly(enabled: Boolean) {
        scope.launch {
            settingsCommands.updateDownloadWifiOnly(enabled)
        }
    }
}

/**
 * Encapsulates remote ABS and WebDAV connection verification tasks.
 * Maintains UI state flows for verification loading and dispatches command calls safely.
 */
class SettingsConnectionHandler(
    private val absSettingsConnectionUseCase: AbsSettingsConnectionUseCase,
    private val testWebDavConnectionUseCase: TestWebDavConnectionUseCase,
    private val settingsQueryUseCase: SettingsQueryUseCase,
    private val settingsRootCommands: SettingsRootCommands,
    private val formatSettingsRootUseCase: com.viel.aplayer.application.usecase.FormatSettingsRootUseCase,
    private val appEventSink: AppEventSink,
    private val scope: CoroutineScope,
    private val app: Application
) {
    private val _absConnectionState = MutableStateFlow(AbsConnectionUiState())
    val absConnectionState: StateFlow<AbsConnectionUiState> = _absConnectionState.asStateFlow()

    private val _webDavConnectionState = MutableStateFlow(WebDavConnectionUiState())
    val webDavConnectionState: StateFlow<WebDavConnectionUiState> = _webDavConnectionState.asStateFlow()

    private val _absSyncConfirmationState = MutableStateFlow<AbsSyncConfirmationState?>(null)

    private var lastSuccessfulAbsConnection: AbsConnectionReuseSnapshot? = null

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
                appEventSink.emitFeedback(
                    LibraryAccessFeedbackFacts.webDavConnectionSucceeded(draftId = editingRootId ?: NEW_DRAFT_ID)
                )
            }.onFailure { error ->
                val friendlyMessage = formatSettingsRootUseCase.resolveConnectionFailureMessage(error)
                _webDavConnectionState.value = WebDavConnectionUiState(isTesting = false, testSucceeded = false, lastError = friendlyMessage)
                appEventSink.emitFeedback(
                    LibraryAccessFeedbackFacts.webDavConnectionFailed(
                        draftId = editingRootId ?: NEW_DRAFT_ID,
                        friendlyMessage = friendlyMessage
                    )
                )
            }
        }
    }

    fun resetWebDavConnectionState() {
        _webDavConnectionState.value = WebDavConnectionUiState()
    }

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

    fun getWebDavCredentials(credentialId: String?): SettingsCredential? {
        val cred = settingsQueryUseCase.getWebDavCredential(credentialId) ?: return null
        return SettingsCredential(username = cred.username, password = cred.password)
    }

    suspend fun getAbsCredential(credentialId: String?): SettingsCredential? {
        val cred = settingsQueryUseCase.getAbsCredential(credentialId) ?: return null
        return SettingsCredential(username = cred.username ?: "", password = "")
    }

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
                appEventSink.emitFeedback(
                    LibraryAccessFeedbackFacts.absServerSaved(
                        rootId = outcome.rootId,
                        editing = editingRootId != null
                    )
                )
                _absConnectionState.value = AbsConnectionUiState()
                launchAutoAbsSync(outcome.rootId)
            }.onFailure { error ->
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
                appEventSink.emitFeedback(LibraryAccessFeedbackFacts.absServerSaveFailed(redactedMessage))
            }
        }
    }

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
                appEventSink.emitFeedback(
                    LibraryAccessFeedbackFacts.absConnectionSucceeded(
                        draftId = editingRootId ?: NEW_DRAFT_ID,
                        libraryCount = result.result.bookLibraries.size
                    )
                )
            }.onFailure { error ->
                lastSuccessfulAbsConnection = null
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
                appEventSink.emitFeedback(
                    LibraryAccessFeedbackFacts.absConnectionFailed(
                        draftId = editingRootId ?: NEW_DRAFT_ID,
                        friendlyMessage = friendlyMessage
                    )
                )
            }
        }
    }

    fun resetAbsConnectionState() {
        _absConnectionState.value = AbsConnectionUiState()
    }

    fun syncAbsRoot(rootId: String) {
        scope.launch {
            when (val inspection = settingsRootCommands.inspectManualAbsSync(rootId)) {
                SettingsAbsSyncInspection.MissingRoot -> {
                    appEventSink.emitFeedback(LibraryAccessFeedbackFacts.syncRootMissing())
                }
                is SettingsAbsSyncInspection.Blocked -> {
                    appEventSink.emitFeedback(inspection.fact)
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
                        appEventSink.emitFeedback(LibraryAccessFeedbackFacts.syncStarted(inspection.rootId))
                    } else {
                        appEventSink.emitFeedback(LibraryAccessFeedbackFacts.syncAlreadyRunning(inspection.rootId))
                    }
                }
            }
        }
    }

    /**
     * Starts a user-priority rescan for the selected local or WebDAV root.
     *
     * SettingsScreen already separates ABS sync from directory rescans, so this handler receives
     * only roots that should be processed by the directory import pipeline.
     */
    fun triggerRescan(rootId: String) {
        settingsRootCommands.scheduleUserSync(rootId)
    }

    private fun launchAutoAbsSync(rootId: String) {
        val scheduled = settingsRootCommands.startAutoAbsSync(rootId)
        if (!scheduled) {
            appEventSink.emitFeedback(LibraryAccessFeedbackFacts.syncAlreadyRunning(rootId))
        }
    }

    private companion object {
        private const val NEW_DRAFT_ID = "new"
    }
}
