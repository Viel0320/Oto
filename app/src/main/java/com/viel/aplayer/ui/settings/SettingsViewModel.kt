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
 * 设置页面的 ViewModel，负责管理持久化配置的交互。
 */
class SettingsViewModel(application: Application) : AndroidViewModel(application) {
    private val container = (application as APlayerApplication).container
    private val settingsRepository = container.settingsRepository
    private val absCatalogSynchronizer = container.absCatalogSynchronizer
    private val absPlaybackSessionSyncer = container.absPlaybackSessionSyncer
    private val absSyncTaskCoordinator = container.absSyncTaskCoordinator
    private val absCredentialStore = AbsCredentialStore.getInstance(application.applicationContext)
    // 详尽的中文注释：设置页里的“测试连接”和“添加服务器”共用同一个 ABS API client，
    // 这样后续可以直接复用一次成功测试拿到的登录结果，而不是在添加时再平白多跑一遍登录链路。
    private val absApiClient = com.viel.aplayer.abs.net.RealAbsApiClient()
    private val absConnectionTester = com.viel.aplayer.abs.sync.AbsConnectionTester(absApiClient)
    private val absSyncWorkScheduler = com.viel.aplayer.abs.sync.AbsSyncWorkScheduler(application.applicationContext)
    private val database = com.viel.aplayer.data.db.AppDatabase.getInstance(application.applicationContext)
    // 详尽的中文注释：最近一次“测试连接成功”的快照只保存在 ViewModel 内存里，
    // 不进数据库、不进 DataStore，也不暴露给 UI。它只服务于“刚测试成功就点添加”的短链路提速。
    private var lastSuccessfulAbsConnection: AbsConnectionReuseSnapshot? = null
    
    // 
    // 在 M5b.1 迁移中，将 SettingsViewModel 中对旧仓库 libraryRepository 的依赖彻底剥离，
    // 降级解耦为书库根网关 libraryRootGateway 与增量扫描网关 scanScheduler。
    private val libraryRootGateway = container.libraryRootGateway
    private val scanScheduler = container.scanScheduler
    
    /**
     * 跨域书库根目录删除协调器用例，用于替代原有的门面仓库调用，以符合领域解耦规范。
     */
    private val deleteLibraryRootUseCase = container.deleteLibraryRootUseCase
    private val _uiEvents = MutableSharedFlow<UiEvent>(extraBufferCapacity = 1)
    val uiEvents: SharedFlow<UiEvent> = _uiEvents.asSharedFlow()
    private val _absConnectionState = MutableStateFlow(AbsConnectionUiState())
    val absConnectionState: StateFlow<AbsConnectionUiState> = _absConnectionState.asStateFlow()
    private val _absSyncConfirmationState = MutableStateFlow<AbsSyncConfirmationState?>(null)
    val absSyncConfirmationState: StateFlow<AbsSyncConfirmationState?> = _absSyncConfirmationState.asStateFlow()

    /** 暴露给 UI 的设置状态流 */
    val settingsState: StateFlow<AppSettings> = settingsRepository.settingsFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = AppSettings()
        )

    /** 暴露给 UI 的媒体库根目录流 */
    // 使用 libraryRootGateway 网关响应式观察观察注册的书库目录并获取内存快照初始缓存值，以消解首帧空白和布局闪烁
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
            // Settings entry should show current SAF grant status, including revoked roots.
            // 延迟 500 毫秒后再触发系统的 SAF 物理授权检测，以完美避开 Activity 启动转场动画及首帧绘制的核心渲染时间，保证界面展示绝对丝滑
            kotlinx.coroutines.delay(500)
            // 使用 libraryRootGateway 的 refreshLibraryRootStatuses 校验书库目录可达性
            libraryRootGateway.refreshLibraryRootStatuses()
        }
    }

    init {
        viewModelScope.launch {
            // Settings entry should show current SAF grant status, including revoked roots.
            // 延迟 500 毫秒后再触发系统的 SAF 物理授权检测，以完美避开 Activity 启动转场动画及首帧绘制的核心渲染时间。
            kotlinx.coroutines.delay(500)
            // 使用 libraryRootGateway 的 refreshLibraryRootStatuses 校验书库目录可达性。
            libraryRootGateway.refreshLibraryRootStatuses()
        }
        viewModelScope.launch {
            // 详尽的中文注释：设置页只负责订阅应用级同步任务结果，
            // 同步本身已经脱离 ViewModel 生命周期，因此切换界面不会中断真正的 ABS 同步过程。
            absSyncTaskCoordinator.events.collect { event ->
                val message = when {
                    event.summary != null -> "ABS 后台同步完成：成功添加 ${event.summary.addedBooks} 本，失败 ${event.summary.failedItems} 本"
                    event.errorMessage != null -> "ABS 后台同步失败：${event.errorMessage.redactAbsError()}"
                    else -> null
                }
                if (message != null) {
                    _uiEvents.tryEmit(UiEvent.ShowToast(message))
                }
            }
        }
    }

    fun refreshLibraryRootStatuses() {
        viewModelScope.launch {
            // Route entry calls this explicitly because the SettingsViewModel may be created before navigation.
            // 手动触发的刷新依然走异步协程检测，保证物理可达性更新。
            // 通过 libraryRootGateway 异步校验当前全部已添加的 SAF 目录授权可达状态
            libraryRootGateway.refreshLibraryRootStatuses()
        }
    }

    // 
    // 在设置页中选择媒体库目录后的回调逻辑，负责获取持久化 SAF 授权，将其存入数据库并异步触发 USER 手动增量扫描任务。
    // 这能让设置页完全独立处理 SAF 动作，从而将 LibraryViewModel 彻底解耦，消除 Activity 切换时的冷启动扫描问题。
    fun onLibraryRootSelected(uri: Uri) {
        // 利用 libraryRootGateway 写入新选择的本地 SAF 授权目录，并在应用级后台执行同步
        libraryRootGateway.addLibraryRootAndScheduleSync(uri)
    }

    fun onWebDavRootSubmitted(
        url: String,
        username: String,
        password: String,
        displayName: String,
        basePath: String
    ) {
        // 使用 libraryRootGateway 在后台注册并立即调度 WebDAV 网络书库目录的文件同步
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
            // 详尽中文注释：添加 ABS server 属于设置页入口动作，因此归档到设置路径 logger，方便和底层认证/同步日志分层排查。
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
                // 详尽的中文注释：只有当当前输入仍然命中最近一次测试连接成功的同一服务器、同一账号和同一书库时，
                // 才允许跳过第二遍登录与鉴权请求，从而缩短“测试成功后立刻点击添加”的等待时间。
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
                // 详尽的中文注释：添加成功后保留这次有效连接快照，支持同一会话里继续给同一服务器添加别的书库，
                // 从而把用户的重复等待压到最小。
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
                // 详尽的中文注释：添加服务器同样可能在 `/status` 版本校验阶段被拒绝，
                // 因此这里必须把具体失败原因通过 Toast 透出给用户，而不能只给模糊的“添加失败”。
                _uiEvents.tryEmit(UiEvent.ShowToast("ABS server 添加失败：$redactedMessage"))
            }
        }
    }

    fun testAbsConnection(baseUrl: String, username: String, password: String) {
        viewModelScope.launch {
            // 详尽中文注释：测试连接是设置页的直接用户动作，入口与结果统一放到设置 logger，后续再用认证 logger 补底层 REST 细节。
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
                // 详尽的中文注释：测试连接成功后，把 token 与书库列表缓存在 ViewModel 内存里，
                // 供后续紧接着的“添加服务器”直接复用，避免再次触发完整的登录和鉴权链路。
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
                // 详尽的中文注释：只要本轮测试失败，就立刻清空上一次成功快照，
                // 防止用户改了服务器地址或账号后，后续“添加服务器”错误复用旧 token。
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
            // 详尽中文注释：手动同步的入口先记在设置路径 logger，便于和底层同步日志按时间关联。
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
        // 详尽中文注释：后台同步已入队需要独立日志，否则只看 worker 侧无法区分“用户没点”还是“队列没起”。
        AbsSettingsLogger.logScheduleBackgroundSync(rootId)
        absSyncWorkScheduler.enqueue(rootId)
    }

    fun triggerRescan() {
        // 调用 scanScheduler 网关异步提交 USER 手动增量重扫指令
        scanScheduler.scheduleLibrarySync("USER")
    }

    fun toggleChapterProgressMode(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.updateChapterProgressMode(enabled)
        }
    }

    // 新增切换是否允许 HTTP 明文流量持久化配置的交互方法。
    fun toggleCleartextTrafficAllowed(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.updateCleartextTrafficAllowed(enabled)
        }
    }

    // 删除库根目录并释放 SAF 授权。通过跨域协调用例执行，安全地处理停播与文件清理，然后通过 Toast 通知用户结果。
    fun deleteLibraryRoot(root: LibraryRootEntity) {
        viewModelScope.launch {
            // 详尽中文注释：删除 server 是设置入口上的高风险动作，先记录入口，便于与停播和数据清理日志对时序。
            AbsSettingsLogger.logDeleteServerStart(rootId = root.id, sourceType = root.sourceType)
            // 调用高层用例执行删除，该用例会智能判断是否需要在此之前触发紧急停播
            val playbackWasStopped = deleteLibraryRootUseCase.invoke(root)
            val message = if (playbackWasStopped) {
                "媒体库已移除，当前播放已停止"
            } else {
                "媒体库已移除"
            }
            // 详尽中文注释：记录删除完成与是否发生了紧急停播，帮助区分普通删除和“删除正在播放的库”。
            AbsSettingsLogger.logDeleteServerFinished(rootId = root.id, playbackStopped = playbackWasStopped)
            _uiEvents.tryEmit(UiEvent.ShowToast(message))
        }
    }

    /**
     * 切换自动跳过静音（Skip Silence）功能全局总开关的交互方法。
     * 经过重构，移除了自定义判定最小时长和温馨通知提示开关的交互逻辑。
     */
    fun toggleSkipSilenceEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.updateSkipSilenceEnabled(enabled)
        }
    }

    // 新增切换睡眠定时音量渐隐功能全局总开关的交互方法。
    fun toggleSleepFadeOutEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.updateSleepFadeOutEnabled(enabled)
        }
    }

    // 新增切换“摇晃手机重置睡眠定时器 (Shake-to-Reset)”全局总开关的交互方法。
    fun toggleShakeToResetEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.updateShakeToResetEnabled(enabled)
        }
    }

    // 新增设置页切换睡眠模式的交互方法，通过协程异步更新 DataStore 持久化配置，由 UI 组件触发。
    fun updateSleepMode(mode: SleepMode) {
        viewModelScope.launch {
            settingsRepository.updateSleepMode(mode)
        }
    }

    // 新增设置页切换悬浮层视觉效果模式的交互方法，统一写入 DataStore 供主页 and 播放器实时响应。
    fun updateGlassEffectMode(mode: GlassEffectMode) {
        viewModelScope.launch {
            settingsRepository.updateGlassEffectMode(mode)
        }
    }

    // 新增设置页修改自动回退播放进度秒数（0-30s）的交互方法，通过协程异步写入持久化 DataStore。
    fun updateAutoRewindSeconds(seconds: Int) {
        viewModelScope.launch {
            settingsRepository.updateAutoRewindSeconds(seconds)
        }
    }

    // 新增设置页切换通知避让（Notification Avoidance）功能全局开关的交互方法，通过协程异步写入持久化 DataStore。
    fun toggleNotificationAvoidanceEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.updateNotificationAvoidanceEnabled(enabled)
        }
    }

    /**
     * 详尽的中文注释：添加服务器成功后，立刻在 ViewModel 后台协程里触发一次 ABS catalog 同步。
     * 这里不阻塞“添加成功”这条主交互链，而是把同步结果通过 toast 回报给用户，
     * 这样既能满足“自动开始后台扫描”的体验要求，又不会让添加按钮一直卡到同步结束。
     */
    private fun launchAutoAbsSync(root: LibraryRootEntity) {
        // 详尽的中文注释：自动同步必须提升为应用级任务，不能依赖当前 SettingsViewModel 是否还存活。
        // 因此这里先尝试把任务交给应用级协调器；一旦成功入队，后面的旧 ViewModel 作用域实现就不再执行。
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
 * 详尽的中文注释：设置页成功测试连接后，保存在 ViewModel 内存里的复用快照。
 * 该快照不进 Room、不进 DataStore，也不暴露给 UI；只服务于“测试连接成功后立刻添加”的短链路提速。
 */
internal data class AbsConnectionReuseSnapshot(
    val baseUrl: String,
    val username: String,
    val token: String,
    val connection: com.viel.aplayer.abs.sync.AbsConnectionTestResult
)

/**
 * 详尽的中文注释：统一规范化设置页连接复用所使用的 baseUrl 比较值。
 * 这里只做 trim 和去尾斜杠，保证用户手动输入 `.../audiobookshelf` 与 `.../audiobookshelf/` 时可以命中同一快照。
 */
internal fun normalizeAbsBaseUrlForReuse(baseUrl: String): String =
    baseUrl.trim().trimEnd('/')

/**
 * 详尽的中文注释：判断“添加服务器”是否可以复用最近一次测试连接成功的快照。
 * 只有当 baseUrl、username 与快照完全对齐，且选中的 libraryId 仍存在于当时返回的书库列表中时，才允许复用。
 * 这样既减少重复请求，又避免把旧测试结果误套到新的服务器、账号或库选择上。
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
