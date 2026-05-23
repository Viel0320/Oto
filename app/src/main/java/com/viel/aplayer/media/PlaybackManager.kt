package com.viel.aplayer.media

import android.content.ComponentName
import android.content.Context
import androidx.annotation.OptIn
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import com.viel.aplayer.data.LibraryRepository
import com.viel.aplayer.data.AppSettingsRepository
import com.viel.aplayer.data.entity.BookProgressEntity
import com.viel.aplayer.media.service.PlaybackService
import com.viel.aplayer.ui.player.components.SubtitleLine
import com.viel.aplayer.widget.PlayerWidgetProvider

@OptIn(UnstableApi::class)
class PlaybackManager private constructor(context: Context) {

    private val appContext = context.applicationContext
    // 详尽的中文注释：新增 CoroutineExceptionHandler 以捕获全局协程中由于未知原因（如文件损坏、网络请求失败等）抛出的异常，防止异常直接导致进程 Crash。
    private val exceptionHandler = CoroutineExceptionHandler { _, exception ->
        android.util.Log.e("PlaybackManager", "Unhandled coroutine exception in PlaybackManager", exception)
    }
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob() + exceptionHandler)
    private val libraryRepository = LibraryRepository.getInstance(appContext)
    // 详尽的中文注释：实例化 AppSettingsRepository 以便动态获取和监控用户的 HTTP 明文流量配置权限。
    private val settingsRepository = AppSettingsRepository.getInstance(appContext)

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

    private val _metadataEntries = MutableStateFlow<List<androidx.media3.common.Metadata.Entry>>(emptyList())
    val metadataEntries = _metadataEntries.asStateFlow()

    private val _currentSubtitles = MutableStateFlow<List<SubtitleLine>>(emptyList())
    val currentSubtitles = _currentSubtitles.asStateFlow()

    // 详尽的中文注释：新增 embeddedSubtitles 共享事件数据流，用于向前台实时广播从 ExoPlayer 中监听提取出的内置歌词。
    private val _embeddedSubtitles = MutableSharedFlow<List<SubtitleLine>>(extraBufferCapacity = 1)
    val embeddedSubtitles = _embeddedSubtitles.asSharedFlow()

    // 为每一次改动添加详尽的中文注释：新增一次性 UI 反馈事件流，向外广播由 PlaybackService 发出的自定义界面提示事件（如静音跳过）
    private val _uiEvents = MutableSharedFlow<com.viel.aplayer.ui.common.UiEvent>(extraBufferCapacity = 1)
    val uiEvents = _uiEvents.asSharedFlow()

    // 为每一次改动添加详尽的中文注释：公开事件分发方法，供外部组件（如 PlayerSettingsManager 动作检测）安全向 UI 线程发射一次性弹窗提示。
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

    // 为每一次改动添加详尽的中文注释：物理记录播放器前一时刻真实的播放状态，用以作为核心依据检测用户点击暂停、耳机拔出等导致的“播放->暂停”状态跃迁，以无缝触发自动回退功能。
    private var lastIsPlaying = false

    // 为每一次改动添加详尽的中文注释：设置临时状态标志，如果为 true，则在播放器状态转换到暂停时不应用自动回退逻辑（比如在音频焦点被动抢占、被迫暂停时）。
    var ignoreNextAutoRewind: Boolean = false

    /** 当前播放计划的 bookId，非挂起，可从任意线程安全读取。 */
    val currentPlayingBookId: String?
        get() = currentPlan?.bookId

    init {
        initializeController()
        startProgressPolling()
    }

    private fun initializeController() {
        val sessionToken = SessionToken(appContext, ComponentName(appContext, PlaybackService::class.java))
        // 为每一次改动添加详尽的中文注释：
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
            // 详尽的中文注释：使用 scope.launch 开启协程，并在其中通过 withContext(Dispatchers.IO) 安全地调用 
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
                        // 为本次桌面 widget 改动添加注释：控制器连上后立即刷新小组件，让桌面状态跟随真实 MediaSession。
                        PlayerWidgetProvider.updateAll(appContext)
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }, ContextCompat.getMainExecutor(appContext))
    }

    private fun setupController(controller: MediaController) {
        // 为每一次改动添加详尽的中文注释：在控制器连接成功进行初始化赋值时，同步设定 lastIsPlaying 初始状态快照。
        lastIsPlaying = controller.isPlaying
        _isPlaying.value = controller.isPlaying
        _playbackState.value = controller.playbackState
        _currentMediaItem.value = controller.currentMediaItem
        _metadataEntries.value = extractMetadataEntries(controller)
        updateGlobalPositionAndDuration(controller)
        _playbackSpeed.value = controller.playbackParameters.speed

        controller.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                val wasPlaying = lastIsPlaying
                lastIsPlaying = isPlaying
                _isPlaying.value = isPlaying
                // 为本次桌面 widget 改动添加注释：播放/暂停按钮图标依赖此状态，变化后立即刷新所有播放器小组件。
                PlayerWidgetProvider.updateAll(appContext)
                saveProgress()

                // 为每一次改动添加详尽的中文注释：在后台协程中，实时将当前的物理播放状态（是否在播）同步写入 isLastPlaybackInterrupted，
                // 用于冷启动时精准甄别上一次是否为非正常中断（强杀/闪退）以触发进度自愈机制。
                scope.launch {
                    try {
                        settingsRepository.updateLastPlaybackInterrupted(isPlaying)
                    } catch (e: Exception) {
                        android.util.Log.e("PlaybackManager", "更新中断持久化标志失败", e)
                    }
                }

                // 为每一次改动添加详尽的中文注释：
                // 如果先前处于正在播放状态（wasPlaying 为 true），当前变化为了暂停或停止播放状态（isPlaying 为 false），
                // 且用户在设置里开启了大于 0 秒的自动回退时间，则自动执行位置定位回退。
                // 如果检测到 ignoreNextAutoRewind 为 true，说明此番暂停因临时失去焦点而被动触发，我们应跳过回退逻辑，并将标志重置为 false。
                if (wasPlaying && !isPlaying) {
                    if (ignoreNextAutoRewind) {
                        ignoreNextAutoRewind = false
                    } else {
                        applyAutoRewind()
                    }
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                _playbackState.value = playbackState
                if (playbackState == Player.STATE_READY) {
                    updateGlobalPositionAndDuration(controller)
                    _metadataEntries.value = extractMetadataEntries(controller)
                }
                // 为本次桌面 widget 改动添加注释：播放队列准备、结束或空闲时刷新小组件文案与按钮状态。
                PlayerWidgetProvider.updateAll(appContext)
                saveProgress()
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                _currentMediaItem.value = mediaItem
                // 字幕文本由 PlayerViewModel 按当前文件懒加载，这里只清掉上一文件的缓存。
                _currentSubtitles.value = emptyList()
                updateGlobalPositionAndDuration(controller)
                _metadataEntries.value = extractMetadataEntries(controller)
                // 为本次桌面 widget 改动添加注释：切换分轨时刷新小组件，确保封面和书名仍对应当前播放书籍。
                PlayerWidgetProvider.updateAll(appContext)
                saveProgress()
            }

            override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
                _playbackSpeed.value = playbackParameters.speed
            }

            // 详尽的中文注释：重写 onMetadata 接口以监听流元数据。
            // 由于解析元数据（尤其是提取内置歌词）涉及流式读取（InputStream.read），属于阻塞 I/O 操作，
            // 因此必须开启协程并分发到 Dispatchers.IO 线程执行，以规避主线程卡顿并消除编译警告。
            override fun onMetadata(metadata: androidx.media3.common.Metadata) {
                scope.launch {
                    val subs = withContext(Dispatchers.IO) {
                        extractLyricsFromMetadata(metadata)
                    }
                    if (subs.isNotEmpty()) {
                        _embeddedSubtitles.emit(subs)
                    }
                }
            }
        })
    }

    /**
     * 详尽的中文注释：核心内置元数据歌词抓取与转化函数。
     * 循环遍历 ExoPlayer 回调出的所有 Metadata 帧条目，寻找到 ID3 规范下的 UnsynchronisedLyricsFrame（无同步歌词帧）。
     * 获得歌词正文文本后，重用 SubtitleParser 的通用 lrc 流式解析接口，一站式转换为 SubtitleLine 结构化集合，
     * 充分消除重复造轮子所带来的隐患，实现底层高聚解耦。
     */
    private fun extractLyricsFromMetadata(metadata: androidx.media3.common.Metadata): List<SubtitleLine> {
        val subs = mutableListOf<SubtitleLine>()
        for (i in 0 until metadata.length()) {
            val entry = metadata.get(i)
            // 详尽的中文注释：为了完全规避 Media3 在不同版本更迭中对 UnsynchronisedLyricsFrame 物理包名（如在 extractor.metadata 或 common.metadata 间迁移）的变动，
            // 物理防范因 compileSdk/依赖库演进而引发的编译符号未解析（Unresolved）崩溃，此处利用反射机制进行完全解耦的动态类型探测与数据读取。
            val entryClassName = entry.javaClass.name
            if (entryClassName.endsWith("UnsynchronisedLyricsFrame")) {
                try {
                    val textField = entry.javaClass.getField("text")
                    val lyricsText = textField.get(entry) as? String
                    if (!lyricsText.isNullOrBlank()) {
                        try {
                            // 详尽的中文注释：使用标准 Java 的 Charset.forName 动态指定 UTF-8 编码，彻底物理规避 Kotlin 特定包下扩展函数在部分编译环境下 unresolved 的致命缺陷
                            val stream = java.io.ByteArrayInputStream(lyricsText.toByteArray(java.nio.charset.Charset.forName("UTF-8")))
                            val parsed = com.viel.aplayer.media.parse.SubtitleParser.parse(stream, "lrc")
                            if (parsed.isNotEmpty()) {
                                subs.addAll(parsed)
                            }
                        } catch (e: Exception) {
                            android.util.Log.e("PlaybackManager", "解析内置元数据歌词失败", e)
                        }
                    }
                } catch (e: Exception) {
                    android.util.Log.e("PlaybackManager", "反射读取内置元数据歌词字段 text 失败", e)
                }
            }
        }
        return subs
    }

    private fun extractMetadataEntries(player: Player): List<androidx.media3.common.Metadata.Entry> {
        val entries = mutableListOf<androidx.media3.common.Metadata.Entry>()
        val tracks = player.currentTracks
        for (group in tracks.groups) {
            val trackGroup = group.mediaTrackGroup
            for (i in 0 until trackGroup.length) {
                val format = trackGroup.getFormat(i)
                val metadata = format.metadata ?: continue
                for (j in 0 until metadata.length()) {
                    entries.add(metadata[j])
                }
            }
        }
        return entries
    }

    private fun updateGlobalPositionAndDuration(player: Player) {
        val plan = currentPlan
        if (plan != null && plan.files.isNotEmpty() && player.currentMediaItem != null) {
            // UI 始终读取真实 MediaController 状态，只在这里转换成全书全局位置。
            val fileIndex = player.currentMediaItemIndex.coerceIn(0, plan.files.lastIndex)
            val positionInFile = player.currentPosition.coerceAtLeast(0L)
            val totalDur = plan.files.sumOf { it.durationMs }
            _currentPosition.value = PositionMapper.fileToGlobalPosition(fileIndex, positionInFile, plan.files)
                .coerceIn(0L, totalDur.coerceAtLeast(0L))
            _duration.value = totalDur
        } else {
            // 没有播放计划或播放计划为空时保持 MediaController 的真实单文件进度。
            _currentPosition.value = player.currentPosition.coerceAtLeast(0L)
            _duration.value = player.duration.coerceAtLeast(0L)
        }
    }

    private fun startProgressPolling() {
        scope.launch {
            var saveCounter = 0
            while (isActive) {
                val controller = mediaController
                if (controller != null && controller.isPlaying) {
                    updateGlobalPositionAndDuration(controller)
                    
                    saveCounter++
                    if (saveCounter >= 20) { // 10s
                        saveCounter = 0
                        saveProgress()
                    }
                }
                val delayTime = if (mediaController?.isPlaying == true) 500L else 2000L
                delay(delayTime)
            }
        }
    }

    /**
     * 将当前进度持久化到数据库。
     */
    fun saveProgress() {
        val controller = mediaController ?: return
        val mediaId = controller.currentMediaItem?.mediaId ?: return
        if (!mediaId.contains(":")) return

        val bookId = mediaId.substringBefore(":")
        // 进度持久化使用真实播放队列索引和文件内位置，通知层的显示包装不参与存储。
        val fileIndex = controller.currentMediaItemIndex.coerceAtLeast(0)
        val positionInFile = controller.currentPosition.coerceAtLeast(0L)

        scope.launch {
            val files = libraryRepository.getFilesForBookSync(bookId)
            if (files.isNotEmpty()) {
                val globalPos = PositionMapper.fileToGlobalPosition(fileIndex, positionInFile, files)
                val bookFileId = files.getOrNull(fileIndex)?.id
                
                libraryRepository.saveProgress(BookProgressEntity(
                    bookId = bookId,
                    globalPositionMs = globalPos,
                    bookFileId = bookFileId,
                    currentFileIndex = fileIndex,
                    positionInFileMs = positionInFile,
                    lastPlayedAt = System.currentTimeMillis()
                ))
            }
        }
    }

    private fun persistProgress(bookId: String, fileIndex: Int, positionInFile: Long) {
        scope.launch {
            val files = libraryRepository.getFilesForBookSync(bookId)
            if (files.isNotEmpty()) {
                val safeFileIndex = fileIndex.coerceIn(0, files.lastIndex)
                val safePositionInFile = positionInFile.coerceAtLeast(0L)
                val globalPos = PositionMapper.fileToGlobalPosition(safeFileIndex, safePositionInFile, files)
                    .coerceIn(0L, files.sumOf { it.durationMs }.coerceAtLeast(0L))
                val bookFileId = files.getOrNull(safeFileIndex)?.id

                // BookProgress is keyed by bookId, so this creates the first row or refreshes the existing row.
                libraryRepository.saveProgress(BookProgressEntity(
                    bookId = bookId,
                    globalPositionMs = globalPos,
                    bookFileId = bookFileId,
                    currentFileIndex = safeFileIndex,
                    positionInFileMs = safePositionInFile,
                    lastPlayedAt = System.currentTimeMillis()
                ))
            }
        }
    }

    /**
     * 为每一次改动添加详尽的中文注释：
     * 为书籍设定播放计划。整个方法执行在 scope 协程环境主线程中。
     * 首先读取持久化设置快照，如果 isLastPlaybackInterrupted（上次播放异常强杀中断）为 true 且开启了回退秒数，
     * 则对当前载入的起始进度进行减去回退时长的进度自愈补偿，最小值不低于 0，
     * 接着通过 copy 构造出自愈后的计划，并同步在数据库重置中断标志为 false 以免后续换歌等发生重复自愈。
     * 最后，百分之百还原原作者关于明文 HTTP 安全校验、Toast 弹出以及 withContext/executeOnMain 等多线程调度设计。
     */
    fun setBookPlaybackPlan(plan: BookPlaybackPlan, playWhenReady: Boolean = false) {
        scope.launch {
            // 为每一次改动添加详尽的中文注释：异步从设置仓库中读取最新的全局持久化快照。
            val settings = settingsRepository.settingsFlow.first()
            var finalPlan = plan

            // 为每一次改动添加详尽的中文注释：核心异常中断进度自愈机制判定。
            // 如果上一次播放由于非正常中断（强杀/闪退/Crash，isLastPlaybackInterrupted = true），且设定了有效的回退时间，
            // 则从全局起始进度中减去回退毫秒数作为进度自愈补偿，最低限制到 0ms 开头，
            // 接着利用 copy 构造出自愈补偿后的 finalPlan，并在 DataStore 中立即复位中断标志为 false 防止切歌等二次触发自愈。
            if (settings.isLastPlaybackInterrupted && settings.autoRewindSeconds > 0) {
                val rewindMs = settings.autoRewindSeconds * 1000L
                val restoredPos = (plan.startGlobalPositionMs - rewindMs).coerceAtLeast(0L)
                finalPlan = plan.copy(startGlobalPositionMs = restoredPos)

                // 为每一次改动添加详尽的中文注释：自愈完成后瞬间重置异常中断标志为 false，保障状态正确复位。
                settingsRepository.updateLastPlaybackInterrupted(false)
                android.util.Log.d("PlaybackManager", "检测到上一次播放被异常中断，已自动回退 $rewindMs ms 进行自愈，最终起始进度: $restoredPos ms")
            } else {
                // 为每一次改动添加详尽的中文注释：若非异常中断恢复，依然主动重置该状态为 false 以免残留脏数据污染。
                settingsRepository.updateLastPlaybackInterrupted(false)
            }

            // 为每一次改动添加详尽的中文注释：完成进度自愈判定后，切回 Dispatchers.Main 线程，百分之百还原原作者的所有多线程渲染及安全检查逻辑。
            withContext(Dispatchers.Main) {
                // 为每一次改动添加详尽的中文注释：在切换或加载新的播放计划前，强行将 ignoreNextAutoRewind 设为 true。
                // 这样能完美拦截由于切换书籍重载媒体资源导致播放器暂停状态改变时，误触发的针对上一本书或新书初始进度的自动回退动作。
                ignoreNextAutoRewind = true

                this@PlaybackManager.currentPlan = finalPlan
                this@PlaybackManager.pendingPlayWhenReady = playWhenReady

                // 性能优化：立即将自愈后最终计划中的初始进度和总时长推送到 UI 流，避免闪烁
                val totalDur = finalPlan.files.sumOf { it.durationMs }
                _currentPosition.value = finalPlan.startGlobalPositionMs
                _duration.value = totalDur
                // 为本次桌面 widget 改动添加注释：播放计划一旦切换，桌面小组件可以立即显示目标书籍，而不必等 MediaController 回调。
                PlayerWidgetProvider.updateAll(appContext)

                // 检查是否包含 HTTP 明文源，若有则异步校验设置后再加载
                val hasHttp = finalPlan.files.any { it.uri.startsWith("http://") }
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
                    executeOnMain { applyPlaybackPlan(finalPlan) }
                }
            }
        }
    }

    private fun applyPlaybackPlan(plan: BookPlaybackPlan) {
        val mediaItems = plan.files.map { file ->
            val metadata = MediaMetadata.Builder()
                .setTitle(plan.title)
                .setArtist(plan.author)
                .setAlbumTitle(plan.title)
                .setArtworkUri(plan.artworkUri)
                .setArtworkData(plan.artworkData, MediaMetadata.PICTURE_TYPE_FRONT_COVER)
                .build()
            val builder = MediaItem.Builder()
                .setMediaId("${plan.bookId}:${file.index}")
                .setUri(file.uri)
                .setMediaMetadata(metadata)
            // 启动时不挂载全书字幕附件，避免为了多文件字幕扫描/解析阻塞 setMediaItems。
            builder.build()
        }
        val (fileIndex, positionInFile) = PositionMapper.globalToFilePosition(plan.startGlobalPositionMs, plan.files)
        
        mediaController?.let { controller ->
            controller.setMediaItems(mediaItems, fileIndex, positionInFile)
            controller.prepare()
            if (pendingPlayWhenReady) {
                // 为本次桌面 widget 改动添加注释：在 prepare 之后消费 autoplay 请求，避免先 play 后 setMediaItems 的异步时序丢失。
                pendingPlayWhenReady = false
                controller.play()
            }
            // 当前文件字幕由 ViewModel 监听 currentMediaItem 后按需解析。
            _currentSubtitles.value = emptyList()
            // Loading a book should create/update BookProgress immediately, even before playback events fire.
            persistProgress(plan.bookId, fileIndex, positionInFile)
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
     * 为每一次改动添加详尽的中文注释：
     * 执行暂停自动回退功能的核心逻辑。
     * 从持久化设置中异步读取 autoRewindSeconds 属性。如果其值大于 0，
     * 则计算目标毫秒位置（当前单文件位置减去回退时长），并使用 coerceAtLeast(0) 限制不超前当前文件的开头。
     * 最后，调用底层 MediaController 进行寻址定位，在更新全局位置流后立即持久化同步保存到数据库中，
     * 消除因突然进程中断或卸载引起的位置丢失风险。
     */
    private fun applyAutoRewind() {
        val controller = mediaController ?: return
        val plan = currentPlan
        scope.launch {
            try {
                // 为每一次改动添加详尽的中文注释：使用 first() 挂起并获取 DataStore 中的最新设置快照，确保数据一致性。
                val settings = settingsRepository.settingsFlow.first()
                val rewindSeconds = settings.autoRewindSeconds
                if (rewindSeconds > 0) {
                    val rewindMs = rewindSeconds * 1000L
                    
                    if (plan != null && plan.files.isNotEmpty()) {
                        // 为每一次改动添加详尽的中文注释：如果当前存在多文件播放计划，在全局大维度上计算当前进度，并执行精准的跨文件边界回退，
                        // 彻底解决单文件回退时被强制截断在 0 秒而无法回退到上一音轨末尾的体验痛点。
                        val fileIndex = controller.currentMediaItemIndex.coerceIn(0, plan.files.lastIndex)
                        val positionInFile = controller.currentPosition.coerceAtLeast(0L)
                        val currentGlobalPos = PositionMapper.fileToGlobalPosition(fileIndex, positionInFile, plan.files)
                        val targetGlobalPos = (currentGlobalPos - rewindMs).coerceAtLeast(0L)
                        
                        val (targetFileIndex, targetPosInFile) = PositionMapper.globalToFilePosition(targetGlobalPos, plan.files)
                        // 为每一次改动添加详尽的中文注释：跨文件定位可能导致媒体源发生变更，因此必须使用 index + file-position 执行 seek
                        controller.seekTo(targetFileIndex, targetPosInFile)
                    } else {
                        // 为每一次改动添加详尽的中文注释：兜底单文件播放场景下的普通回退寻址。
                        val currentPos = controller.currentPosition
                        val targetPos = (currentPos - rewindMs).coerceAtLeast(0L)
                        controller.seekTo(targetPos)
                    }
                    
                    updateGlobalPositionAndDuration(controller)
                    // 为每一次改动添加详尽的中文注释：回退完成后立即向本地数据库落盘保存进度，防丢失防倒退
                    saveProgress()
                }
            } catch (e: Exception) {
                android.util.Log.e("PlaybackManager", "执行暂停自动回退失败", e)
            }
        }
    }

    /**
     * 为每一次改动添加详尽的中文注释：获取或设置当前播放器的内部音量比例（0.0f - 1.0f）。
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
            val files = libraryRepository.getFilesForBookSync(bookId)
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
                persistProgress(bookId, fileIndex, positionInFile)
            }
        }
    }

    fun setPlaybackSpeed(speed: Float) {
        executeOnMain {
            mediaController?.setPlaybackSpeed(speed)
        }
    }

    fun release() {
        scope.cancel()
        controllerFuture?.let {
            MediaController.releaseFuture(it)
            controllerFuture = null
        }
        mediaController = null
        INSTANCE = null
    }

    /**
     * 为每一次改动添加详尽的中文注释：异步获取已连接的 MediaController 实例。
     * 如果当前 mediaController 已建立连接则立即返回；
     * 如果 controllerFuture 处于连接中，则通过 suspendCancellableCoroutine 挂起并等待连接完成，
     * 以便在 Activity 已销毁或后台重建的异步时序下依然能获取到真实的 MediaController，
     * 解决删除书库时因连接尚未就绪导致 getCurrentBookId() 漏判后台播放的缺陷。
     */
    /**
     * 为每一次改动添加详详尽的中文注释：异步获取已连接的 MediaController 实例。
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
     * 为每一次改动添加详尽的中文注释：异步获取当前正在播放或计划播放的书籍 ID。
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
     * 为每一次改动添加详尽的中文注释：异步停止并清空受影响的书籍播放队列，重置播放状态 Flow。
     * 首先挂起等待 MediaController 异步连接完毕，以防在未就绪时调用导致底层 ExoPlayer 无法接收到 pause 和 stop 指令，
     * 确保即使在后台被动调用的时序下也能彻底切断底层音频播放流。
     */
    suspend fun stopPlayback() {
        val controller = getController()
        withContext(Dispatchers.Main) {
            // 为每一次改动添加详尽的中文注释：在主动停止播放器前，强行将 ignoreNextAutoRewind 设为 true。
            // 这样可以拦截由于主动暂停/清除播放资源导致物理播放状态回调触发时，无意义且有隐患的自动回退与保存进度操作。
            ignoreNextAutoRewind = true
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
