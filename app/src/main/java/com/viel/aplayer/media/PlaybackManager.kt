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
import com.viel.aplayer.data.entity.BookProgressEntity
import com.viel.aplayer.media.service.PlaybackService
import com.viel.aplayer.ui.player.components.SubtitleLine

@OptIn(UnstableApi::class)
class PlaybackManager private constructor(context: Context) {

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val libraryRepository = LibraryRepository.getInstance(appContext)

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

    private val _currentPosition = MutableStateFlow(0L)
    val currentPosition = _currentPosition.asStateFlow()

    private val _duration = MutableStateFlow(0L)
    val duration = _duration.asStateFlow()

    private val _playbackSpeed = MutableStateFlow(1.0f)
    val playbackSpeed = _playbackSpeed.asStateFlow()

    private var currentPlan: BookPlaybackPlan? = null

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
        })
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

        executeOnMain {
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