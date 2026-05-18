package com.viel.aplayer.playback

import android.content.ComponentName
import android.content.Context
import androidx.annotation.OptIn
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.viel.aplayer.data.BookProgressEntity
import com.viel.aplayer.data.LibraryRepository
import com.viel.aplayer.service.PlaybackService
import com.viel.aplayer.ui.components.SubtitleLine
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

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
                // 字幕行跟随当前播放文件切换，来源与 MediaItem 挂载的字幕附件一致。
                _currentSubtitles.value = subtitleLinesFor(mediaItem)
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
        if (plan != null && player.currentMediaItem != null) {
            val fileIndex = player.currentMediaItemIndex
            val positionInFile = player.currentPosition.coerceAtLeast(0L)
            
            val globalPos = PositionMapper.fileToGlobalPosition(fileIndex, positionInFile, plan.files)
            val totalDur = plan.files.sumOf { it.durationMs }
            
            _currentPosition.value = globalPos
            _duration.value = totalDur
        } else {
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
        val fileIndex = mediaId.substringAfter(":").toIntOrNull() ?: 0
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
                // 同目录同名字幕在播放计划里已解析；这里正式挂到 Media3 的 MediaItem。
                plan.subtitlesByFileId[file.id]?.toSubtitleConfiguration(file.id)?.let { subtitle ->
                    builder.setSubtitleConfigurations(listOf(subtitle))
                }
                builder.build()
            }
            val (fileIndex, positionInFile) = PositionMapper.globalToFilePosition(plan.startGlobalPositionMs, plan.files)
            
            mediaController?.let { controller ->
                controller.setMediaItems(mediaItems, fileIndex, positionInFile)
                controller.prepare()
                _currentSubtitles.value = subtitleLinesFor(mediaItems.getOrNull(fileIndex))
            }
        }
    }

    private fun PlaybackSubtitle.toSubtitleConfiguration(fileId: String): MediaItem.SubtitleConfiguration =
        MediaItem.SubtitleConfiguration.Builder(uri)
            .setMimeType(mimeType)
            .setLabel(label)
            .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
            .setId("$fileId:subtitle")
            .build()

    private fun subtitleLinesFor(mediaItem: MediaItem?): List<SubtitleLine> {
        val plan = currentPlan ?: return emptyList()
        val mediaId = mediaItem?.mediaId ?: return emptyList()
        val fileIndex = mediaId.substringAfter(":", missingDelimiterValue = "").toIntOrNull() ?: return emptyList()
        val file = plan.files.firstOrNull { it.index == fileIndex } ?: return emptyList()
        return plan.subtitlesByFileId[file.id]?.lines.orEmpty()
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
                val (fileIndex, positionInFile) = PositionMapper.globalToFilePosition(globalPositionMs, files)
                executeOnMain {
                    controller.seekTo(fileIndex, positionInFile)
                    controller.play()
                    _currentPosition.value = globalPositionMs
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
