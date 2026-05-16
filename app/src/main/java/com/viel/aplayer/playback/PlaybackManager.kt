package com.viel.aplayer.playback

import android.content.ComponentName
import android.content.Context
import androidx.annotation.OptIn
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.viel.aplayer.data.LibraryRepository
import com.viel.aplayer.service.PlaybackService
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

    private val _currentPosition = MutableStateFlow(0L)
    val currentPosition = _currentPosition.asStateFlow()

    private val _duration = MutableStateFlow(0L)
    val duration = _duration.asStateFlow()

    private val _playbackSpeed = MutableStateFlow(1.0f)
    val playbackSpeed = _playbackSpeed.asStateFlow()

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
        _duration.value = controller.duration.coerceAtLeast(0L)
        _playbackSpeed.value = controller.playbackParameters.speed

        controller.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _isPlaying.value = isPlaying
                // 自动保存：播放状态改变时（例如暂停）保存进度
                saveProgress()
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                _playbackState.value = playbackState
                if (playbackState == Player.STATE_READY) {
                    _duration.value = controller.duration.coerceAtLeast(0L)
                    _metadataEntries.value = extractMetadataEntries(controller)
                }
                // 自动保存：播放结束或状态变化时保存
                saveProgress()
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                // 自动保存：在切换媒体项之前，保存旧媒体项的进度
                _currentMediaItem.value?.mediaId?.let { oldUri ->
                    val pos = controller.currentPosition.coerceAtLeast(0L)
                    libraryRepository.updateProgress(oldUri, pos)
                }

                _currentMediaItem.value = mediaItem
                _duration.value = controller.duration.coerceAtLeast(0L)
                _metadataEntries.value = extractMetadataEntries(controller)
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

    private fun startProgressPolling() {
        scope.launch {
            var saveCounter = 0
            while (isActive) {
                val controller = mediaController
                if (controller != null && controller.isPlaying) {
                    val pos = controller.currentPosition.coerceAtLeast(0L)
                    _currentPosition.value = pos
                    
                    // 自动保存：播放期间每 10 秒自动保存一次，防止异常闪退丢失进度
                    saveCounter++
                    if (saveCounter >= 20) { // 500ms * 20 = 10s
                        saveCounter = 0
                        saveProgress()
                    }
                }
                // 当播放时高频更新（如 500ms），不播放时降低频率（如 2s）以节省资源
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
        val uri = controller.currentMediaItem?.mediaId ?: return
        val pos = controller.currentPosition.coerceAtLeast(0L)
        libraryRepository.updateProgress(uri, pos)
    }

    // Commands - Thread safe execution
    fun play() {
        executeOnMain { mediaController?.play() }
    }

    fun pause() {
        executeOnMain { mediaController?.pause() }
    }

    fun seekTo(positionMs: Long) {
        executeOnMain {
            mediaController?.seekTo(positionMs)
            mediaController?.play()
            _currentPosition.value = positionMs
        }
    }

    fun setMediaItem(mediaItem: MediaItem, startPositionMs: Long = 0L) {
        executeOnMain {
            mediaController?.setMediaItem(mediaItem, startPositionMs)
            mediaController?.prepare()
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
