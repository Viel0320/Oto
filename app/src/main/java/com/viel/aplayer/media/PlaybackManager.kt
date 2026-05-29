package com.viel.aplayer.media

import android.content.ComponentName
import android.content.Context
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.viel.aplayer.data.AppSettingsRepository
import com.viel.aplayer.media.service.PlaybackService
import com.viel.aplayer.ui.player.components.SubtitleLine

import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(UnstableApi::class)
class PlaybackManager private constructor(context: Context) {

    private val appContext = context.applicationContext
    // 新增 CoroutineExceptionHandler 以捕获全局协程中由于未知原因（如文件损坏、网络请求失败等）抛出的异常，防止异常直接导致进程 Crash。
    private val exceptionHandler = CoroutineExceptionHandler { _, exception ->
        android.util.Log.e("PlaybackManager", "Unhandled coroutine exception in PlaybackManager", exception)
    }
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob() + exceptionHandler)

    // 
    // 在 M4.1 重构中，为了彻底摆脱庞大重量级的 LibraryRepository 依赖，
    // 获取全局 Application 中的 container，并提取只读的 bookQueryGateway 以及用于进度保存的 progressGateway。
    private val container = (appContext as com.viel.aplayer.APlayerApplication).container
    private val bookQueryGateway = container.bookQueryGateway
    private val progressGateway = container.progressGateway

    // 实例化 AppSettingsRepository 以便动态获取和监控用户的 HTTP 明文流量配置权限。
    private val settingsRepository = AppSettingsRepository.getInstance(appContext)
    // 实例化新的 AutoRewindManager 以便对自动回退逻辑进行精细化管理与状态维护。
    private val autoRewindManager = AutoRewindManager.getInstance(appContext)

    // 进度同步追踪器实例，用于隔离进度高频轮询更新与底层的数据库落盘，实现单一职责设计与解耦。
    private val progressSyncTracker: ProgressSyncTracker

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var mediaController: MediaController? = null
    // 为本次桌面 widget 改动添加注释：记录异步连接 MediaController 期间的 autoplay 意图，确保 widget 冷启动恢复播放不会因为控制器尚未就绪而丢指令。
    private var pendingPlayWhenReady = false

    // Exposed Flows for UI to observe
    private val _playbackState = MutableStateFlow(Player.STATE_IDLE)
    val playbackState = _playbackState.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying = _isPlaying.asStateFlow()

    private val _currentMediaItem = MutableStateFlow<MediaItem?>(null)
    val currentMediaItem = _currentMediaItem.asStateFlow()

    // 底层的 Media3/ExoPlayer 已经通过 Mp3Extractor 和 DefaultRenderersFactory 彻底屏蔽了内置元数据和字幕轨道的解析与创建，
    // 因此前后台桥接层无需再为任何媒体轨道维护内置字幕、元数据条目的缓存流或相关的事件监听器，此处仅保留用于承载物理外置字幕的 Flow 供 UI 层订阅
    private val _currentSubtitles = MutableStateFlow<List<SubtitleLine>>(emptyList())
    val currentSubtitles = _currentSubtitles.asStateFlow()

    // 新增一次性 UI 反馈事件流，向外广播由 PlaybackService 发出的自定义界面提示事件（如静音跳过）
    private val _uiEvents = MutableSharedFlow<com.viel.aplayer.ui.common.UiEvent>(extraBufferCapacity = 1)
    val uiEvents = _uiEvents.asSharedFlow()

    // 公开事件分发方法，供外部组件（如 PlayerSettingsManager 动作检测）安全向 UI 线程发射一次性弹窗提示。
    fun sendUiEvent(event: com.viel.aplayer.ui.common.UiEvent) {
        _uiEvents.tryEmit(event)
    }

    private val _currentPosition = MutableStateFlow(0L)
    val currentPosition = _currentPosition.asStateFlow()

    private val _duration = MutableStateFlow(0L)
    val duration = _duration.asStateFlow()

    private val _playbackSpeed = MutableStateFlow(1.0f)
    val playbackSpeed = _playbackSpeed.asStateFlow()

    private var currentPlan: BookPlaybackPlan? = null

    // 物理记录播放器前一时刻真实的播放状态，用以作为核心依据检测用户点击暂停、耳机拔出等导致的“播放->暂停”状态跃迁，以无缝触发自动回退功能。
    private var lastIsPlaying = false

    /** 当前播放计划的 bookId，非挂起，可从任意线程安全读取。 */
    val currentPlayingBookId: String?
        get() = currentPlan?.bookId

    init {
        // 构建进度同步追踪器，并通过 lambda 回调无缝对接 Flow 值的更新，实现管道式状态上报。
        progressSyncTracker = ProgressSyncTracker(
            context = appContext,
            bookQueryGateway = bookQueryGateway,
            progressGateway = progressGateway,
            scope = scope,
            getController = { mediaController },
            getCurrentPlan = { currentPlan },
            onProgressUpdated = { positionMs, durationMs ->
                _currentPosition.value = positionMs
                _duration.value = durationMs
            }
        )
        initializeController()
        progressSyncTracker.startPolling()
    }

    private fun initializeController() {
        val sessionToken = SessionToken(appContext, ComponentName(appContext, PlaybackService::class.java))
        // 
        // 重构 MediaController.Builder，向其注入自定义的 MediaController.Listener 接口，
        // 用以监听并拦截来自后台 PlaybackService 发出的自定义媒体会话命令（如 EVENT_SKIP_SILENCE 静音跳过触发通知），
        // 收到后将其转换为 UiEvent.ShowToast 分发到全局 uiEvents 共享流中，交由宿主 UI 层进行精致渲染弹出。
        controllerFuture = MediaController.Builder(appContext, sessionToken)
            .setListener(object : MediaController.Listener {
                override fun onCustomCommand(
                    controller: MediaController,
                    command: androidx.media3.session.SessionCommand,
                    args: android.os.Bundle
                ): ListenableFuture<androidx.media3.session.SessionResult> {
                    if (command.customAction == "EVENT_SKIP_SILENCE") {
                        _uiEvents.tryEmit(com.viel.aplayer.ui.common.UiEvent.ShowToast("已自动跳过空白静音片段"))
                    }
                    return com.google.common.util.concurrent.Futures.immediateFuture(
                        androidx.media3.session.SessionResult(androidx.media3.session.SessionResult.RESULT_SUCCESS)
                    )
                }
            })
            .buildAsync()
        
        controllerFuture?.addListener({
            // 使用 scope.launch 开启协程，并在其中通过 withContext(Dispatchers.IO) 安全地调用 
            // ListenableFuture.get()。虽然此时 Future 已完成，但 get() 仍被 IDE 视为阻塞方法，
            // 这样做可以消除“在非阻塞上下文中调用阻塞方法”的警告，并符合协程架构规范。
            scope.launch {
                try {
                    val controller = withContext(Dispatchers.IO) { controllerFuture?.get() }
                    mediaController = controller
                    controller?.let { conn ->
                        setupController(conn)
                        // Cold-start restore may set the playback plan before MediaController connects; apply it once ready.
                        currentPlan?.let { setBookPlaybackPlan(it) }
                        // 详尽的中文注释：由于桌面小组件已彻底下线，此处移除原有的 PlayerWidgetProvider.updateAll 更新小组件状态的调用
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }, ContextCompat.getMainExecutor(appContext))
    }

    private fun setupController(controller: MediaController) {
        // 在控制器连接成功进行初始化赋值时，同步设定 lastIsPlaying 初始状态快照。
        lastIsPlaying = controller.isPlaying
        _isPlaying.value = controller.isPlaying
        _playbackState.value = controller.playbackState
        _currentMediaItem.value = controller.currentMediaItem
        progressSyncTracker.updateProgress(controller)
        _playbackSpeed.value = controller.playbackParameters.speed

        controller.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                val wasPlaying = lastIsPlaying
                lastIsPlaying = isPlaying
                _isPlaying.value = isPlaying
                // 详尽的中文注释：随着桌面小组件功能的移除，此处不再需要在播放/暂停状态改变时刷新小组件，已移除 PlayerWidgetProvider.updateAll 逻辑
                progressSyncTracker.saveProgress()

                // 在后台协程中，实时将当前的物理播放状态（是否在播）同步写入 isLastPlaybackInterrupted，
                // 用于冷启动时精准甄别上一次是否为非正常中断（强杀/闪退）以触发进度自愈机制。
                scope.launch {
                    try {
                        settingsRepository.updateLastPlaybackInterrupted(isPlaying)
                    } catch (e: Exception) {
                        android.util.Log.e("PlaybackManager", "更新中断持久化标志失败", e)
                    }
                }

                // 
                // 如果先前处于正在播放状态（wasPlaying 为 true），当前变化为了暂停或停止播放状态（isPlaying 为 false），
                // 且用户在设置里开启了大于 0 秒的自动回退时间，则自动执行位置定位回退。
                // 委托给 AutoRewindManager 进行暂停逻辑判断与回退处理。
                if (wasPlaying && !isPlaying) {
                    autoRewindManager.handlePause(
                        controller = controller,
                        currentPlan = currentPlan,
                        scope = scope,
                        onProgressUpdated = { conn -> progressSyncTracker.updateProgress(conn) },
                        onSaveProgress = { progressSyncTracker.saveProgress() }
                    )
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                _playbackState.value = playbackState
                if (playbackState == Player.STATE_READY) {
                    progressSyncTracker.updateProgress(controller)
                }
                // 详尽的中文注释：由于桌面小组件功能已废弃，此处在播放队列准备或结束状态变更时不再需要同步更新小组件状态
                progressSyncTracker.saveProgress()
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                _currentMediaItem.value = mediaItem
                // 字幕文本由 PlayerViewModel 按当前文件懒加载，这里只清掉上一文件的缓存。
                _currentSubtitles.value = emptyList()
                progressSyncTracker.updateProgress(controller)
                
                // 详尽的中文注释：小组件已移除，切换分轨时不再执行小组件后台状态刷新逻辑
                progressSyncTracker.saveProgress()
            }

            override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
                progressSyncTracker.updateProgress(controller)
            }

            override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
                _playbackSpeed.value = playbackParameters.speed
            }
        })
    }

    /**
     * 将当前进度持久化到数据库。
     * 该方法保留以维持与外部调用组件及 AutoRewindManager 原本的契约，内部委托给 progressSyncTracker 执行。
     */
    fun saveProgress() {
        progressSyncTracker.saveProgress()
    }

    /**
         * 为书籍设定播放计划。整个方法执行在 scope 协程环境主线程中。
     * 首先读取持久化设置快照，如果 isLastPlaybackInterrupted（上次播放异常强杀中断）为 true 且开启了回退秒数，
     * 则对当前载入的起始进度进行减去回退时长的进度自愈补偿，最小值不低于 0，
     * 接着通过 copy 构造出自愈后的计划，并同步在数据库重置中断标志为 false 以免后续换歌等发生重复自愈。
     * 最后，百分之百还原原作者关于明文 HTTP 安全校验、Toast 弹出以及 withContext/executeOnMain 等多线程调度设计。
     */
    fun setBookPlaybackPlan(plan: BookPlaybackPlan, playWhenReady: Boolean = false) {
        scope.launch {
                // 记录 PlaybackManager 从拿到播放计划到真正准备下发给 MediaController 的耗时，
            // 这样可以看出 DataStore 读取、异常中断修正等前置逻辑是否在这一层拖慢启动。
            val setPlanStart = SystemClock.elapsedRealtime()
            // 异步从设置仓库中读取最新的全局持久化快照。
            val settingsReadStart = SystemClock.elapsedRealtime()
            val settings = settingsRepository.settingsFlow.first()
            val settingsReadCost = SystemClock.elapsedRealtime() - settingsReadStart
            // 异常中断进度自愈机制已前置到应用冷启动阶段执行，此处直接使用传入的 plan
            val finalPlan = plan

            com.viel.aplayer.logger.PlaybackTimingLogger.logSetPlanEntry(
                bookId = plan.bookId,
                settingsReadMs = settingsReadCost,
                originalStart = plan.startGlobalPositionMs,
                finalStart = finalPlan.startGlobalPositionMs,
                fileCount = finalPlan.files.size,
                playWhenReady = playWhenReady
            )

            // 完成进度自愈判定后，切回 Dispatchers.Main 线程，百分之百还原原作者的所有多线程渲染及安全检查逻辑。
            withContext(Dispatchers.Main) {
                // 在切换或加载新的播放计划前，强行将 ignoreNextAutoRewind 设为 true。
                // 这样能完美拦截由于切换书籍重载媒体资源导致播放器暂停状态改变时，误触发的针对上一本书或新书初始进度的自动回退动作。
                autoRewindManager.ignoreNextAutoRewind = true

                this@PlaybackManager.currentPlan = finalPlan
                this@PlaybackManager.pendingPlayWhenReady = playWhenReady

                // 性能优化：立即将自愈后最终计划中的初始进度和总时长推送到 UI 流，避免闪烁
                val totalDur = finalPlan.files.sumOf { it.durationMs }
                _currentPosition.value = finalPlan.startGlobalPositionMs
                _duration.value = totalDur
                // 详尽的中文注释：桌面小组件功能已完全停用，加载新播放计划时移除对 PlayerWidgetProvider.updateAll 的调用

                // 播放器计划只接收 VFS URI；本地/非本地策略后续按 LibraryRoot.sourceType 判断，不再从 BookFile.uri 探测 HTTP。
                val hasHttp = false
                if (hasHttp) {
                    scope.launch {
                        val isAllowed = settingsRepository.settingsFlow.first().isCleartextTrafficAllowed
                        if (!isAllowed) {
                            withContext(Dispatchers.Main) {
                                android.widget.Toast.makeText(appContext, "安全拦截：明文 HTTP 播放未授权。请在设置中允许。", android.widget.Toast.LENGTH_LONG).show()
                            }
                            return@launch
                        }
                        withContext(Dispatchers.Main) { applyPlaybackPlan(finalPlan) }
                    }
                } else {
                    val preApplyCost = SystemClock.elapsedRealtime() - setPlanStart
                    com.viel.aplayer.logger.PlaybackTimingLogger.logPreApplyCost(
                        bookId = plan.bookId,
                        preApplyCostMs = preApplyCost
                    )
                    executeOnMain { applyPlaybackPlan(finalPlan) }
                }
            }
        }
    }

    private fun applyPlaybackPlan(plan: BookPlaybackPlan) {
        // 把 applyPlaybackPlan 拆成“MediaItem 构建”和“controller.setMediaItems/prepare 下发”两段，
        // 用来判断多分轨队列构造还是 MediaController 会话调用更耗时。
        val applyPlanStart = SystemClock.elapsedRealtime()
        // 调用 PlaybackPlanBuilder 的 buildMediaItems 接口对播放计划进行转换，完全剥离传输实体构建细节，实现工厂化解耦
        val mediaItems = PlaybackPlanBuilder.buildMediaItems(plan)
        val mediaItemsBuildCost = SystemClock.elapsedRealtime() - applyPlanStart
        val (fileIndex, positionInFile) = PositionMapper.globalToFilePosition(plan.startGlobalPositionMs, plan.files)
        
        mediaController?.let { controller ->
            val controllerDispatchStart = SystemClock.elapsedRealtime()
            controller.setMediaItems(mediaItems, fileIndex, positionInFile)
            controller.prepare()
            val controllerDispatchCost = SystemClock.elapsedRealtime() - controllerDispatchStart
            val totalApplyCost = SystemClock.elapsedRealtime() - applyPlanStart
            com.viel.aplayer.logger.PlaybackTimingLogger.logApplyPlan(
                bookId = plan.bookId,
                mediaItemsBuildMs = mediaItemsBuildCost,
                controllerDispatchMs = controllerDispatchCost,
                totalMs = totalApplyCost,
                fileCount = mediaItems.size,
                fileIndex = fileIndex,
                positionInFile = positionInFile
            )
            if (pendingPlayWhenReady) {
                // 为本次桌面 widget 改动添加注释：在 prepare 之后消费 autoplay 请求，避免先 play 后 setMediaItems 的异步时序丢失。
                pendingPlayWhenReady = false
                controller.play()
                        // 明确记录 autoplay 指令已经被消费，方便和后续真正出声时间做对比。
                com.viel.aplayer.logger.PlaybackTimingLogger.logAutoplayConsumed(plan.bookId)
            }
            // 当前文件字幕由 ViewModel 监听 currentMediaItem 后按需解析。
            _currentSubtitles.value = emptyList()
            // Loading a book should create/update BookProgress immediately, even before playback events fire.
            progressSyncTracker.persistProgress(plan.bookId, fileIndex, positionInFile)
        } ?: run {
            val totalApplyCost = SystemClock.elapsedRealtime() - applyPlanStart
            com.viel.aplayer.logger.PlaybackTimingLogger.logApplyPlanSkipped(
                bookId = plan.bookId,
                totalMs = totalApplyCost
            )
        }
    }

    // Commands
    fun play() {
        executeOnMain { mediaController?.play() }
    }

    fun pause() {
        executeOnMain { mediaController?.pause() }
    }


    /**
     * 获取或设置当前播放器的内部音量比例（0.0f - 1.0f）。
     * 用于在音量渐隐机制中实现平滑、无感知的对数音量衰减，而不惊扰系统全局的物理音量设置。
     */
    var playerVolume: Float
        get() = mediaController?.volume ?: 1.0f
        set(value) {
            executeOnMain {
                mediaController?.volume = value.coerceIn(0.0f, 1.0f)
            }
        }

    fun seekTo(globalPositionMs: Long) {
        // 为本次桌面 widget 崩溃修复添加注释：MediaController 只能在创建它的 application thread 上访问；widget 会从后台广播线程触发 seek，因此这里先切回 PlaybackManager 的主线程 scope 再读取 currentMediaItem。
        scope.launch {
            val controller = mediaController ?: return@launch
            val mediaId = controller.currentMediaItem?.mediaId ?: return@launch
            if (!mediaId.contains(":")) return@launch
            val bookId = mediaId.substringBefore(":")
            // 使用 bookQueryGateway 接口查询指定书籍的物理音频分轨，以执行高精度的 seek 定位跳转
            val files = bookQueryGateway.getFilesForBookSync(bookId)
            if (files.isNotEmpty()) {
                val totalDuration = files.sumOf { it.durationMs }
                val targetGlobal = globalPositionMs.coerceIn(0L, totalDuration.coerceAtLeast(0L))
                val (fileIndex, positionInFile) = PositionMapper.globalToFilePosition(targetGlobal, files)
                // UI 传入全书位置；这里用当前书籍文件列表恢复到真实播放队列位置。
                controller.seekTo(fileIndex, positionInFile)
                controller.play()
                _currentPosition.value = targetGlobal
                _duration.value = totalDuration
                // 跳转后的字幕由 currentMediaItem 变化触发懒加载，避免 seek 时同步解析字幕。
                _currentSubtitles.value = emptyList()
                // User-initiated seek must persist immediately so BookProgress is not dependent on later callbacks.
                progressSyncTracker.persistProgress(bookId, fileIndex, positionInFile)
            }
        }
    }

    fun setPlaybackSpeed(speed: Float) {
        executeOnMain {
            mediaController?.setPlaybackSpeed(speed)
        }
    }

    fun release() {
        progressSyncTracker.stopPolling()
        scope.cancel()
        controllerFuture?.let {
            MediaController.releaseFuture(it)
            controllerFuture = null
        }
        mediaController = null
        INSTANCE = null
    }

    /**
     * 异步获取已连接的 MediaController 实例。
     * 如果当前 mediaController 已建立连接则立即返回；
     * 如果 controllerFuture 处于连接中，则通过 suspendCancellableCoroutine 挂起并等待连接完成，
     * 以便在 Activity 已销毁或后台重建的异步时序下依然能获取到真实的 MediaController，
     * 解决删除书库时因连接尚未就绪导致 getCurrentBookId() 漏判后台播放的缺陷。
     */
    /**
     * 为每一次改动添加详异步获取已连接的 MediaController 实例。
     * 直接利用 withContext(Dispatchers.IO) 包装阻塞式的 ListenableFuture.get() 调用。
     * 该方式能完美消除 IDE 对“非阻塞上下文调用阻塞方法”的警告，因为 Dispatchers.IO 允许阻塞。
     * 协程会在此处挂起并释放主线程，直到控制器连接完成并返回结果，代码逻辑大幅精简且类型安全。
     */
    suspend fun getController(): MediaController? {
        val controller = mediaController
        if (controller != null) return controller

        val future = controllerFuture ?: return null
        return try {
            withContext(Dispatchers.IO) {
                // 即使 Future 已 Done，.get() 依然是阻塞调用，必须在 IO 线程中执行。
                future.get()
            }.also { mediaController = it }
        } catch (e: Exception) {
            android.util.Log.e("PlaybackManager", "获取 MediaController 失败", e)
            null
        }
    }

    /**
     * 异步获取当前正在播放或计划播放的书籍 ID。
     * 优先等待 MediaController 异步连接就绪后再行检索 currentMediaItem，
     * 确保在 UI 退出且 PlaybackManager 被 release 重新获取单例的极端生命周期下，
     * 依然能准确捕捉后台真实的播放书籍 ID。
     */
    fun getCurrentBookId(): String? {
        // 为本次桌面 widget 崩溃修复添加注释：该方法可能被 IO 协程调用，优先读取 StateFlow 快照，避免跨线程直接访问 MediaController.currentMediaItem。
        val mediaId = currentMediaItem.value?.mediaId ?: currentPlan?.bookId
        return if (mediaId != null && mediaId.contains(":")) {
            mediaId.substringBefore(":")
        } else {
            mediaId
        }
    }

    /**
     * 异步停止并清空受影响的书籍播放队列，重置播放状态 Flow。
     * 首先挂起等待 MediaController 异步连接完毕，以防在未就绪时调用导致底层 ExoPlayer 无法接收到 pause 和 stop 指令，
     * 确保即使在后台被动调用的时序下也能彻底切断底层音频播放流。
     */
    suspend fun stopPlayback() {
        val controller = getController()
        withContext(Dispatchers.Main) {
            // 在主动停止播放器前，强行将 ignoreNextAutoRewind 设为 true。
            // 这样可以拦截由于主动暂停/清除播放资源导致物理播放状态回调触发时，无意义且有隐患的自动回退与保存进度操作。
            autoRewindManager.ignoreNextAutoRewind = true
            controller?.let { conn ->
                conn.pause()
                conn.stop()
                conn.clearMediaItems()
            }
            currentPlan = null
            _currentMediaItem.value = null
            _currentPosition.value = 0L
            _duration.value = 0L
            _isPlaying.value = false
            _playbackState.value = Player.STATE_IDLE
        }
    }

    private fun executeOnMain(action: () -> Unit) {
        if (Thread.currentThread() == android.os.Looper.getMainLooper().thread) {
            action()
        } else {
            scope.launch(Dispatchers.Main) { action() }
        }
    }

    companion object {
        @Volatile
        private var INSTANCE: PlaybackManager? = null

        fun getInstance(context: Context): PlaybackManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: PlaybackManager(context).also { INSTANCE = it }
            }
        }
    }
}
