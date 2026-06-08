package com.viel.aplayer.ui.settings

// Theme Mode Selection (Support theme mode preference settings) Added ThemeMode import to access selected theme configurations.
import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.viel.aplayer.APlayerApplication
import com.viel.aplayer.R
import com.viel.aplayer.application.library.settings.SettingsAbsSyncInspection
import com.viel.aplayer.application.usecase.AbsConnectionReuseSnapshot
import com.viel.aplayer.application.usecase.LibraryRootSettingsSnapshot
import com.viel.aplayer.data.db.AudiobookSchema
import com.viel.aplayer.data.store.AppLanguage
import com.viel.aplayer.data.store.AppSettings
import com.viel.aplayer.data.store.GlassEffectMode
import com.viel.aplayer.data.store.SeekStepSeconds
import com.viel.aplayer.data.store.SleepMode
import com.viel.aplayer.data.store.ThemeMode
import com.viel.aplayer.event.feedback.FeedbackMessages
import com.viel.aplayer.i18n.AppLocaleController
import com.viel.aplayer.library.vfs.sourceProvider.webdav.WebDavConnectionTestException
import com.viel.aplayer.library.vfs.sourceProvider.webdav.WebDavConnectionTestFailureReason
import com.viel.aplayer.library.vfs.sourceProvider.webdav.WebDavEndpointValidationException
import com.viel.aplayer.library.vfs.sourceProvider.webdav.WebDavEndpointValidationReason
import com.viel.aplayer.logger.AbsSettingsLogger
import com.viel.aplayer.network.UnsafeNetworkPolicyViolation
import com.viel.aplayer.ui.common.formatDate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Settings view model (Handler for configuration persistence interactions)
 * Manages reactive settings flows and dispatches business operations.
 */
class SettingsViewModel(application: Application) : AndroidViewModel(application) {
    // Settings Screen Dependency View (Resolve only settings-page operations and feedback)
    // SettingsViewModel coordinates several settings workflows but should not see playback runtime or VFS dependencies.
    private val settingsDependencies = APlayerApplication.getSettingsScreenDependencies(application)
    private val settingsRepository = settingsDependencies.settingsRepository
    // Settings Root Scene Interfaces (Route root display and commands through the settings-root module)
    // This keeps root registration, status refresh, and manual scan triggers off the broad library transition entry point.
    private val settingsRootReadModel = settingsDependencies.settingsRootReadModel
    private val settingsRootCommands = settingsDependencies.settingsRootCommands
    // Settings Query Use Case (Supplies settings read models and credential lookups)
    // The ViewModel now maps application snapshots into UI state instead of reading Room DAOs or credential stores directly.
    private val settingsQueryUseCase = settingsDependencies.settingsQueryUseCase
    private val settingsLibraryMaintenanceUseCase = settingsDependencies.settingsLibraryMaintenanceUseCase
    private val absSettingsConnectionUseCase = settingsDependencies.absSettingsConnectionUseCase
    private val webDavConnectionTester = settingsDependencies.webDavConnectionTester
    // Application Event Sink (Centralizes settings feedback with the rest of the app shell)
    // SettingsViewModel now emits user messages through the shared app-level stream instead of a local UI event flow.
    private val appEventSink = settingsDependencies.appEventSink
    // Cache connection snapshot (To speed up registration directly after a successful test)
    // Temporarily retains connection metadata in memory without writing to database or exposing it to UI.
    private var lastSuccessfulAbsConnection: AbsConnectionReuseSnapshot? = null

    /**
     * Cross-domain deletion usecase (To enforce DDD architecture boundaries)
     * Replaces direct facade repository calls with clean domain-level service triggers.
     */
    private val deleteLibraryRootUseCase = settingsDependencies.deleteLibraryRootUseCase

    // Settings Overlay Visibility State (To control settings overlay visibility in single-activity architecture)
    // Manages the show/hide status of the settings overlay inside MainActivity, providing reactive flow signals.
    private val _isVisible = MutableStateFlow(false)
    val isVisible: StateFlow<Boolean> = _isVisible.asStateFlow()

    // Modify settings overlay visibility (To dynamically trigger settings overlay display state changes)
    // Exposes a public modifier function to toggle the settings overlay visibility flag.
    fun setVisible(visible: Boolean) {
        _isVisible.value = visible
    }

    private val _absConnectionState = MutableStateFlow(AbsConnectionUiState())
    val absConnectionState: StateFlow<AbsConnectionUiState> = _absConnectionState.asStateFlow()
    // WebDAV connection state: Expose state flow indicating verification progress to settings dialog.
    private val _webDavConnectionState = MutableStateFlow(WebDavConnectionUiState())
    val webDavConnectionState: StateFlow<WebDavConnectionUiState> = _webDavConnectionState.asStateFlow()
    private val _absSyncConfirmationState = MutableStateFlow<AbsSyncConfirmationState?>(null)
    val absSyncConfirmationState: StateFlow<AbsSyncConfirmationState?> = _absSyncConfirmationState.asStateFlow()

    /** Exposed settings flow (To stream settings modifications to observing UI views) */
    val settingsState: StateFlow<AppSettings> = settingsRepository.settingsFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = AppSettings()
        )

    val libraryRootDisplays: StateFlow<List<SettingsRootItem>> = settingsRootReadModel
        .observeRootSnapshots()
        .map { snapshots ->
            // Settings Root Item Mapping (Converts application snapshots into Room-free scene items)
            // Query ownership stays in the settings-root read model while ViewModel keeps UI-specific title, status, and timestamp formatting without exposing persistence root rows.
            snapshots.map { snapshot ->
                val isAbsRoot = snapshot.sourceType == AudiobookSchema.LibrarySourceType.ABS
                SettingsRootItem(
                    rootId = snapshot.rootId,
                    sourceType = snapshot.sourceType,
                    sourceUri = snapshot.sourceUri,
                    basePath = snapshot.basePath,
                    credentialId = snapshot.credentialId,
                    displayName = snapshot.displayName,
                    title = resolveLibraryRootTitle(snapshot),
                    statusText = resolveLibraryRootStatusText(snapshot, getApplication()),
                    locationText = resolveLibraryRootLocation(snapshot),
                    selectedLibraryText = if (isAbsRoot) snapshot.displayName.ifBlank { snapshot.basePath } else null,
                    lastSyncText = formatLibraryRootSyncTime(
                        timestampMs = if (isAbsRoot) snapshot.absLastFullSyncAt else snapshot.lastScannedAt.takeIf { it > 0L },
                        notSyncedText = getApplication<Application>().getString(R.string.settings_library_not_synced)
                    ),
                    importedBookCount = snapshot.importedBookCount,
                    lastError = snapshot.absLastError?.redactAbsError()
                )
            }
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    init {
        viewModelScope.launch {
            // Optimize Settings Status Refresh (Refresh statuses only when settings overlay becomes visible to prevent heavy checks on cold start)
            // Obtains the visibility flow and updates the registered library directories once overlay shows up.
            isVisible.collect { visible ->
                if (visible) {
                    settingsRootCommands.refreshAllRootStatuses()
                }
            }
        }
    }

    fun onLibraryRootSelected(uri: Uri) {
        // Persist local library directory (To registers new SAF path and schedules import sync)
        // Dispatches the folder registration to the settings-root command surface so UI callers do not select a granular root gateway.
        settingsRootCommands.addLocalRootAndScheduleSync(uri)
    }

    /**
     * Handle SAF root relocation (To update local library path and clear incremental cache)
     * Overwrites SAF root URI, evicts cache index, and forces immediate reachability check to refresh book list.
     */
    fun onSafRootRelocated(id: String, newUri: Uri) {
        viewModelScope.launch {
            runCatching {
                settingsLibraryMaintenanceUseCase.updateSafRootAndScheduleSync(id, newUri)
            }.onSuccess {
                appEventSink.showToast(FeedbackMessages.settingsLocalLibraryRelocated())
            }.onFailure { error ->
                com.viel.aplayer.logger.ScanWorkflowLogger.error("onSafRootRelocated failed", error)
                appEventSink.showToast(FeedbackMessages.settingsLocalLibraryRelocationFailed(error.message))
            }
        }
    }

    fun onWebDavRootSubmitted(
        url: String,
        username: String,
        password: String,
        displayName: String,
        basePath: String
    ) {
        // Register WebDAV endpoint (To initiate a background sync task for remote WebDAV resources)
        // Passes connection credentials and directories to the settings-root command surface so settings UI stays on a scene-specific interface.
        settingsRootCommands.addWebDavRootAndScheduleSync(
            url = url,
            username = username,
            password = password,
            displayName = displayName,
            basePath = basePath
        )
    }

    /**
     * Update WebDAV configuration (To modify connection attributes for remote WebDAV server)
     * Modifies URL endpoints, base sub-paths, and security parameters for selected WebDAV library.
     */
    fun updateWebDavRoot(
        id: String,
        url: String,
        username: String,
        password: String,
        displayName: String,
        basePath: String
    ) {
        viewModelScope.launch {
            runCatching {
                settingsLibraryMaintenanceUseCase.updateWebDavRootAndScheduleSync(
                    id = id,
                    url = url,
                    username = username,
                    password = password,
                    displayName = displayName,
                    basePath = basePath
                )
            }.onSuccess {
                appEventSink.showToast(FeedbackMessages.settingsWebDavUpdated())
            }.onFailure { error ->
                appEventSink.showToast(FeedbackMessages.settingsWebDavUpdateFailed(error.message))
            }
        }
    }

    // WebDAV Connection UI Action (Delegates URL, TLS, and PROPFIND work to WebDavConnectionTester)
    // The ViewModel only flips loading/result state and emits user feedback.
    fun testWebDavConnection(
        url: String,
        username: String,
        password: String,
        basePath: String,
        editingRootId: String? = null
    ) {
        viewModelScope.launch {
            _webDavConnectionState.value = WebDavConnectionUiState(isTesting = true)
            runCatching {
                val resolvedCredentials = settingsQueryUseCase.resolveWebDavCredentials(
                    username = username,
                    password = password,
                    editingRootId = editingRootId
                )
                webDavConnectionTester.testConnection(
                    url = url,
                    username = resolvedCredentials.username,
                    password = resolvedCredentials.password,
                    basePath = basePath
                )
            }.onSuccess {
                _webDavConnectionState.value = WebDavConnectionUiState(isTesting = false, testSucceeded = true)
                appEventSink.showToast(FeedbackMessages.settingsWebDavConnectionSucceeded())
            }.onFailure { error ->
                // User Friendly SSL Error: Handle SSL/TLS trust path verify exceptions specifically and offer hints.
                val friendlyMessage = resolveConnectionFailureMessage(error)
                _webDavConnectionState.value = WebDavConnectionUiState(isTesting = false, testSucceeded = false, lastError = friendlyMessage)
                appEventSink.showToast(FeedbackMessages.settingsWebDavConnectionFailed(friendlyMessage))
            }
        }
    }

    // WebDAV state reset: Restore default verification status and clear snapshot when dialog is closed or saved.
    fun resetWebDavConnectionState() {
        _webDavConnectionState.value = WebDavConnectionUiState()
    }

    /**
     * Retrieve WebDAV credentials (To pre-fill edit form inputs in UI dialogs)
     */
    fun getWebDavCredentials(credentialId: String?): com.viel.aplayer.library.vfs.sourceProvider.webdav.WebDavCredential? {
        return settingsQueryUseCase.getWebDavCredential(credentialId)
    }

    /**
     * Retrieve ABS credentials (To pre-fill edit form inputs in UI dialogs)
     */
    suspend fun getAbsCredential(credentialId: String?): com.viel.aplayer.abs.auth.AbsCredential? {
        return settingsQueryUseCase.getAbsCredential(credentialId)
    }

    fun addAbsServerWithPassword(
        baseUrl: String,
        username: String,
        password: String,
        libraryId: String,
        libraryName: String,
        editingRootId: String? = null
    ) {
        viewModelScope.launch {
            // Log ABS server addition (To track user registration attempt in settings log scope)
            // Segregates user configuration events from network-level authentication logs.
            AbsSettingsLogger.logAddServerStart(baseUrl, username, libraryId, libraryName)
            runCatching {
                // ABS Server Save Delegation (Moves login, token reuse, credential persistence, and root edits into the use case)
                // The ViewModel receives only the saved root plus a refreshed reuse snapshot for UI-adjacent caching.
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
                val redactedMessage = (
                    error.message ?: getApplication<Application>().getString(R.string.feedback_settings_abs_server_save_failed_fallback)
                    ).redactAbsError()
                AbsSettingsLogger.logAddServerFailure(
                    baseUrl = baseUrl,
                    username = username,
                    libraryId = libraryId,
                    errorClass = error::class.java.simpleName,
                    message = redactedMessage
                )
                // Expose authentication failures (To detail precise server constraints on the settings interface)
                // Toast details error payload rather than generic failure messages.
                appEventSink.showToast(FeedbackMessages.settingsAbsServerSaveFailed(redactedMessage))
            }
        }
    }

    fun testAbsConnection(baseUrl: String, username: String, password: String, editingRootId: String? = null) {
        viewModelScope.launch {
            // Log testing action (To record user action endpoint configuration in settings scope)
            // Routes connection check parameters directly to AbsSettingsLogger.
            val start = AbsSettingsLogger.mark()
            AbsSettingsLogger.logTestConnectionStart(baseUrl, username)
            _absConnectionState.value = AbsConnectionUiState(
                isTesting = true,
                baseUrl = baseUrl,
                username = username
            )
            runCatching {
                // ABS Connection Test Delegation (Moves login and edit-mode token lookup behind the settings use case)
                // The ViewModel only stores the returned reuse snapshot and renders the library options.
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
                // Evict cached credentials (To avoid reusing stale or invalid tokens)
                // Discards the snapshot when a subsequent test check fails or returns an error.
                lastSuccessfulAbsConnection = null
                // User Friendly SSL Error: Handle SSL/TLS trust path verify exceptions specifically and offer hints.
                val friendlyMessage = resolveConnectionFailureMessage(error).redactAbsError()
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

    // ABS connection status reset: Expose hook to clear authentication test states upon input changes or window exits.
    fun resetAbsConnectionState() {
        _absConnectionState.value = AbsConnectionUiState()
    }

    fun syncAbsRoot(rootId: String) {
        viewModelScope.launch {
            when (val inspection = settingsRootCommands.inspectManualAbsSync(rootId)) {
                SettingsAbsSyncInspection.MissingRoot -> {
                    appEventSink.showToast(FeedbackMessages.absBackgroundSyncRootMissing())
                }
                is SettingsAbsSyncInspection.Blocked -> {
                    // Manual ABS Sync Block Feedback (Render application-level preflight failure without receiving root entities)
                    // The settings-root module builds the provider-aware detail message, while the ViewModel only chooses the UI feedback channel.
                    appEventSink.showToast(FeedbackMessages.settingsRootUnavailableSyncBlocked(inspection.message))
                }
                is SettingsAbsSyncInspection.Ready -> {
                    // Log manual sync (To trace trigger events initiated from settings panel)
                    // Routes synchronization initialization to settings diagnostic logs using the entity-free inspection projection.
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

    fun triggerRescan() {
        // Trigger manual scan (To check file updates on registered roots)
        // Dispatches scan scheduler request through settings-root commands so the UI layer does not bind to ScanScheduler directly.
        settingsRootCommands.scheduleUserSync()
    }

    fun toggleChapterProgressMode(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.updateChapterProgressMode(enabled)
        }
    }

    // Toggle Insecure TLS: Update the global settings regarding whether self-signed/untrusted SSL certificates are accepted.
    fun toggleAllowInsecureTls(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.updateAllowInsecureTls(enabled)
        }
    }

    // Toggle HTTP traffic config (To adjust cleartext allowance setting parameters)
    fun toggleCleartextTrafficAllowed(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.updateCleartextTrafficAllowed(enabled)
        }
    }

    // Deregister library root (To release SAF grant permissions, stop related playback, and clear DB entries)
    fun deleteLibraryRoot(root: SettingsRootItem) {
        viewModelScope.launch {
            // Log directory deletion (To record high-risk library removal event)
            // Writes deletion request info with ID and type indicators.
            AbsSettingsLogger.logDeleteServerStart(rootId = root.rootId, sourceType = root.sourceType)
            // Stop playback before deletion (To prevent crashes or unexpected playback behaviors from missing tracks)
            // Triggers the rootId deletion path so Settings UI never reconstructs or stores the persistence root row.
            val playbackWasStopped = deleteLibraryRootUseCase.invoke(root.rootId)
            // Log removal result (To track whether the removal caused immediate playback halt)
            // Records outcome stats to log system.
            AbsSettingsLogger.logDeleteServerFinished(rootId = root.rootId, playbackStopped = playbackWasStopped)
            appEventSink.showToast(FeedbackMessages.settingsLibraryRootRemoved(playbackWasStopped))
        }
    }

    /**
     * Toggle the global switch for the Skip Silence feature.
     *
     * Following refactoring, the interactive logic for custom minimum duration and warm notification tips switches has been removed.
     */
    fun toggleSkipSilenceEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.updateSkipSilenceEnabled(enabled)
        }
    }

    // Toggle skip silence (To modify persistent preference for audio skip settings)
    fun toggleSleepFadeOutEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.updateSleepFadeOutEnabled(enabled)
        }
    }

    // Toggle shake reset (To toggle shake timer resetting capability)
    fun toggleShakeToResetEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.updateShakeToResetEnabled(enabled)
        }
    }

    // Update sleep mode (To adjust sleep target mode settings)
    fun updateSleepMode(mode: SleepMode) {
        viewModelScope.launch {
            settingsRepository.updateSleepMode(mode)
        }
    }

    // Update glass effect (To modify visual backdrop parameters for navigation overlays)
    fun updateGlassEffectMode(mode: GlassEffectMode) {
        viewModelScope.launch {
            settingsRepository.updateGlassEffectMode(mode)
        }
    }

    // Update theme mode (To modify the active theme mode preference) Persists user chosen theme mode to local repository.
    fun updateThemeMode(mode: ThemeMode) {
        viewModelScope.launch {
            settingsRepository.updateThemeMode(mode)
        }
    }

    // Update App Language (Persist the selected locale before applying Android per-app language APIs)
    // DataStore writes first so a framework-triggered Activity recreation cannot lose the user's explicit System-default reset.
    fun updateAppLanguage(language: AppLanguage) {
        viewModelScope.launch {
            settingsRepository.updateAppLanguage(language)
            AppLocaleController.applyPlatformLocale(getApplication(), language)
        }
    }

    // Update rewind seconds (To customize position rollback durations)
    fun updateAutoRewindSeconds(seconds: Int) {
        viewModelScope.launch {
            settingsRepository.updateAutoRewindSeconds(seconds)
        }
    }

    // Update Rewind Seek Step (Persist the short backward transport increment)
    // The repository accepts only constrained seek-step values, so Settings UI cannot write unsupported integers.
    fun updateSeekBackwardSeconds(step: SeekStepSeconds) {
        viewModelScope.launch {
            settingsRepository.updateSeekBackwardSeconds(step)
        }
    }

    // Update Forward Seek Step (Persist the short forward transport increment)
    // The repository accepts only constrained seek-step values, keeping player, notification, and widget commands aligned.
    fun updateSeekForwardSeconds(step: SeekStepSeconds) {
        viewModelScope.launch {
            settingsRepository.updateSeekForwardSeconds(step)
        }
    }

    // Toggle notification avoidance (To enable/disable playback avoidance behavior)
    fun toggleNotificationAvoidanceEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.updateNotificationAvoidanceEnabled(enabled)
        }
    }

    // Toggle Dynamic Color (Persist dynamic color option changes to local repository) Updates dynamic color setting configuration.
    fun toggleDynamicColorEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.updateDynamicColorEnabled(enabled)
        }
    }

    /**
     * Start automated catalog sync (To sync items immediately after server registration)
     * Initiates non-blocking synchronization process in background coroutine scope.
     */
    private fun launchAutoAbsSync(rootId: String) {
        // Start application-level task (To prevent task cancellation upon SettingsViewModel destruction)
        // Enqueues synchronization through settings-root commands so ABS task origins stay out of presentation code.
        val scheduled = settingsRootCommands.startAutoAbsSync(rootId)
        if (!scheduled) {
            appEventSink.showToast(FeedbackMessages.settingsAbsSyncAlreadyRunning())
        }
        return
    }

    private fun resolveConnectionFailureMessage(error: Throwable): String {
        val app = getApplication<Application>()
        return when (error) {
            is UnsafeNetworkPolicyViolation ->
                app.getString(R.string.feedback_settings_cleartext_http_blocked)
            is WebDavEndpointValidationException ->
                app.getString(error.reason.webDavEndpointValidationMessageRes())
            is WebDavConnectionTestException ->
                if (error.reason == WebDavConnectionTestFailureReason.HttpStatus) {
                    app.getString(R.string.feedback_settings_webdav_http_status, error.httpCode ?: 0)
                } else {
                    app.getString(error.reason.webDavConnectionTestMessageRes())
                }
            is javax.net.ssl.SSLHandshakeException ->
                app.getString(R.string.feedback_settings_ssl_certificate_untrusted)
            is javax.net.ssl.SSLPeerUnverifiedException ->
                app.getString(R.string.feedback_settings_ssl_hostname_mismatch)
            else -> error.message ?: app.getString(R.string.feedback_settings_connection_failed_fallback)
        }
    }
}

/**
 * WebDAV Endpoint Error Mapping (Maps endpoint validation reason codes to localized resources)
 * The network and root-store layers throw stable codes, while SettingsViewModel owns the user-facing text selection.
 */
private fun WebDavEndpointValidationReason.webDavEndpointValidationMessageRes(): Int =
    when (this) {
        WebDavEndpointValidationReason.MissingScheme -> R.string.feedback_settings_webdav_url_missing_scheme
        WebDavEndpointValidationReason.MissingHost -> R.string.feedback_settings_webdav_url_missing_host
        WebDavEndpointValidationReason.UserInfoNotAllowed -> R.string.feedback_settings_webdav_url_userinfo_not_allowed
        WebDavEndpointValidationReason.UnsupportedScheme -> R.string.feedback_settings_webdav_url_unsupported_scheme
    }

/**
 * WebDAV Connection Error Mapping (Maps PROPFIND failure reason codes to localized resources)
 * HTTP status values are kept as data so only the template, not the code, is translated.
 */
private fun WebDavConnectionTestFailureReason.webDavConnectionTestMessageRes(): Int =
    when (this) {
        WebDavConnectionTestFailureReason.Unauthorized -> R.string.feedback_settings_webdav_auth_failed
        WebDavConnectionTestFailureReason.Forbidden -> R.string.feedback_settings_webdav_forbidden
        WebDavConnectionTestFailureReason.NotFound -> R.string.feedback_settings_webdav_not_found
        WebDavConnectionTestFailureReason.HttpStatus -> R.string.feedback_settings_webdav_http_status
    }

private fun String.redactAbsError(): String =
    replace(Regex("Bearer\\s+\\S+", RegexOption.IGNORE_CASE), "Bearer <redacted>")

/**
 * Library Root Title Formatter (Builds the first-line label for each registered library source)
 * Uses provider-specific fallback names so local, WebDAV, and ABS rows remain readable even when the custom display name is empty.
 */
private fun resolveLibraryRootTitle(root: LibraryRootSettingsSnapshot): String =
    when (root.sourceType) {
        AudiobookSchema.LibrarySourceType.WEBDAV ->
            root.displayName.ifBlank { "${root.sourceUri}${root.basePath}" }
        AudiobookSchema.LibrarySourceType.ABS ->
            root.displayName.ifBlank { "ABS ${root.basePath}" }
        else -> root.displayName.ifBlank {
            runCatching { Uri.decode(root.sourceUri).substringAfterLast(":") }
                .getOrDefault(root.sourceUri)
        }
    }

/**
 * Library Root Status Formatter (Normalizes storage availability and ABS sync error state)
 * Prioritizes unavailable root reachability over stale ABS sync timestamps so previously synced servers cannot appear healthy after a failed preflight.
 */
private fun resolveLibraryRootStatusText(root: LibraryRootSettingsSnapshot, app: Application): String {
    val resId = when {
        root.status != AudiobookSchema.LibraryRootStatus.ACTIVE ->
            root.availabilityStatus
                .takeIf { status -> status != AudiobookSchema.AvailabilityStatus.UNKNOWN }
                ?.availabilityStatusMessageRes()
                ?: root.status.libraryRootStatusMessageRes()
        root.availabilityStatus != AudiobookSchema.AvailabilityStatus.UNKNOWN &&
            root.availabilityStatus != AudiobookSchema.AvailabilityStatus.AVAILABLE ->
            root.availabilityStatus.availabilityStatusMessageRes()
        root.sourceType == AudiobookSchema.LibrarySourceType.ABS && root.absLastError?.isNotBlank() == true ->
            R.string.settings_library_status_error
        root.sourceType == AudiobookSchema.LibrarySourceType.ABS && root.absLastFullSyncAt != null ->
            R.string.settings_library_status_synced
        root.sourceType == AudiobookSchema.LibrarySourceType.ABS ->
            R.string.settings_library_status_idle
        root.availabilityStatus != AudiobookSchema.AvailabilityStatus.UNKNOWN ->
            root.availabilityStatus.availabilityStatusMessageRes()
        else -> root.status.libraryRootStatusMessageRes()
    }
    return app.getString(resId)
}

/**
 * Library Root Status Resource Mapping (Converts persisted root status codes into display resources)
 * Settings rows should never expose database codes such as ACTIVE, ERROR, or REVOKED directly to users.
 */
private fun String.libraryRootStatusMessageRes(): Int =
    when (this) {
        AudiobookSchema.LibraryRootStatus.ACTIVE -> R.string.settings_library_status_active
        AudiobookSchema.LibraryRootStatus.REVOKED -> R.string.settings_library_status_revoked
        AudiobookSchema.LibraryRootStatus.ERROR -> R.string.settings_library_status_error
        else -> R.string.settings_library_status_unknown
    }

/**
 * Availability Status Resource Mapping (Converts reachability status codes into display resources)
 * Availability probes remain storage/network facts, while this presentation mapping owns localized labels.
 */
private fun String.availabilityStatusMessageRes(): Int =
    when (this) {
        AudiobookSchema.AvailabilityStatus.AVAILABLE -> R.string.settings_library_status_available
        AudiobookSchema.AvailabilityStatus.REVOKED -> R.string.settings_library_status_revoked
        AudiobookSchema.AvailabilityStatus.AUTH_FAILED -> R.string.settings_library_status_auth_failed
        AudiobookSchema.AvailabilityStatus.NETWORK_UNAVAILABLE -> R.string.settings_library_status_network_unavailable
        AudiobookSchema.AvailabilityStatus.NOT_FOUND -> R.string.settings_library_status_not_found
        AudiobookSchema.AvailabilityStatus.PERMISSION_DENIED -> R.string.settings_library_status_permission_denied
        AudiobookSchema.AvailabilityStatus.SERVER_ERROR -> R.string.settings_library_status_server_error
        AudiobookSchema.AvailabilityStatus.TIMEOUT -> R.string.settings_library_status_timeout
        AudiobookSchema.AvailabilityStatus.UNSUPPORTED -> R.string.settings_library_status_unsupported
        else -> R.string.settings_library_status_unknown
    }

/**
 * Library Root Location Formatter (Builds the second-line physical or remote address)
 * Separates the provider location from display names so ABS library selection can be shown as a distinct field.
 */
private fun resolveLibraryRootLocation(root: LibraryRootSettingsSnapshot): String =
    when (root.sourceType) {
        AudiobookSchema.LibrarySourceType.WEBDAV -> "${root.sourceUri}${root.basePath}"
        AudiobookSchema.LibrarySourceType.ABS -> root.sourceUri
        else -> formatSafDisplayPath(root.sourceUri)
    }

/**
 * SAF Display Path Formatter (Shows the human-readable storage path segment)
 * Decodes Android tree URIs and trims everything before `primary:` so settings rows show paths such as `primary:Audiobooks` instead of the full document-provider URI.
 */
private fun formatSafDisplayPath(sourceUri: String): String {
    val decoded = runCatching { Uri.decode(sourceUri) }.getOrDefault(sourceUri)
    val primaryIndex = decoded.indexOf("primary:")
    return if (primaryIndex >= 0) decoded.substring(primaryIndex) else decoded
}

/**
 * Library Root Sync Time Formatter (Converts persisted millisecond timestamps into standard local time text)
 * Returns a stable placeholder when a source has not completed a scan or ABS full sync yet.
 */
private fun formatLibraryRootSyncTime(timestampMs: Long?, notSyncedText: String): String =
    timestampMs?.takeIf { it > 0L }?.let(::formatDate) ?: notSyncedText
