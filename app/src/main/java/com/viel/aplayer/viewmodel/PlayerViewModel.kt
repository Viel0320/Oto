package com.viel.aplayer.viewmodel

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.extractor.metadata.id3.ChapterFrame
import androidx.media3.extractor.metadata.id3.TextInformationFrame
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import androidx.core.content.ContextCompat
import com.viel.aplayer.data.BookmarkEntity
import com.viel.aplayer.data.ChapterEntity
import com.viel.aplayer.data.LibraryRepository
import com.viel.aplayer.service.PlaybackService
import com.viel.aplayer.ui.components.SubtitleLine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PlayerViewModel : ViewModel() {
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var player: Player? = null
    private var libraryRepository: LibraryRepository? = null
    private var currentMediaUri: String? = null

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _currentTitle = MutableStateFlow("")
    val currentTitle: StateFlow<String> = _currentTitle.asStateFlow()

    private val _currentAuthor = MutableStateFlow("")
    val currentAuthor: StateFlow<String> = _currentAuthor.asStateFlow()

    private val _currentCoverPath = MutableStateFlow<String?>(null)
    val currentCoverPath: StateFlow<String?> = _currentCoverPath.asStateFlow()

    private val _currentPosition = MutableStateFlow(0L)
    val currentPosition: StateFlow<Long> = _currentPosition.asStateFlow()

    private val _duration = MutableStateFlow(0L)
    val duration: StateFlow<Long> = _duration.asStateFlow()

    private val _playbackSpeed = MutableStateFlow(1.0f)
    val playbackSpeed: StateFlow<Float> = _playbackSpeed.asStateFlow()

    private val _playbackState = MutableStateFlow(Player.STATE_IDLE)
    val playbackState: StateFlow<Int> = _playbackState.asStateFlow()

    private val _sleepTimerMillis = MutableStateFlow(0L)

    private val _selectedSleepTimer = MutableStateFlow(0) // 0 for off, otherwise minutes
    val selectedSleepTimer: StateFlow<Int> = _selectedSleepTimer.asStateFlow()

    private val _currentChapters = MutableStateFlow<List<ChapterEntity>>(emptyList())
    val currentChapters: StateFlow<List<ChapterEntity>> = _currentChapters.asStateFlow()

    private val _currentSubtitles = MutableStateFlow<List<SubtitleLine>>(emptyList())
    val currentSubtitles: StateFlow<List<SubtitleLine>> = _currentSubtitles.asStateFlow()

    private val _currentBookmarks = MutableStateFlow<List<BookmarkEntity>>(emptyList())
    val currentBookmarks: StateFlow<List<BookmarkEntity>> = _currentBookmarks.asStateFlow()

    private val _showUndoSeek = MutableStateFlow(false)
    val showUndoSeek: StateFlow<Boolean> = _showUndoSeek.asStateFlow()
    private var lastSeekPosition = 0L
    private var undoJob: kotlinx.coroutines.Job? = null

    private var sleepTimerJob: kotlinx.coroutines.Job? = null
    private var chaptersJob: kotlinx.coroutines.Job? = null
    private var bookmarksJob: kotlinx.coroutines.Job? = null

    fun initialize(context: Context) {
        if (controllerFuture != null) return
        val appContext = context.applicationContext
        libraryRepository = LibraryRepository.getInstance(appContext)

        val sessionToken = SessionToken(appContext, ComponentName(appContext, PlaybackService::class.java))
        controllerFuture = MediaController.Builder(appContext, sessionToken).buildAsync()
        
        controllerFuture?.addListener({
            val mediaController = try { controllerFuture?.get() } catch (_: Exception) { null }
            player = mediaController
            mediaController?.addListener(object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    _isPlaying.value = isPlaying
                    if (!isPlaying) {
                        saveProgress() // 暂停时立即保存进度
                    }
                }
                override fun onPlaybackParametersChanged(playbackParameters: androidx.media3.common.PlaybackParameters) {
                    _playbackSpeed.value = playbackParameters.speed
                }
                override fun onPlaybackStateChanged(playbackState: Int) {
                    _playbackState.value = playbackState
                    if (playbackState == Player.STATE_READY) {
                        _duration.value = mediaController.duration.coerceAtLeast(0L)
                        
                        // Try to extract chapters if current list is empty
                        if (_currentChapters.value.isEmpty()) {
                            extractChaptersFromPlayer(mediaController)
                        }

                        // ExoPlayer has fully parsed metadata now — update everything
                        val meta = mediaController.mediaMetadata
                        val title = meta.title?.toString()
                        val author = meta.artist?.toString()
                            ?: meta.albumArtist?.toString()
                            ?: meta.writer?.toString()
                        val narrator = meta.composer?.toString()
                        val description = meta.description?.toString()
                        
                        if (!title.isNullOrBlank() && !title.contains("/")) {
                            _currentTitle.value = title
                        }
                        if (!author.isNullOrBlank()) _currentAuthor.value = author
                        
                        // Write back to library so the list shows correct data
                        currentMediaUri?.let { uri ->
                            // Only update title if it's not a mime type
                            val finalTitle = if (!title.isNullOrBlank() && !title.contains("/")) title else null
                            libraryRepository?.updateMetadata(uri, finalTitle, author, narrator, description, mediaController.duration.coerceAtLeast(0L))
                        }
                    }
                }
                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    if (mediaItem == null) {
                        _currentTitle.value = ""
                        _currentAuthor.value = ""
                        _currentCoverPath.value = null
                        _currentChapters.value = emptyList()
                        currentMediaUri = null
                        return
                    }
                    val transTitle = mediaItem.mediaMetadata.title?.toString()
                    val transAuthor = mediaItem.mediaMetadata.artist?.toString()
                    if (!transTitle.isNullOrBlank()) _currentTitle.value = transTitle
                    // Don't reset author here — STATE_READY will provide the real one
                    if (!transAuthor.isNullOrBlank() && (transAuthor != "Unknown Author")) {
                        _currentAuthor.value = transAuthor
                    }
                    currentMediaUri = mediaItem.mediaId
                    _duration.value = mediaController.duration.coerceAtLeast(0L)
                    
                    // Load chapters, subtitles & bookmarks
                    loadChapters(mediaItem.mediaId)
                    loadSubtitles(mediaItem.mediaId)
                    loadBookmarks(mediaItem.mediaId)
                }
            })
            // Start progress polling & auto-save
            viewModelScope.launch {
                var saveCounter = 0
                while (true) {
                    val isActuallyPlaying = mediaController?.isPlaying == true
                    val playbackState = mediaController?.playbackState ?: Player.STATE_IDLE
                    
                    if (isActuallyPlaying && playbackState != Player.STATE_ENDED) {
                        val pos = mediaController.currentPosition.coerceAtLeast(0L)
                        if (pos > 0 || playbackState == Player.STATE_READY) {
                            _currentPosition.value = pos
                        }
                        
                        saveCounter++
                        // Save progress every 2 seconds (4 * 500ms)
                        if (saveCounter >= 4) {
                            saveCounter = 0
                            saveProgress()
                        }
                    }
                    delay(500)
                }
            }

            // Autoload most recent media if nothing is currently playing/loaded
            if (mediaController?.mediaItemCount == 0) {
                viewModelScope.launch {
                    val recent = libraryRepository?.getMostRecentAudiobook()
                    android.util.Log.d("PlayerViewModel", "Autoload search result: ${recent?.title}")
                    if (recent != null) {
                        loadMedia(
                            uri = Uri.parse(recent.uri),
                            title = recent.title,
                            author = recent.author,
                            startPositionMs = recent.lastPosition,
                            playWhenReady = false
                        )
                    }
                }
            }
        }, ContextCompat.getMainExecutor(context))
    }

    fun loadMedia(uri: Uri, title: String, author: String, startPositionMs: Long = 0L, playWhenReady: Boolean = true) {
        // Save progress of previous media before switching
        saveProgress()
        
        currentMediaUri = uri.toString()
        _currentTitle.value = title
        _currentAuthor.value = author
        _currentCoverPath.value = null // reset, will be loaded after DB insert

        // Load chapters, subtitles & bookmarks
        loadChapters(uri.toString())
        loadSubtitles(uri.toString())
        loadBookmarks(uri.toString())

        // Only set metadata we actually know
        val metadataBuilder = MediaMetadata.Builder().setTitle(title)
        if (author != "Unknown Author") {
            metadataBuilder.setArtist(author)
        }
            
        val mediaItem = MediaItem.Builder()
            .setMediaId(uri.toString())
            .setUri(uri)
            .setMediaMetadata(metadataBuilder.build())
            .build()
            
        player?.setMediaItem(mediaItem, startPositionMs)
        _currentPosition.value = startPositionMs
        player?.prepare()
        if (playWhenReady) {
            player?.play()
        }
        
        // Poll for cover path
        viewModelScope.launch {
            repeat(5) {
                val path = libraryRepository?.getCoverPath(uri.toString())
                if (path != null) {
                    _currentCoverPath.value = path
                    return@launch
                }
                delay(1000)
            }
        }
    }
    
    private fun loadChapters(uri: String) {
        chaptersJob?.cancel()
        chaptersJob = viewModelScope.launch {
            libraryRepository?.getChapters(uri)?.collect {
                _currentChapters.value = it
            }
        }
    }

    private fun loadSubtitles(uri: String) {
        viewModelScope.launch {
            android.util.Log.d("PlayerViewModel", "Loading subtitles for $uri")
            val subs = libraryRepository?.loadSubtitles(uri) ?: emptyList()
            android.util.Log.d("PlayerViewModel", "Loaded ${subs.size} subtitles")
            _currentSubtitles.value = subs
        }
    }

    private fun loadBookmarks(uri: String) {
        bookmarksJob?.cancel()
        bookmarksJob = viewModelScope.launch {
            libraryRepository?.getBookmarks(uri)?.collect {
                _currentBookmarks.value = it
            }
        }
    }

    fun addBookmark(title: String) {
        val uri = currentMediaUri ?: return
        val pos = _currentPosition.value
        viewModelScope.launch {
            libraryRepository?.addBookmark(uri, pos, title)
        }
    }

    fun deleteBookmark(bookmark: BookmarkEntity) {
        viewModelScope.launch {
            libraryRepository?.deleteBookmark(bookmark)
        }
    }

    private fun extractChaptersFromPlayer(player: Player) {
        val uri = currentMediaUri ?: return

        // 切换到 Default 线程处理反射和循环计算，避免阻塞主线程
        viewModelScope.launch(Dispatchers.Default) {
            val chapters = mutableListOf<ChapterEntity>()
            val tracks = player.currentTracks
            for (group in tracks.groups) {
                val trackGroup = group.mediaTrackGroup
                for (i in 0 until trackGroup.length) {
                    val format = trackGroup.getFormat(i)
                    val metadata = format.metadata ?: continue
                    for (j in 0 until metadata.length()) {
                        val entry = metadata[j]
                        if (entry is ChapterFrame) {
                            var title: String? = null
                            try {
                                val field = entry.javaClass.getDeclaredField("subFrames")
                                field.isAccessible = true
                                val subFrames = field.get(entry) as? Array<*>
                                subFrames?.forEach { subFrame ->
                                    if (subFrame is TextInformationFrame && (subFrame.id == "TIT2" || subFrame.id == "TIT1")) {
                                        title = subFrame.values.firstOrNull()
                                    }
                                }
                            } catch (_: Exception) {}

                            if (title.isNullOrBlank() && !entry.chapterId.matches(Regex("ch\\d+"))) {
                                title = entry.chapterId
                            }

                            chapters.add(
                                ChapterEntity(
                                    bookUri = uri,
                                    title = title ?: "Chapter ${chapters.size + 1}",
                                    startPosition = entry.startTimeMs.toLong(),
                                    endPosition = entry.endTimeMs.toLong()
                                )
                            )
                        } else if (entry.javaClass.simpleName.contains("Chapter", ignoreCase = true)) {
                            try {
                                val clazz = entry.javaClass
                                val title = try { clazz.getDeclaredField("title").apply { isAccessible = true }.get(entry) as? String } catch(_: Exception) { null }
                                    ?: try { clazz.getDeclaredField("text").apply { isAccessible = true }.get(entry) as? String } catch(_: Exception) { null }
                                val startTimeMs = try { clazz.getDeclaredField("startTimeMs").apply { isAccessible = true }.get(entry) as? Int } catch(_: Exception) { null }
                                val endTimeMs = try { clazz.getDeclaredField("endTimeMs").apply { isAccessible = true }.get(entry) as? Int } catch(_: Exception) { null }

                                if (startTimeMs != null && endTimeMs != null) {
                                    chapters.add(
                                        ChapterEntity(
                                            bookUri = uri,
                                            title = title ?: "Chapter ${chapters.size + 1}",
                                            startPosition = startTimeMs.toLong(),
                                            endPosition = endTimeMs.toLong()
                                        )
                                    )
                                }
                            } catch (_: Exception) {}
                        }
                    }
                }
            }

            // Distinct and sort
            val finalChapters = chapters.asSequence().distinctBy { it.startPosition }.sortedBy { it.startPosition }.toList()

            if (finalChapters.isNotEmpty()) {
                _currentChapters.value = finalChapters
                // Save to DB for future use
                libraryRepository?.saveChapters(uri, finalChapters)
            }
        }
    }

    private fun saveProgress() {
        val uri = currentMediaUri ?: return
        val position = _currentPosition.value
        libraryRepository?.updateProgress(uri, position)
    }

    fun play() {
        player?.play()
    }

    fun pause() {
        player?.pause()
    }

    fun togglePlayPause() {
        if (player?.isPlaying == true) {
            pause()
        } else {
            play()
        }
    }

    fun seekTo(positionMs: Long, allowUndo: Boolean = false) {
        if (allowUndo) {
            lastSeekPosition = _currentPosition.value
            _showUndoSeek.value = true
            undoJob?.cancel()
            undoJob = viewModelScope.launch {
                delay(10000)
                _showUndoSeek.value = false
            }
        } else {
            _showUndoSeek.value = false
            undoJob?.cancel()
        }
        player?.seekTo(positionMs)
        _currentPosition.value = positionMs
    }

    fun undoSeek() {
        if (_showUndoSeek.value) {
            seekTo(lastSeekPosition, allowUndo = false)
            _showUndoSeek.value = false
            undoJob?.cancel()
        }
    }

    fun skipForward() {
        // TODO: Make skip duration customizable by user
        val newPos = (_currentPosition.value + 30000).coerceAtMost(_duration.value)
        seekTo(newPos)
    }

    fun skipBackward() {
        // TODO: Make skip duration customizable by user
        val newPos = (_currentPosition.value - 10000).coerceAtLeast(0L)
        seekTo(newPos)
    }

    fun setPlaybackSpeed(speed: Float) {
        player?.setPlaybackSpeed(speed)
        _playbackSpeed.value = speed
    }

    fun setSleepTimer(minutes: Int) {
        sleepTimerJob?.cancel()
        _selectedSleepTimer.value = minutes
        
        if (minutes == 0) {
            _sleepTimerMillis.value = 0L
            return
        }
        
        // Special handling for test option: if minutes is -1, set 5 seconds
        val millis = if (minutes < 0) 5000L else minutes * 60 * 1000L
        _sleepTimerMillis.value = millis
        
        sleepTimerJob = viewModelScope.launch {
            while (_sleepTimerMillis.value > 0) {
                delay(1000)
                // Only count down if actually playing
                if (_isPlaying.value) {
                    _sleepTimerMillis.value = (_sleepTimerMillis.value - 1000).coerceAtLeast(0L)
                }
            }
            player?.pause()
            _selectedSleepTimer.value = 0
            _sleepTimerMillis.value = 0
        }
    }

    override fun onCleared() {
        super.onCleared()
        saveProgress()
        controllerFuture?.let {
            MediaController.releaseFuture(it)
            controllerFuture = null
        }
        player = null
    }
}
