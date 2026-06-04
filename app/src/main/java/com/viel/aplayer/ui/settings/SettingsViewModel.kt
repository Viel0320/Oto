package com.viel.aplayer.ui.settings

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.viel.aplayer.APlayerApplication
import com.viel.aplayer.abs.auth.AbsCredentialStore
import com.viel.aplayer.data.entity.LibraryRootEntity
import com.viel.aplayer.data.store.AppSettings
import com.viel.aplayer.data.store.GlassEffectMode
import com.viel.aplayer.data.store.SleepMode
import com.viel.aplayer.logger.AbsSettingsLogger
import com.viel.aplayer.ui.common.UiEvent
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Settings view model (Handler for configuration persistence interactions)
 * Manages reactive settings flows and dispatches business operations.
 */
class SettingsViewModel(application: Application) : AndroidViewModel(application) {
    private val container = (application as APlayerApplication).container
    private val settingsRepository = container.settingsRepository
    private val absCatalogSynchronizer = container.absCatalogSynchronizer
    private val absPlaybackSessionSyncer = container.absPlaybackSessionSyncer
    private val absSyncTaskCoordinator = container.absSyncTaskCoordinator
    private val absCredentialStore = AbsCredentialStore.getInstance(application.applicationContext)
    // Shared client instance (To avoid redundant authentication requests)
    // Reuses a single API client instance across connection tests and registration flows.
    private val absApiClient = com.viel.aplayer.abs.net.RealAbsApiClient()
    private val absConnectionTester = com.viel.aplayer.abs.sync.AbsConnectionTester(absApiClient)
    private val absSyncWorkScheduler = com.viel.aplayer.abs.sync.AbsSyncWorkScheduler(application.applicationContext)
    private val database = com.viel.aplayer.data.db.AppDatabase.getInstance(application.applicationContext)
    // Cache connection snapshot (To speed up registration directly after a successful test)
    // Temporarily retains connection metadata in memory without writing to database or exposing it to UI.
    private var lastSuccessfulAbsConnection: AbsConnectionReuseSnapshot? = null
    
    // M5b.1 Decouple repositories (To remove deprecated libraryRepository dependency)
    // Downgrades direct repository operations to libraryRootGateway and scanScheduler.
    private val libraryRootGateway = container.libraryRootGateway
    private val scanScheduler = container.scanScheduler
    
    /**
     * Cross-domain deletion usecase (To enforce DDD architecture boundaries)
     * Replaces direct facade repository calls with clean domain-level service triggers.
     */
    private val deleteLibraryRootUseCase = container.deleteLibraryRootUseCase
    private val _uiEvents = MutableSharedFlow<UiEvent>(extraBufferCapacity = 1)
    val uiEvents: SharedFlow<UiEvent> = _uiEvents.asSharedFlow()
    private val _absConnectionState = MutableStateFlow(AbsConnectionUiState())
    val absConnectionState: StateFlow<AbsConnectionUiState> = _absConnectionState.asStateFlow()
    private val _absSyncConfirmationState = MutableStateFlow<AbsSyncConfirmationState?>(null)
    val absSyncConfirmationState: StateFlow<AbsSyncConfirmationState?> = _absSyncConfirmationState.asStateFlow()

    /** Exposed settings flow (To stream settings modifications to observing UI views) */
    val settingsState: StateFlow<AppSettings> = settingsRepository.settingsFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = AppSettings()
        )

    /** Exposed library roots (To display registered directories) */
    // Subscribes to library root state flow and initializes with cached values to mitigate frame flickers.
    val libraryRoots: StateFlow<List<LibraryRootEntity>> = libraryRootGateway.observeLibraryRoots()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = libraryRootGateway.getCachedLibraryRoots()
        )

    val absServers: StateFlow<List<AbsServerSettingsState>> = combine(
        libraryRootGateway.observeLibraryRoots(),
        database.absSyncStateDao().observeAll()
    ) { roots, syncStates ->
        val syncByRootId = syncStates.associateBy { state -> state.rootId }
        roots.filter { root -> root.sourceType == com.viel.aplayer.data.db.AudiobookSchema.LibrarySourceType.ABS }
            .map { root ->
                val sync = syncByRootId[root.id]
                AbsServerSettingsState(
                    rootId = root.id,
                    displayName = root.displayName,
                    baseUrl = root.sourceUri,
                    libraryId = root.basePath,
                    syncStatus = when {
                        sync?.lastError?.isNotBlank() == true -> "ERROR"
                        sync?.lastFullSyncAt != null -> "SYNCED"
                        else -> "IDLE"
                    },
                    lastFullSyncAt = sync?.lastFullSyncAt,
                    serverVersion = sync?.serverVersion,
                    lastError = sync?.lastError?.redactAbsError()
                )
            }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    init {
        viewModelScope.launch {
            // Postpone SAF status refresh (To avoid interfering with Activity transitions and initial frame draws)
            // Delays for 500ms before validating SAF storage permissions to ensure smooth UI animation rendering.
            kotlinx.coroutines.delay(500)
            // Verify folder accessibility (To update status indicators for library root cards)
            // Triggers directory verification flow through libraryRootGateway.
            libraryRootGateway.refreshLibraryRootStatuses()
        }
    }

    init {
        viewModelScope.launch {
            // Postpone SAF status refresh (To avoid interfering with Activity transitions and initial frame draws)
            // Delays for 500ms before validating SAF storage permissions to ensure smooth UI animation rendering.
            kotlinx.coroutines.delay(500)
            // Verify folder accessibility (To update status indicators for library root cards)
            // Triggers directory verification flow through libraryRootGateway.
            libraryRootGateway.refreshLibraryRootStatuses()
        }
    }

    fun refreshLibraryRootStatuses() {
        viewModelScope.launch {
            // Manually refresh root status (To update folder accessibility statuses via async validation)
            // Dispatches status verification routine in a coroutine block using libraryRootGateway.
            libraryRootGateway.refreshLibraryRootStatuses()
        }
    }

    // Handle SAF registry callback (To register new storage authority and trigger background scan)
    // Separates settings-scoped SAF requests to isolate navigation lifecycle and decouple LibraryViewModel.
    fun onLibraryRootSelected(uri: Uri) {
        // Persist local library directory (To registers new SAF path and schedules import sync)
        // Dispatches the folder registration to libraryRootGateway.
        libraryRootGateway.addLibraryRootAndScheduleSync(uri)
    }

    fun onWebDavRootSubmitted(
        url: String,
        username: String,
        password: String,
        displayName: String,
        basePath: String
    ) {
        // Register WebDAV endpoint (To initiate a background sync task for remote WebDAV resources)
        // Passes connection credentials and directories to libraryRootGateway.
        libraryRootGateway.addWebDavLibraryRootAndScheduleSync(
            url = url,
            username = username,
            password = password,
            displayName = displayName,
            basePath = basePath
        )
    }

    fun addAbsServerWithPassword(
        baseUrl: String,
        username: String,
        password: String,
        libraryId: String,
        libraryName: String
    ) {
        viewModelScope.launch {
            // Log ABS server addition (To track user registration attempt in settings log scope)
            // Segregates user configuration events from network-level authentication logs.
            AbsSettingsLogger.logAddServerStart(baseUrl, username, libraryId, libraryName)
            runCatching {
                val reuseSnapshot = lastSuccessfulAbsConnection?.takeIf { snapshot ->
                    shouldReuseAbsConnectionSnapshot(
                        snapshot = snapshot,
                        baseUrl = baseUrl,
                        username = username,
                        libraryId = libraryId
                    )
                }
                // Skip authentication request (To skip login network requests when parameters align with the tested endpoint)
                // Verifies if input arguments match the cached snapshot metadata.
                val token = reuseSnapshot?.token ?: requireNotNull(absApiClient.login(baseUrl, username, password).user?.token)
                val connection = reuseSnapshot?.connection ?: absConnectionTester.testConnection(baseUrl, token)
                val credential = absCredentialStore.save(
                    baseUrl = baseUrl,
                    token = token,
                    userId = connection.userId,
                    username = connection.username
                )
                val root = libraryRootGateway.addAbsLibraryRoot(
                    credentialId = credential.id,
                    libraryId = libraryId,
                    displayName = libraryName
                )
                // Retain connection snapshot (To minimize waiting times when adding multiple libraries from the same host)
                // Saves the validated connection payload in memory.
                val normalizedSnapshot = AbsConnectionReuseSnapshot(
                    baseUrl = normalizeAbsBaseUrlForReuse(baseUrl),
                    username = username.trim(),
                    token = token,
                    connection = connection
                )
                root to normalizedSnapshot
            }.onSuccess { (root, snapshot) ->
                lastSuccessfulAbsConnection = snapshot
                AbsSettingsLogger.logAddServerSuccess(baseUrl, username, libraryId, root.id)
                _uiEvents.tryEmit(UiEvent.ShowToast("ABS server 已添加，开始后台同步"))
                _absConnectionState.value = AbsConnectionUiState()
                launchAutoAbsSync(root)
            }.onFailure { error ->
                val redactedMessage = (error.message ?: "ABS server 添加失败").redactAbsError()
                AbsSettingsLogger.logAddServerFailure(
                    baseUrl = baseUrl,
                    username = username,
                    libraryId = libraryId,
                    errorClass = error::class.java.simpleName,
                    message = redactedMessage
                )
                // Expose authentication failures (To detail precise server constraints on the settings interface)
                // Toast details error payload rather than generic failure messages.
                _uiEvents.tryEmit(UiEvent.ShowToast("ABS server 添加失败：$redactedMessage"))
            }
        }
    }

    fun testAbsConnection(baseUrl: String, username: String, password: String) {
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
                val login = absApiClient.login(baseUrl, username, password)
                val token = requireNotNull(login.user?.token)
                val result = absConnectionTester.testConnection(baseUrl, token)
                // Cache authorization parameters (To avoid login loops when registration is performed right after check)
                // Holds token and library list in memory.
                lastSuccessfulAbsConnection = AbsConnectionReuseSnapshot(
                    baseUrl = normalizeAbsBaseUrlForReuse(baseUrl),
                    username = username.trim(),
                    token = token,
                    connection = result
                )
                result
            }.onSuccess { result ->
                _absConnectionState.value = AbsConnectionUiState(
                    isTesting = false,
                    baseUrl = baseUrl,
                    username = username,
                    serverVersion = result.serverVersion,
                    loginSucceeded = true,
                    libraries = result.bookLibraries.map { library ->
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
                    libraryCount = result.bookLibraries.size,
                    serverVersion = result.serverVersion
                )
                _uiEvents.tryEmit(UiEvent.ShowToast("连接成功，发现 ${result.bookLibraries.size} 个可用书库"))
            }.onFailure { error ->
                // Evict cached credentials (To avoid reusing stale or invalid tokens)
                // Discards the snapshot when a subsequent test check fails or returns an error.
                lastSuccessfulAbsConnection = null
                _absConnectionState.value = AbsConnectionUiState(
                    isTesting = false,
                    baseUrl = baseUrl,
                    username = username,
                    loginSucceeded = false,
                    lastError = (error.message ?: "连接失败").redactAbsError()
                )
                AbsSettingsLogger.logTestConnectionFailure(
                    baseUrl = baseUrl,
                    username = username,
                    costMs = AbsSettingsLogger.elapsedMs(start),
                    errorClass = error::class.java.simpleName,
                    message = _absConnectionState.value.lastError
                )
                _uiEvents.tryEmit(UiEvent.ShowToast("连接失败：${_absConnectionState.value.lastError}"))
            }
        }
    }

    fun syncAbsRoot(rootId: String) {
        viewModelScope.launch {
            val root = libraryRootGateway.getCachedLibraryRoots().firstOrNull { it.id == rootId } ?: return@launch
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
                _uiEvents.tryEmit(UiEvent.ShowToast("ABS 同步已开始"))
            } else {
                _uiEvents.tryEmit(UiEvent.ShowToast("ABS 同步已在进行中"))
            }
        }
    }

    fun dismissLargeAbsSyncConfirmation() {
        _absSyncConfirmationState.value = null
    }

    fun scheduleAbsRootSync(rootId: String) {
        // Log background sync queue (To track work enqueue actions before execution)
        // Writes task scheduling information to settings logger.
        AbsSettingsLogger.logScheduleBackgroundSync(rootId)
        absSyncWorkScheduler.enqueue(rootId)
    }

    fun triggerRescan() {
        // Trigger manual scan (To check file updates on registered roots)
        // Dispatches scan scheduler request in asynchronous scope.
        scanScheduler.scheduleLibrarySync("USER")
    }

    fun toggleChapterProgressMode(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.updateChapterProgressMode(enabled)
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
            val message = if (playbackWasStopped) {
                "媒体库已移除，当前播放已停止"
            } else {
                "媒体库已移除"
            }
            // Log removal result (To track whether the removal caused immediate playback halt)
            // Records outcome stats to log system.
            AbsSettingsLogger.logDeleteServerFinished(rootId = root.id, playbackStopped = playbackWasStopped)
            _uiEvents.tryEmit(UiEvent.ShowToast(message))
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

    /**
     * Start automated catalog sync (To sync items immediately after server registration)
     * Initiates non-blocking synchronization process in background coroutine scope.
     */
    private fun launchAutoAbsSync(root: LibraryRootEntity) {
        // Start application-level task (To prevent task cancellation upon SettingsViewModel destruction)
        // Enqueues synchronization to absSyncTaskCoordinator.
        val scheduled = absSyncTaskCoordinator.start(root.id, com.viel.aplayer.abs.sync.AbsSyncTaskOrigin.AUTO_ADD)
        if (!scheduled) {
            _uiEvents.tryEmit(UiEvent.ShowToast("ABS 同步已在进行中"))
        }
        return
    }
}

private fun String.redactAbsError(): String =
    replace(Regex("Bearer\\s+\\S+", RegexOption.IGNORE_CASE), "Bearer <redacted>")

/**
 * Connection reuse data snapshot (To cache successful testing credentials)
 * Retains validation details in memory to facilitate smooth registration.
 */
internal data class AbsConnectionReuseSnapshot(
    val baseUrl: String,
    val username: String,
    val token: String,
    val connection: com.viel.aplayer.abs.sync.AbsConnectionTestResult
)

/**
 * Normalize baseUrl value (To align user input URLs during reuse validation check)
 * Removes trailing slash and trims whitespace character margins.
 */
internal fun normalizeAbsBaseUrlForReuse(baseUrl: String): String =
    baseUrl.trim().trimEnd('/')

/**
 * Validate reuse criteria (To determine if the cached connection snapshot can be safely reused)
 * Checks if URLs, username, and target library IDs are equivalent.
 */
internal fun shouldReuseAbsConnectionSnapshot(
    snapshot: AbsConnectionReuseSnapshot,
    baseUrl: String,
    username: String,
    libraryId: String
): Boolean {
    if (normalizeAbsBaseUrlForReuse(baseUrl) != snapshot.baseUrl) return false
    if (username.trim() != snapshot.username) return false
    return snapshot.connection.bookLibraries.any { library -> library.id == libraryId }
}
