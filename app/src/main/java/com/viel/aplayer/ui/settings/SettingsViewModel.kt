package com.viel.aplayer.ui.settings

// Import alignment: Add java.util.UUID import for random ID generation during credential fallback
// Import alignment: Add WebDAV client okhttp dependencies for remote host availability check
import android.app.Application
import android.net.Uri
import androidx.core.net.toUri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.viel.aplayer.APlayerApplication
import com.viel.aplayer.abs.auth.AbsCredentialStore
import com.viel.aplayer.data.db.AudiobookSchema
import com.viel.aplayer.data.entity.LibraryRootEntity
import com.viel.aplayer.data.store.AppSettings
import com.viel.aplayer.data.store.GlassEffectMode
import com.viel.aplayer.data.store.SleepMode
import com.viel.aplayer.library.availability.buildRootUnavailableSyncMessage
import com.viel.aplayer.library.availability.isSyncAvailable
import com.viel.aplayer.logger.AbsSettingsLogger
import com.viel.aplayer.ui.common.UiEvent
import com.viel.aplayer.ui.common.formatDate
import kotlinx.coroutines.Dispatchers
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
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.milliseconds

/**
 * Settings view model (Handler for configuration persistence interactions)
 * Manages reactive settings flows and dispatches business operations.
 */
class SettingsViewModel(application: Application) : AndroidViewModel(application) {
    private val container = (application as APlayerApplication).container
    private val settingsRepository = container.settingsRepository
    private val absCatalogSynchronizer = container.absCatalogSynchronizer
    private val absSyncTaskCoordinator = container.absSyncTaskCoordinator
    private val absCredentialStore = AbsCredentialStore.getInstance(application.applicationContext)
    // Shared client instance (To avoid redundant authentication requests)
    // Reuses a single API client instance across connection tests and registration flows.
    private val absApiClient = com.viel.aplayer.abs.net.RealAbsApiClient()
    private val absConnectionTester = com.viel.aplayer.abs.sync.AbsConnectionTester(absApiClient)
    private val database = com.viel.aplayer.data.db.AppDatabase.getInstance(application.applicationContext)
    // Cache connection snapshot (To speed up registration directly after a successful test)
    // Temporarily retains connection metadata in memory without writing to database or exposing it to UI.
    private var lastSuccessfulAbsConnection: AbsConnectionReuseSnapshot? = null
    // Cache WebDAV connection snapshot: Temporarily retains successful verification details in memory to mirror ABS behavior.
    private var lastSuccessfulWebDavConnection: WebDavConnectionReuseSnapshot? = null
    
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

    val libraryRootDisplays: StateFlow<List<LibraryRootDisplayState>> = combine(
        libraryRootGateway.observeLibraryRoots(),
        database.absSyncStateDao().observeAll(),
        database.bookDao().getAllBooks()
    ) { roots, syncStates, books ->
        // Library Root Book Counts (Aggregates imported titles per root without introducing another persistence table)
        // Counts active BookEntity records by rootId so the settings list can display the imported-book total for SAF, WebDAV, and ABS roots uniformly.
        val bookCountsByRootId = books.groupingBy { book -> book.rootId }.eachCount()
        val syncByRootId = syncStates.associateBy { state -> state.rootId }
        roots.map { root ->
            val sync = syncByRootId[root.id]
            val isAbsRoot = root.sourceType == AudiobookSchema.LibrarySourceType.ABS
            LibraryRootDisplayState(
                root = root,
                title = resolveLibraryRootTitle(root),
                statusText = resolveLibraryRootStatusText(root, sync?.lastError, sync?.lastFullSyncAt),
                locationText = resolveLibraryRootLocation(root),
                selectedLibraryText = if (isAbsRoot) root.displayName.ifBlank { root.basePath } else null,
                lastSyncText = formatLibraryRootSyncTime(
                    if (isAbsRoot) sync?.lastFullSyncAt else root.lastScannedAt.takeIf { it > 0L }
                ),
                importedBookCount = bookCountsByRootId[root.id] ?: 0,
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
            kotlinx.coroutines.delay(500.milliseconds)
            // Verify folder accessibility (To update status indicators for library root cards)
            // Triggers directory verification flow through libraryRootGateway.
            libraryRootGateway.refreshLibraryRootStatuses()
        }
    }

    init {
        viewModelScope.launch {
            // Postpone SAF status refresh (To avoid interfering with Activity transitions and initial frame draws)
            // Delays for 500ms before validating SAF storage permissions to ensure smooth UI animation rendering.
            kotlinx.coroutines.delay(500.milliseconds)
            // Verify folder accessibility (To update status indicators for library root cards)
            // Triggers directory verification flow through libraryRootGateway.
            libraryRootGateway.refreshLibraryRootStatuses()
        }
    }

    fun onLibraryRootSelected(uri: Uri) {
        // Persist local library directory (To registers new SAF path and schedules import sync)
        // Dispatches the folder registration to libraryRootGateway.
        libraryRootGateway.addLibraryRootAndScheduleSync(uri)
    }

    /**
     * Handle SAF root relocation (To update local library path and clear incremental cache)
     * Overwrites SAF root URI, evicts cache index, and forces immediate reachability check to refresh book list.
     */
    fun onSafRootRelocated(id: String, newUri: Uri) {
        viewModelScope.launch {
            runCatching {
                libraryRootGateway.updateSafLibraryRoot(id, newUri)
                database.directoryCacheDao().deleteByRootId(id)
                val recoveryChecker = com.viel.aplayer.library.availability.MissingBookFileRecoveryChecker(getApplication())
                recoveryChecker.recoverMissingAudioFiles()
            }.onSuccess {
                scanScheduler.scheduleLibrarySync("USER")
                _uiEvents.tryEmit(UiEvent.ShowToast("本地媒体库位置已更新，开始扫描"))
            }.onFailure { error ->
                com.viel.aplayer.logger.ScanWorkflowLogger.error("onSafRootRelocated failed", error)
                _uiEvents.tryEmit(UiEvent.ShowToast("更新位置失败：${error.message}"))
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
        // Passes connection credentials and directories to libraryRootGateway.
        libraryRootGateway.addWebDavLibraryRootAndScheduleSync(
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
                libraryRootGateway.updateWebDavLibraryRoot(
                    id = id,
                    url = url,
                    username = username,
                    password = password,
                    displayName = displayName,
                    basePath = basePath
                )
                database.directoryCacheDao().deleteByRootId(id)
                val recoveryChecker = com.viel.aplayer.library.availability.MissingBookFileRecoveryChecker(getApplication())
                recoveryChecker.recoverMissingAudioFiles()
            }.onSuccess {
                scanScheduler.scheduleLibrarySync("USER")
                _uiEvents.tryEmit(UiEvent.ShowToast("WebDAV 媒体库已更新"))
            }.onFailure { error ->
                _uiEvents.tryEmit(UiEvent.ShowToast("修改 WebDAV 失败：${error.message}"))
            }
        }
    }

    // WebDAV connection tester: Verify remote credentials by issuing a light propfind call.
    fun testWebDavConnection(
        url: String,
        username: String,
        password: String,
        basePath: String,
        editingRootId: String? = null
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            _webDavConnectionState.value = WebDavConnectionUiState(isTesting = true)
            runCatching {
                val normalizedEndpoint = normalizeWebDavEndpoint(url)
                val normalizedBasePath = normalizeWebDavBasePath(basePath, url)
                val targetUrl = if (normalizedBasePath.isEmpty()) normalizedEndpoint else "$normalizedEndpoint$normalizedBasePath"

                val finalUsername = if (username.isBlank() && editingRootId != null) {
                    val existingRoot = database.libraryRootDao().getRootById(editingRootId)
                    val cred = existingRoot?.credentialId?.let { getWebDavCredentials(it) }
                    cred?.username ?: ""
                } else {
                    username
                }
                
                val finalPassword = if (password.isBlank() && editingRootId != null) {
                    val existingRoot = database.libraryRootDao().getRootById(editingRootId)
                    val cred = existingRoot?.credentialId?.let { getWebDavCredentials(it) }
                    cred?.password ?: ""
                } else {
                    password
                }

                val client = OkHttpClient.Builder()
                    .connectTimeout(10, TimeUnit.SECONDS)
                    .readTimeout(15, TimeUnit.SECONDS)
                    .build()

                val mediaType = "application/xml; charset=utf-8".toMediaType()
                val requestBody = "<?xml version=\"1.0\" encoding=\"utf-8\" ?><D:propfind xmlns:D=\"DAV:\"><D:allprop/></D:propfind>".toRequestBody(mediaType)
                val builder = Request.Builder()
                    .url(targetUrl)
                    .method("PROPFIND", requestBody)
                    .header("Depth", "0")

                if (finalUsername.isNotBlank() || finalPassword.isNotBlank()) {
                    builder.header("Authorization", Credentials.basic(finalUsername, finalPassword, Charsets.UTF_8))
                }

                client.newCall(builder.build()).execute().use { response ->
                    if (response.isSuccessful || response.code == 207) {
                        true
                    } else {
                        val errMsg = when (response.code) {
                            401 -> "认证失败（用户名或密码错误）"
                            403 -> "服务器拒绝访问（403 禁止访问）"
                            404 -> "未找到路径，请检查 URL 和库内路径"
                            else -> "连接失败，HTTP 状态码: ${response.code}"
                        }
                        throw IOException(errMsg)
                    }
                }
            }.onSuccess {
                // WebDAV connection tester: Cache verified settings payload on success and invalidate snapshot upon reset or failure.
                lastSuccessfulWebDavConnection = WebDavConnectionReuseSnapshot(
                    url = url.trim(),
                    username = username.trim(),
                    password = password,
                    basePath = basePath.trim()
                )
                _webDavConnectionState.value = WebDavConnectionUiState(isTesting = false, testSucceeded = true)
                _uiEvents.tryEmit(UiEvent.ShowToast("WebDAV 测试连接成功"))
            }.onFailure { error ->
                lastSuccessfulWebDavConnection = null
                _webDavConnectionState.value = WebDavConnectionUiState(isTesting = false, testSucceeded = false, lastError = error.message ?: "连接失败")
                _uiEvents.tryEmit(UiEvent.ShowToast("WebDAV 测试连接失败: ${error.message}"))
            }
        }
    }

    // WebDAV state reset: Restore default verification status and clear snapshot when dialog is closed or saved.
    fun resetWebDavConnectionState() {
        lastSuccessfulWebDavConnection = null
        _webDavConnectionState.value = WebDavConnectionUiState()
    }

    private fun normalizeWebDavEndpoint(url: String): String {
        val parsed = url.trim().toUri()
        val scheme = parsed.scheme?.lowercase() ?: throw IllegalArgumentException("WebDAV URL 缺少协议")
        val authority = parsed.encodedAuthority ?: throw IllegalArgumentException("WebDAV URL 缺少主机")
        require(scheme == "http" || scheme == "https") { "WebDAV URL 仅支持 http/https" }
        return "$scheme://$authority"
    }

    private fun normalizeWebDavBasePath(basePath: String, url: String): String {
        val parsed = url.trim().toUri()
        val rawPath = basePath.ifBlank { parsed.path.orEmpty() }
        return Uri.decode(rawPath)
            .replace('\\', '/')
            .trim()
            .trim('/')
            .takeIf { it.isNotBlank() }
            ?.let { "/$it" }
            .orEmpty()
    }

    /**
     * Retrieve WebDAV credentials (To pre-fill edit form inputs in UI dialogs)
     */
    fun getWebDavCredentials(credentialId: String?): com.viel.aplayer.library.vfs.sourceProvider.webdav.WebDavCredential? {
        if (credentialId.isNullOrBlank()) return null
        val store = com.viel.aplayer.library.vfs.sourceProvider.webdav.WebDavCredentialStore(getApplication())
        return store.get(credentialId)
    }

    /**
     * Retrieve ABS credentials (To pre-fill edit form inputs in UI dialogs)
     */
    suspend fun getAbsCredential(credentialId: String?): com.viel.aplayer.abs.auth.AbsCredential? {
        if (credentialId.isNullOrBlank()) return null
        return absCredentialStore.get(credentialId)
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
                val token = reuseSnapshot?.token 
                    ?: if (password.isBlank() && editingRootId != null) {
                        val existingRoot = database.libraryRootDao().getRootById(editingRootId)
                        val cred = existingRoot?.credentialId?.let { absCredentialStore.get(it) }
                        cred?.token ?: throw IllegalArgumentException("ABS 凭据读取失败且密码为空")
                    } else {
                        requireNotNull(absApiClient.login(baseUrl, username, password).user?.token)
                    }
                val connection = reuseSnapshot?.connection ?: absConnectionTester.testConnection(baseUrl, token)
                val credential = if (editingRootId != null) {
                    val existingRoot = database.libraryRootDao().getRootById(editingRootId)
                    val existingCredId = existingRoot?.credentialId
                    absCredentialStore.save(
                        baseUrl = baseUrl,
                        token = token,
                        userId = connection.userId,
                        username = connection.username,
                        credentialId = existingCredId ?: UUID.randomUUID().toString()
                    )
                } else {
                    absCredentialStore.save(
                        baseUrl = baseUrl,
                        token = token,
                        userId = connection.userId,
                        username = connection.username
                    )
                }
                val root = if (editingRootId != null) {
                    libraryRootGateway.updateAbsLibraryRoot(
                        id = editingRootId,
                        credentialId = credential.id,
                        libraryId = libraryId,
                        displayName = libraryName
                    )
                } else {
                    libraryRootGateway.addAbsLibraryRoot(
                        credentialId = credential.id,
                        libraryId = libraryId,
                        displayName = libraryName
                    )
                }
                if (editingRootId != null) {
                    database.directoryCacheDao().deleteByRootId(editingRootId)
                    val recoveryChecker = com.viel.aplayer.library.availability.MissingBookFileRecoveryChecker(getApplication())
                    recoveryChecker.recoverMissingAudioFiles()
                }
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
                val msg = if (editingRootId != null) "ABS server 已更新，开始同步" else "ABS server 已添加，开始同步"
                _uiEvents.tryEmit(UiEvent.ShowToast(msg))
                _absConnectionState.value = AbsConnectionUiState()
                launchAutoAbsSync(root)
            }.onFailure { error ->
                val redactedMessage = (error.message ?: "ABS server 保存失败").redactAbsError()
                AbsSettingsLogger.logAddServerFailure(
                    baseUrl = baseUrl,
                    username = username,
                    libraryId = libraryId,
                    errorClass = error::class.java.simpleName,
                    message = redactedMessage
                )
                // Expose authentication failures (To detail precise server constraints on the settings interface)
                // Toast details error payload rather than generic failure messages.
                _uiEvents.tryEmit(UiEvent.ShowToast("ABS server 保存失败：$redactedMessage"))
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
                // ABS testing authentication logic enhancement: Support token reuse when testing connections in editing mode with blank password.
                val token = if (password.isBlank() && editingRootId != null) {
                    val existingRoot = database.libraryRootDao().getRootById(editingRootId)
                    val cred = existingRoot?.credentialId?.let { absCredentialStore.get(it) }
                    cred?.token ?: throw IllegalArgumentException("ABS 凭据读取失败且密码为空")
                } else {
                    val login = absApiClient.login(baseUrl, username, password)
                    requireNotNull(login.user?.token)
                }
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

    // ABS connection status reset: Expose hook to clear authentication test states upon input changes or window exits.
    fun resetAbsConnectionState() {
        _absConnectionState.value = AbsConnectionUiState()
    }

    fun syncAbsRoot(rootId: String) {
        viewModelScope.launch {
            val preflight = libraryRootGateway.refreshLibraryRootStatus(rootId) ?: return@launch
            if (!preflight.isSyncAvailable) {
                // Manual ABS Sync Preflight (Blocks plan inspection when the selected root is unavailable)
                // Plan inspection talks to the remote server, so the root status must be refreshed and validated before any preview request is sent.
                _uiEvents.tryEmit(UiEvent.ShowToast(buildRootUnavailableSyncMessage(preflight)))
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
                _uiEvents.tryEmit(UiEvent.ShowToast("ABS 同步已开始"))
            } else {
                _uiEvents.tryEmit(UiEvent.ShowToast("ABS 同步已在进行中"))
            }
        }
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
private fun formatLibraryRootSyncTime(timestampMs: Long?): String =
    timestampMs?.takeIf { it > 0L }?.let(::formatDate) ?: "未同步"

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

// WebDAV connection snapshot model: Store verified WebDAV parameters to mirror ABS reuse behavior.
internal data class WebDavConnectionReuseSnapshot(
    val url: String,
    val username: String,
    val password: String,
    val basePath: String
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
