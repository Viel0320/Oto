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
import kotlin.coroutines.resume
import com.viel.aplayer.data.LibraryRepository
import com.viel.aplayer.data.AppSettingsRepository
import com.viel.aplayer.data.entity.BookProgressEntity
import com.viel.aplayer.media.service.PlaybackService
import com.viel.aplayer.ui.player.components.SubtitleLine

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
    private val _embeddedSubtitles = kotlinx.coroutines.flow.MutableSharedFlow<List<SubtitleLine>>(extraBufferCapacity = 1)
    val embeddedSubtitles = _embeddedSubtitles.asSharedFlow()

    private val _currentPosition = MutableStateFlow(0L)
    val currentPosition = _currentPosition.asStateFlow()

    private val _duration = MutableStateFlow(0L)
    val duration = _duration.asStateFlow()

    private val _playbackSpeed = MutableStateFlow(1.0f)
    val playbackSpeed = _playbackSpeed.asStateFlow()

    private var currentPlan: BookPlaybackPlan? = null

    /** 当前播放计划的 bookId，非挂起，可从任意线程安全读取。 */
    val currentPlayingBookId: String?
        get() = currentPlan?.bookId

    init {
        initializeController()
        startProgressPolling()
    }

    private fun initializeController() {
        val sessionToken = SessionToken(appContext, ComponentName(appContext, PlaybackService::class.java))
        controllerFuture = MediaController.Builder(appContext, sessionToken).buildAsync()
        
        controllerFuture?.addListener({
            try {
                mediaController = controllerFuture?.get()
                mediaController?.let { controller ->
                    setupController(controller)
                    // Cold-start restore may set the playback plan before MediaController connects; apply it once ready.
                    currentPlan?.let { setBookPlaybackPlan(it) }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }, ContextCompat.getMainExecutor(appContext))
    }

    private fun setupController(controller: MediaController) {
        _isPlaying.value = controller.isPlaying
        _playbackState.value = controller.playbackState
        _currentMediaItem.value = controller.currentMediaItem
        _metadataEntries.value = extractMetadataEntries(controller)
        updateGlobalPositionAndDuration(controller)
        _playbackSpeed.value = controller.playbackParameters.speed

        controller.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _isPlaying.value = isPlaying
                saveProgress()
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                _playbackState.value = playbackState
                if (playbackState == Player.STATE_READY) {
                    updateGlobalPositionAndDuration(controller)
                    _metadataEntries.value = extractMetadataEntries(controller)
                }
                saveProgress()
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                _currentMediaItem.value = mediaItem
                // 字幕文本由 PlayerViewModel 按当前文件懒加载，这里只清掉上一文件的缓存。
                _currentSubtitles.value = emptyList()
                updateGlobalPositionAndDuration(controller)
                _metadataEntries.value = extractMetadataEntries(controller)
                saveProgress()
            }

            override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
                _playbackSpeed.value = playbackParameters.speed
            }

            // 详尽的中文注释：重写 onMetadata 接口以监听并捕获音频解调器解码出来的流元数据（包含内置 ID3 歌词帧）。
            override fun onMetadata(metadata: androidx.media3.common.Metadata) {
                val subs = extractLyricsFromMetadata(metadata)
                if (subs.isNotEmpty()) {
                    _embeddedSubtitles.tryEmit(subs)
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

    fun setBookPlaybackPlan(plan: BookPlaybackPlan) {
        this.currentPlan = plan
        
        // 性能优化：立即将计划中的初始进度和总时长推送到 UI 流，避免闪烁
        val totalDur = plan.files.sumOf { it.durationMs }
        _currentPosition.value = plan.startGlobalPositionMs
        _duration.value = totalDur

        // 检查是否包含 HTTP 明文源，若有则异步校验设置后再加载
        val hasHttp = plan.files.any { it.uri.startsWith("http://") }
        if (hasHttp) {
            scope.launch {
                val isAllowed = settingsRepository.settingsFlow.first().isCleartextTrafficAllowed
                if (!isAllowed) {
                    withContext(Dispatchers.Main) {
                        android.widget.Toast.makeText(appContext, "安全拦截：明文 HTTP 播放未授权。请在设置中允许。", android.widget.Toast.LENGTH_LONG).show()
                    }
                    return@launch
                }
                withContext(Dispatchers.Main) { applyPlaybackPlan(plan) }
            }
        } else {
            executeOnMain { applyPlaybackPlan(plan) }
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

    fun seekTo(globalPositionMs: Long) {
        val controller = mediaController ?: return
        val mediaId = controller.currentMediaItem?.mediaId ?: return
        if (!mediaId.contains(":")) return
        val bookId = mediaId.substringBefore(":")

        scope.launch {
            val files = libraryRepository.getFilesForBookSync(bookId)
            if (files.isNotEmpty()) {
                val totalDuration = files.sumOf { it.durationMs }
                val targetGlobal = globalPositionMs.coerceIn(0L, totalDuration.coerceAtLeast(0L))
                val (fileIndex, positionInFile) = PositionMapper.globalToFilePosition(targetGlobal, files)
                executeOnMain {
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
    suspend fun getController(): MediaController? {
        val controller = mediaController
        if (controller != null) return controller

        val future = controllerFuture ?: return null
        if (future.isDone) {
            return try {
                future.get().also { mediaController = it }
            } catch (e: Exception) {
                null
            }
        }

        return suspendCancellableCoroutine { continuation ->
            future.addListener({
                try {
                    val conn = future.get()
                    mediaController = conn
                    if (continuation.isActive) {
                        continuation.resume(conn)
                    }
                } catch (e: Exception) {
                    if (continuation.isActive) {
                        continuation.resume(null)
                    }
                }
            }, ContextCompat.getMainExecutor(appContext))

            continuation.invokeOnCancellation {
                // 协程取消无需物理取消 future，由 PlaybackManager 单例共享生命周期
            }
        }
    }

    /**
     * 为每一次改动添加详尽的中文注释：异步获取当前正在播放或计划播放的书籍 ID。
     * 优先等待 MediaController 异步连接就绪后再行检索 currentMediaItem，
     * 确保在 UI 退出且 PlaybackManager 被 release 重新获取单例的极端生命周期下，
     * 依然能准确捕捉后台真实的播放书籍 ID。
     */
    suspend fun getCurrentBookId(): String? {
        val controller = getController()
        val mediaId = controller?.currentMediaItem?.mediaId ?: currentPlan?.bookId
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