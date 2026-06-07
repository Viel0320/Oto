package com.viel.aplayer.ui.settings

// Theme Mode Selection (Support theme mode preference settings) Added ThemeMode import to access selected theme configurations.
import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.viel.aplayer.APlayerApplication
import com.viel.aplayer.R
import com.viel.aplayer.data.db.AudiobookSchema
import com.viel.aplayer.data.entity.LibraryRootEntity
import com.viel.aplayer.data.store.AppSettings
import com.viel.aplayer.data.store.GlassEffectMode
import com.viel.aplayer.data.store.SleepMode
import com.viel.aplayer.data.store.ThemeMode
import com.viel.aplayer.domain.usecase.AbsConnectionReuseSnapshot
import com.viel.aplayer.event.feedback.FeedbackMessages
import com.viel.aplayer.library.availability.buildRootUnavailableSyncMessage
import com.viel.aplayer.library.availability.isSyncAvailable
import com.viel.aplayer.logger.AbsSettingsLogger
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
    private val absCatalogSynchronizer = settingsDependencies.absCatalogSynchronizer
    private val absSyncTaskCoordinator = settingsDependencies.absSyncTaskCoordinator
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
    
    // UI Facade Root Operations (Routes settings page root and scan commands through the high-level facade)
    // SettingsViewModel remains a UI state coordinator, while LibraryFacade hides the granular root and scan gateways behind one application-facing seam.
    private val libraryFacade = settingsDependencies.libraryFacade
    
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

    val libraryRootDisplays: StateFlow<List<LibraryRootDisplayState>> = settingsQueryUseCase
        .observeLibraryRootSnapshots()
        .map { snapshots ->
            // Settings Display Mapping (Converts application query snapshots into presentation text)
            // Query ownership stays in SettingsQueryUseCase while ViewModel keeps the UI-specific title, status, and timestamp formatting.
            snapshots.map { snapshot ->
                val root = snapshot.root
                val isAbsRoot = root.sourceType == AudiobookSchema.LibrarySourceType.ABS
                LibraryRootDisplayState(
                    root = root,
                    title = resolveLibraryRootTitle(root),
                    statusText = resolveLibraryRootStatusText(root, snapshot.absLastError, snapshot.absLastFullSyncAt),
                    locationText = resolveLibraryRootLocation(root),
                    selectedLibraryText = if (isAbsRoot) root.displayName.ifBlank { root.basePath } else null,
                    lastSyncText = formatLibraryRootSyncTime(
                        timestampMs = if (isAbsRoot) snapshot.absLastFullSyncAt else root.lastScannedAt.takeIf { it > 0L },
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
                    libraryFacade.refreshLibraryRootStatuses()
                }
            }
        }
    }

    fun onLibraryRootSelected(uri: Uri) {
        // Persist local library directory (To registers new SAF path and schedules import sync)
        // Dispatches the folder registration to LibraryFacade so UI callers do not select a granular root gateway.
        libraryFacade.addLibraryRootAndScheduleSync(uri)
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
        // Passes connection credentials and directories to LibraryFacade so settings UI stays on the application-facing seam.
        libraryFacade.addWebDavLibraryRootAndScheduleSync(
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
                AbsSettingsLogger.logAddServerSuccess(baseUrl, username, libraryId, outcome.root.id)
                appEventSink.showToast(FeedbackMessages.settingsAbsServerSaved(editing = editingRootId != null))
                _absConnectionState.value = AbsConnectionUiState()
                launchAutoAbsSync(outcome.root)
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
            val preflight = libraryFacade.refreshLibraryRootStatus(rootId) ?: return@launch
            if (!preflight.isSyncAvailable) {
                // Manual ABS Sync Preflight (Blocks plan inspection when the selected root is unavailable)
                // Plan inspection talks to the remote server, so the root status must be refreshed and validated before any preview request is sent.
                appEventSink.showToast(
                    FeedbackMessages.settingsRootUnavailableSyncBlocked(buildRootUnavailableSyncMessage(preflight))
                )
                return@launch
            }
            val root = preflight.root
            // Log manual sync (To trace trigger events initiated from settings panel)
            // Routes synchronization initialization to settings diagnostic logs.
            val start = AbsSettingsLogger.mark()
            AbsSettingsLogger.logManualSyncStart(rootId = root.id, displayName = root.displayName)
            val plan = absCatalogSynchronizer.inspectRootSyncPlan(root)
            if (plan.requiresConfirmation) {
                AbsSettingsLogger.logManualSyncRequiresConfirmation(rootId = rootId, totalItems = plan.totalItems)
                _absSyncConfirmationState.value = AbsSyncConfirmationState(rootId = rootId, totalItems = plan.totalItems)
                return@launch
            }
            val scheduled = absSyncTaskCoordinator.start(rootId, com.viel.aplayer.abs.sync.AbsSyncTaskOrigin.MANUAL)
            if (scheduled) {
                AbsSettingsLogger.logManualSyncFinished(rootId = rootId, costMs = AbsSettingsLogger.elapsedMs(start))
                appEventSink.showToast(FeedbackMessages.settingsAbsSyncStarted())
            } else {
                appEventSink.showToast(FeedbackMessages.settingsAbsSyncAlreadyRunning())
            }
        }
    }

    fun triggerRescan() {
        // Trigger manual scan (To check file updates on registered roots)
        // Dispatches scan scheduler request through LibraryFacade so the UI layer does not bind to ScanScheduler directly.
        libraryFacade.scheduleLibrarySync("USER")
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
    fun deleteLibraryRoot(root: LibraryRootEntity) {
        viewModelScope.launch {
            // Log directory deletion (To record high-risk library removal event)
            // Writes deletion request info with ID and type indicators.
            AbsSettingsLogger.logDeleteServerStart(rootId = root.id, sourceType = root.sourceType)
            // Stop playback before deletion (To prevent crashes or unexpected playback behaviors from missing tracks)
            // Triggers deleteLibraryRootUseCase which conditionally stops active playback.
            val playbackWasStopped = deleteLibraryRootUseCase.invoke(root)
            // Log removal result (To track whether the removal caused immediate playback halt)
            // Records outcome stats to log system.
            AbsSettingsLogger.logDeleteServerFinished(rootId = root.id, playbackStopped = playbackWasStopped)
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

    // Update rewind seconds (To customize position rollback durations)
    fun updateAutoRewindSeconds(seconds: Int) {
        viewModelScope.launch {
            settingsRepository.updateAutoRewindSeconds(seconds)
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
    private fun launchAutoAbsSync(root: LibraryRootEntity) {
        // Start application-level task (To prevent task cancellation upon SettingsViewModel destruction)
        // Enqueues synchronization to absSyncTaskCoordinator.
        val scheduled = absSyncTaskCoordinator.start(root.id, com.viel.aplayer.abs.sync.AbsSyncTaskOrigin.AUTO_ADD)
        if (!scheduled) {
            appEventSink.showToast(FeedbackMessages.settingsAbsSyncAlreadyRunning())
        }
        return
    }

    private fun resolveConnectionFailureMessage(error: Throwable): String =
        when (error) {
            is javax.net.ssl.SSLHandshakeException ->
                getApplication<Application>().getString(R.string.feedback_settings_ssl_certificate_untrusted)
            is javax.net.ssl.SSLPeerUnverifiedException ->
                getApplication<Application>().getString(R.string.feedback_settings_ssl_hostname_mismatch)
            else -> error.message ?: getApplication<Application>().getString(R.string.feedback_settings_connection_failed_fallback)
        }
}

private fun String.redactAbsError(): String =
    replace(Regex("Bearer\\s+\\S+", RegexOption.IGNORE_CASE), "Bearer <redacted>")

/**
 * Library Root Title Formatter (Builds the first-line label for each registered library source)
 * Uses provider-specific fallback names so local, WebDAV, and ABS rows remain readable even when the custom display name is empty.
 */
private fun resolveLibraryRootTitle(root: LibraryRootEntity): String =
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
private fun resolveLibraryRootStatusText(root: LibraryRootEntity, absLastError: String?, absLastFullSyncAt: Long?): String =
    when {
        root.status != AudiobookSchema.LibraryRootStatus.ACTIVE ->
            root.availabilityStatus.takeIf { status -> status != AudiobookSchema.AvailabilityStatus.UNKNOWN } ?: root.status
        root.availabilityStatus != AudiobookSchema.AvailabilityStatus.UNKNOWN &&
            root.availabilityStatus != AudiobookSchema.AvailabilityStatus.AVAILABLE -> root.availabilityStatus
        root.sourceType == AudiobookSchema.LibrarySourceType.ABS && absLastError?.isNotBlank() == true -> "ERROR"
        root.sourceType == AudiobookSchema.LibrarySourceType.ABS && absLastFullSyncAt != null -> "SYNCED"
        root.sourceType == AudiobookSchema.LibrarySourceType.ABS -> "IDLE"
        root.availabilityStatus != AudiobookSchema.AvailabilityStatus.UNKNOWN -> root.availabilityStatus
        else -> root.status
    }

/**
 * Library Root Location Formatter (Builds the second-line physical or remote address)
 * Separates the provider location from display names so ABS library selection can be shown as a distinct field.
 */
private fun resolveLibraryRootLocation(root: LibraryRootEntity): String =
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
