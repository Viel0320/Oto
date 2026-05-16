package com.viel.aplayer.ui.viewmodel

import android.content.ComponentName
import android.content.Context
import android.media.AudioManager
import android.net.Uri
import androidx.core.net.toUri
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
import com.viel.aplayer.ui.state.PlayerUiState
import com.viel.aplayer.ui.state.RelatedSection
import com.viel.aplayer.ui.utils.DEFAULT_COVER_BACKGROUND_ARGB
import com.viel.aplayer.ui.utils.extractCoverDominantColorArgb
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PlayerViewModel : ViewModel() {
    companion object {
        private val PLAYBACK_SPEEDS = listOf(0.25f, 0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f)
        private val SLEEP_TIMER_OPTIONS = listOf(0, -1, -2, 15, 30, 60)
    }

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var player: Player? = null
    private var libraryRepository: LibraryRepository? = null
    private var audioManager: AudioManager? = null
    private var currentMediaUri: String? = null

    private val _uiState = MutableStateFlow(PlayerUiState())
    val uiState: StateFlow<PlayerUiState> = _uiState.asStateFlow()

    private val _playbackState = MutableStateFlow(Player.STATE_IDLE)

    private val _sleepTimerMillis = MutableStateFlow(0L)
    private var lastSeekPosition = 0L
    private var undoJob: kotlinx.coroutines.Job? = null

    private var sleepTimerJob: kotlinx.coroutines.Job? = null
    private var chaptersJob: kotlinx.coroutines.Job? = null
    private var bookmarksJob: kotlinx.coroutines.Job? = null
    private var relatedBooksJob: kotlinx.coroutines.Job? = null

    fun initialize(context: Context) {
        if (controllerFuture != null) return
        val appContext = context.applicationContext
        libraryRepository = LibraryRepository.getInstance(appContext)
        audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager

        val sessionToken = SessionToken(appContext, ComponentName(appContext, PlaybackService::class.java))
        controllerFuture = MediaController.Builder(appContext, sessionToken).buildAsync()
        
        controllerFuture?.addListener({
            val mediaController = try { controllerFuture?.get() } catch (_: Exception) { null }
            player = mediaController
            mediaController?.addListener(object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    _uiState.update { it.copy(isPlaying = isPlaying) }
                    // Ensure saveProgress (which touches MediaController) is called on Main thread
                    viewModelScope.launch {
                        saveProgress()
                    }
                }
                override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                    _uiState.update { it.copy(playWhenReady = playWhenReady) }
                }
                override fun onPlaybackParametersChanged(playbackParameters: androidx.media3.common.PlaybackParameters) {
                    _uiState.update { it.copy(playbackSpeed = playbackParameters.speed) }
                }
                override fun onPlaybackStateChanged(playbackState: Int) {
                    _playbackState.value = playbackState
                    if (playbackState == Player.STATE_READY) {
                        val duration = mediaController.duration.coerceAtLeast(0L)
                        _uiState.update { it.copy(duration = duration) }
                        
                        // Try to extract chapters if current list is empty
                        if (_uiState.value.currentChapters.isEmpty()) {
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
                        
                        _uiState.update { state ->
                            state.copy(
                                currentTitle = if (!title.isNullOrBlank() && !title.contains("/")) title else state.currentTitle,
                                currentAuthor = if (!author.isNullOrBlank()) author else state.currentAuthor,
                                currentNarrator = if (!narrator.isNullOrBlank()) narrator else state.currentNarrator
                            )
                        }

                        // Write back to library so the list shows correct data
                        currentMediaUri?.let { uri ->
                            // Only update title if it's not a mime type
                            val finalTitle = if (!title.isNullOrBlank() && !title.contains("/")) title else null
                            libraryRepository?.updateMetadata(uri, finalTitle, author, narrator, description, duration)
                        }
                    }
                }
                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    if (mediaItem == null) {
                        _uiState.update {
                        it.copy(
                            currentUri = "",
                            currentTitle = "",
                            currentAuthor = "",
                            currentNarrator = "",
                            currentCoverPath = null,
                            currentThumbnailPath = null,
                            currentChapters = emptyList(),
                            currentSubtitles = emptyList(),
                            currentBookmarks = emptyList(),
                            backgroundColorArgb = DEFAULT_COVER_BACKGROUND_ARGB
                        )
                    }
                        currentMediaUri = null
                        return
                    }
                    val transTitle = mediaItem.mediaMetadata.title?.toString()
                    val transAuthor = mediaItem.mediaMetadata.artist?.toString()
                    val transNarrator = mediaItem.mediaMetadata.composer?.toString()
                    _uiState.update { state ->
                        state.copy(
                            currentUri = mediaItem.mediaId,
                            currentTitle = if (!transTitle.isNullOrBlank()) transTitle else state.currentTitle,
                            currentAuthor = if (!transAuthor.isNullOrBlank() && transAuthor != "Unknown Author") transAuthor else state.currentAuthor,
                            currentNarrator = if (!transNarrator.isNullOrBlank()) transNarrator else state.currentNarrator,
                            duration = mediaController.duration.coerceAtLeast(0L)
                        )
                    }
                    currentMediaUri = mediaItem.mediaId
                    
                    // Load chapters, subtitles & bookmarks
                    loadChapters(mediaItem.mediaId)
                    loadSubtitles(mediaItem.mediaId)
                    loadBookmarks(mediaItem.mediaId)
                    loadRelatedBooks(mediaItem.mediaId, transAuthor ?: "Unknown Author", transNarrator ?: "Unknown Narrator")
                }
            })
            // Start progress polling & auto-save
            viewModelScope.launch(Dispatchers.Main.immediate) {
                var saveCounter = 0
                while (true) {
                    // mediaController methods must be called on Main thread
                    val isActuallyPlaying = mediaController?.isPlaying == true
                    val playbackState = mediaController?.playbackState ?: Player.STATE_IDLE
                    
                    if (isActuallyPlaying && playbackState != Player.STATE_ENDED) {
                        val pos = mediaController.currentPosition.coerceAtLeast(0L)
                        if (pos > 0 || playbackState == Player.STATE_READY) {
                            _uiState.update { it.copy(currentPosition = pos) }
                        }
                        
                        saveCounter++
                        // Save progress every 10 seconds (20 * 1000ms)
                        if (saveCounter >= 10) {
                            saveCounter = 0
                            saveProgress()
                        }
                    }
                    delay(1000)
                }
            }

            // Autoload most recent media if nothing is currently playing/loaded
            if (mediaController?.mediaItemCount == 0) {
                viewModelScope.launch(Dispatchers.Main.immediate) {
                    val recent = libraryRepository?.getMostRecentAudiobook()
                    android.util.Log.d("PlayerViewModel", "Autoload search result: ${recent?.title}")
                    if (recent != null) {
                        loadMedia(
                            uri = recent.uri.toUri(),
                            title = recent.title,
                            author = recent.author,
                            narrator = recent.narrator,
                            startPositionMs = recent.lastPosition,
                            playWhenReady = false
                        )
                    }
                }
            }
        }, ContextCompat.getMainExecutor(context))
    }

    fun loadMedia(uri: Uri, title: String, author: String, narrator: String = "", startPositionMs: Long = 0L, playWhenReady: Boolean = true) {
        // Save progress of previous media before switching
        saveProgress()
        
        currentMediaUri = uri.toString()
        _uiState.update {
            it.copy(
                currentTitle = title,
                currentAuthor = author,
                currentNarrator = narrator,
                currentCoverPath = null,
                currentPosition = startPositionMs,
                currentChapters = emptyList(),
                currentSubtitles = emptyList(),
                currentBookmarks = emptyList(),
                showUndoSeek = false,
                isChapterListVisible = false,
                isBookmarkDialogVisible = false,
                bookmarkTitle = "",
                backgroundColorArgb = DEFAULT_COVER_BACKGROUND_ARGB
            )
        }

        // Load chapters, subtitles & bookmarks
        loadChapters(uri.toString())
        loadSubtitles(uri.toString())
        loadBookmarks(uri.toString())
        loadRelatedBooks(uri.toString(), author, narrator)

        // Only set metadata we actually know
        val metadataBuilder = MediaMetadata.Builder().setTitle(title)
        if (author != "Unknown Author") {
            metadataBuilder.setArtist(author)
        }
        if (narrator.isNotBlank()) {
            metadataBuilder.setComposer(narrator)
        }

        val extension = android.webkit.MimeTypeMap.getFileExtensionFromUrl(uri.toString())
        val mimeType = if (extension != null) {
            android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension.lowercase())
        } else {
            null
        } ?: when {
            uri.toString().endsWith(".m4b", ignoreCase = true) -> "audio/mp4"
            uri.toString().endsWith(".m4a", ignoreCase = true) -> "audio/mp4"
            uri.toString().endsWith(".mp3", ignoreCase = true) -> "audio/mpeg"
            uri.toString().endsWith(".flac", ignoreCase = true) -> "audio/flac"
            uri.toString().endsWith(".wav", ignoreCase = true) -> "audio/wav"
            uri.toString().endsWith(".ogg", ignoreCase = true) -> "audio/ogg"
            uri.toString().endsWith(".aac", ignoreCase = true) -> "audio/aac"
            else -> null
        }
            
        val mediaItem = MediaItem.Builder()
            .setMediaId(uri.toString())
            .setUri(uri)
            .setMimeType(mimeType)
            .setMediaMetadata(metadataBuilder.build())
            .build()
            
        player?.setMediaItem(mediaItem, startPositionMs)
        player?.prepare()
        if (playWhenReady) {
            player?.play()
        }
        
        // 立即保存一次进度，确保新加载的书籍立刻出现在“最近播放”列表顶部
        saveProgress(startPositionMs)
        
        // Poll for cover paths
        viewModelScope.launch {
            repeat(5) {
                val entity = libraryRepository?.getByUri(uri.toString())
                if (entity != null) {
                    if (entity.coverPath != null || entity.thumbnailPath != null) {
                        updateCoverPath(entity.coverPath)
                        updateThumbnailPath(entity.thumbnailPath)
                        return@launch
                    }
                }
                delay(1000)
            }
        }
    }
    
    private fun loadChapters(uri: String) {
        chaptersJob?.cancel()
        chaptersJob = viewModelScope.launch {
            libraryRepository?.getChapters(uri)?.collect {
                _uiState.update { state -> state.copy(currentChapters = it) }
            }
        }
    }

    private fun loadSubtitles(uri: String) {
        viewModelScope.launch {
            android.util.Log.d("PlayerViewModel", "Loading subtitles for $uri")
            val subs = libraryRepository?.loadSubtitles(uri) ?: emptyList()
            android.util.Log.d("PlayerViewModel", "Loaded ${subs.size} subtitles")
            _uiState.update { it.copy(currentSubtitles = subs) }
        }
    }

    private fun loadBookmarks(uri: String) {
        bookmarksJob?.cancel()
        bookmarksJob = viewModelScope.launch {
            libraryRepository?.getBookmarks(uri)?.collect {
                _uiState.update { state -> state.copy(currentBookmarks = it) }
            }
        }
    }

    private fun loadRelatedBooks(currentUri: String, author: String, narrator: String) {
        relatedBooksJob?.cancel()
        relatedBooksJob = viewModelScope.launch {
            val authorList = author.split(",").map { it.trim() }.filter { it.isNotBlank() && it != "Unknown Author" }
            val narratorList = narrator.split(",").map { it.trim() }.filter { it.isNotBlank() && it != "Unknown Narrator" }

            // Initialize sections to maintain order
            _uiState.update { state -> state.copy(
                relatedAuthorSections = authorList.map { name -> RelatedSection(name, emptyList()) },
                relatedNarratorSections = narratorList.map { name -> RelatedSection(name, emptyList()) },
                recentlyAddedBooks = emptyList()
            ) }

            authorList.forEachIndexed { index, name ->
                launch {
                    libraryRepository?.filterByAuthorLimited(name, currentUri, 3)?.collect { books ->
                        _uiState.update { state ->
                            val currentSections = state.relatedAuthorSections.toMutableList()
                            if (index < currentSections.size) {
                                currentSections[index] = RelatedSection(name, books)
                            }
                            state.copy(relatedAuthorSections = currentSections)
                        }
                    }
                }
            }

            narratorList.forEachIndexed { index, name ->
                launch {
                    libraryRepository?.filterByNarratorLimited(name, currentUri, 3)?.collect { books ->
                        _uiState.update { state ->
                            val currentSections = state.relatedNarratorSections.toMutableList()
                            if (index < currentSections.size) {
                                currentSections[index] = RelatedSection(name, books)
                            }
                            state.copy(relatedNarratorSections = currentSections)
                        }
                    }
                }
            }

            launch {
                libraryRepository?.getRecentlyAddedExclusive(currentUri, authorList, narratorList, 3)?.collect { books ->
                    _uiState.update { it.copy(recentlyAddedBooks = books) }
                }
            }
        }
    }

    fun addBookmark(title: String) {
        val uri = currentMediaUri ?: return
        val pos = _uiState.value.currentPosition
        viewModelScope.launch {
            libraryRepository?.addBookmark(uri, pos, title)
        }
    }

    fun deleteBookmark(bookmark: BookmarkEntity) {
        viewModelScope.launch {
            libraryRepository?.deleteBookmark(bookmark)
        }
    }

    fun updateBookmark(bookmark: BookmarkEntity, newTitle: String) {
        viewModelScope.launch {
            val updatedBookmark = bookmark.copy(
                title = newTitle,
                createdAt = System.currentTimeMillis()
            )
            libraryRepository?.updateBookmark(updatedBookmark)
        }
    }

    private fun extractChaptersFromPlayer(player: Player) {
        val uri = currentMediaUri ?: return
        // Must access currentTracks on the main thread
        val tracks = player.currentTracks
        
        // Extract metadata entries on the main thread to avoid background thread access to Tracks/Groups
        val metadataEntries = mutableListOf<androidx.media3.common.Metadata.Entry>()
        for (group in tracks.groups) {
            val trackGroup = group.mediaTrackGroup
            for (i in 0 until trackGroup.length) {
                val format = trackGroup.getFormat(i)
                val metadata = format.metadata ?: continue
                for (j in 0 until metadata.length()) {
                    metadataEntries.add(metadata[j])
                }
            }
        }

        if (metadataEntries.isEmpty()) return

        // Switch to Default thread for reflection and heavy computation
        viewModelScope.launch(Dispatchers.Default) {
            val chapters = mutableListOf<ChapterEntity>()
            for (entry in metadataEntries) {
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

            // Distinct and sort
            val finalChapters = chapters.asSequence().distinctBy { it.startPosition }.sortedBy { it.startPosition }.toList()

            if (finalChapters.isNotEmpty()) {
                _uiState.update { it.copy(currentChapters = finalChapters) }
                // Save to DB for future use
                libraryRepository?.saveChapters(uri, finalChapters)
            }
        }
    }

    private fun saveProgress(position: Long? = null) {
        val uri = currentMediaUri ?: return
        
        if (position != null) {
            libraryRepository?.updateProgress(uri, position)
            return
        }

        // Must access player?.currentPosition on the main thread
        viewModelScope.launch {
            val pos = player?.currentPosition?.coerceAtLeast(0L) ?: _uiState.value.currentPosition
            libraryRepository?.updateProgress(uri, pos)
        }
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
            lastSeekPosition = _uiState.value.currentPosition
            _uiState.update { it.copy(showUndoSeek = true, currentPosition = positionMs) }
            undoJob?.cancel()
            undoJob = viewModelScope.launch {
                delay(3000)
                _uiState.update { it.copy(showUndoSeek = false) }
            }
        } else {
            _uiState.update { it.copy(showUndoSeek = false, currentPosition = positionMs) }
            undoJob?.cancel()
        }
        player?.seekTo(positionMs)
        player?.play() // 只要触发 Seek，就转为播放状态
        // 立即保存进度到数据库并更新本地 UI 状态，防止被后续的进度轮询拉回旧位置
        saveProgress(positionMs)
    }

    fun undoSeek() {
        if (_uiState.value.showUndoSeek) {
            seekTo(lastSeekPosition, allowUndo = false)
            _uiState.update { it.copy(showUndoSeek = false) }
            undoJob?.cancel()
        }
    }

    fun skipForward() {
        // TODO: Make skip duration customizable by user
        val state = _uiState.value
        val newPos = (state.currentPosition + 30000).coerceAtMost(state.duration)
        seekTo(newPos)
    }

    fun skipBackward() {
        // TODO: Make skip duration customizable by user
        val newPos = (_uiState.value.currentPosition - 10000).coerceAtLeast(0L)
        seekTo(newPos)
    }

    fun setPlaybackSpeed(speed: Float, manualMode: Boolean = speed != 1.0f) {
        player?.setPlaybackSpeed(speed)
        _uiState.update { it.copy(playbackSpeed = speed, isSpeedManualMode = manualMode) }
    }

    fun cyclePlaybackSpeed() {
        val state = _uiState.value
        val currentIndex = PLAYBACK_SPEEDS.indexOf(state.playbackSpeed).coerceAtLeast(0)
        val nextIndex = (currentIndex + 1) % PLAYBACK_SPEEDS.size
        setPlaybackSpeed(PLAYBACK_SPEEDS[nextIndex], manualMode = true)
    }

    fun resetPlaybackSpeed() {
        setPlaybackSpeed(1.0f, manualMode = false)
    }

    fun cycleSleepTimer() {
        val currentIndex = SLEEP_TIMER_OPTIONS.indexOf(_uiState.value.selectedSleepTimer).coerceAtLeast(0)
        val nextIndex = (currentIndex + 1) % SLEEP_TIMER_OPTIONS.size
        setSleepTimer(SLEEP_TIMER_OPTIONS[nextIndex])
    }

    fun setSleepTimer(minutes: Int) {
        sleepTimerJob?.cancel()
        _uiState.update { it.copy(selectedSleepTimer = minutes) }
        
        if (minutes == 0) {
            _sleepTimerMillis.value = 0L
            return
        }
        
        if (minutes == -2) {
            // End of Chapter mode
            sleepTimerJob = viewModelScope.launch {
                while (true) {
                    delay(1000)
                    val state = _uiState.value
                    if (state.isPlaying) {
                        val currentChapter = state.currentChapter
                        if (currentChapter != null) {
                            if (state.currentPosition >= currentChapter.endPosition - 1000) {
                                break
                            }
                        } else {
                            // Fallback to end of track if no chapters are found
                            if (state.duration > 0 && state.currentPosition >= state.duration - 1000) {
                                break
                            }
                        }
                    }
                }
                player?.pause()
                _uiState.update { it.copy(selectedSleepTimer = 0) }
                _sleepTimerMillis.value = 0
            }
            return
        }

        // Special handling for test option: if minutes is -1, set 5 seconds
        val millis = if (minutes < 0) 5000L else minutes * 60 * 1000L
        _sleepTimerMillis.value = millis
        
        sleepTimerJob = viewModelScope.launch {
            while (_sleepTimerMillis.value > 0) {
                delay(1000)
                // Only count down if actually playing
                if (_uiState.value.isPlaying) {
                    _sleepTimerMillis.value = (_sleepTimerMillis.value - 1000).coerceAtLeast(0L)
                }
            }
            player?.pause()
            _uiState.update { it.copy(selectedSleepTimer = 0) }
            _sleepTimerMillis.value = 0
        }
    }

    fun showChapterList() {
        _uiState.update { it.copy(isChapterListVisible = true) }
    }

    fun dismissChapterList() {
        _uiState.update { it.copy(isChapterListVisible = false) }
    }

    fun showBookmarkDialog() {
        _uiState.update { it.copy(isBookmarkDialogVisible = true, bookmarkTitle = "") }
    }

    fun dismissBookmarkDialog() {
        _uiState.update { it.copy(isBookmarkDialogVisible = false, bookmarkTitle = "") }
    }

    fun updateBookmarkTitle(title: String) {
        _uiState.update { it.copy(bookmarkTitle = title) }
    }

    fun saveBookmarkFromDialog() {
        val title = _uiState.value.bookmarkTitle.ifBlank { "Bookmark" }
        addBookmark(title)
        dismissBookmarkDialog()
    }

    fun setSelectedContentTab(tab: Int) {
        _uiState.update { it.copy(selectedContentTab = tab) }
    }

    fun setFullPlayerVisible(visible: Boolean) {
        _uiState.update { it.copy(isFullPlayerVisible = visible) }
    }

    fun setMiniPlayerHidden(hidden: Boolean) {
        _uiState.update { it.copy(isMiniPlayerHidden = hidden) }
    }

    fun onRouteChanged() {
        _uiState.update { it.copy(isMiniPlayerHidden = false) }
    }

    fun toggleProgressMode() {
        _uiState.update { it.copy(isChapterProgressMode = !it.isChapterProgressMode) }
    }

    private var volumeAccumulator = 0f
    fun adjustVolume(delta: Float) {
        val am = audioManager ?: return
        volumeAccumulator += delta
        
        // 每个单位步长需要的滑动位移，可以根据灵敏度调整
        val threshold = 0.05f 
        
        if (kotlin.math.abs(volumeAccumulator) >= threshold) {
            val direction = if (volumeAccumulator > 0) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER
            am.adjustStreamVolume(
                AudioManager.STREAM_MUSIC,
                direction,
                AudioManager.FLAG_SHOW_UI // 显示系统音量条
            )
            volumeAccumulator = 0f
        }
    }

    fun skipToNextChapter() {
        val state = _uiState.value
        val chapters = state.currentChapters
        if (chapters.isEmpty()) return
        
        val currentIndex = chapters.indexOfLast { state.currentPosition >= it.startPosition }
        if (currentIndex != -1 && currentIndex < chapters.size - 1) {
            seekTo(chapters[currentIndex + 1].startPosition)
        }
    }

    fun skipToPreviousChapter() {
        val state = _uiState.value
        val chapters = state.currentChapters
        if (chapters.isEmpty()) return

        val currentIndex = chapters.indexOfLast { state.currentPosition >= it.startPosition }
        if (currentIndex != -1) {
            val currentChapter = chapters[currentIndex]
            // If we are more than 3 seconds into the chapter, go to start of current chapter
            if (state.currentPosition - currentChapter.startPosition > 3000) {
                seekTo(currentChapter.startPosition)
            } else if (currentIndex > 0) {
                seekTo(chapters[currentIndex - 1].startPosition)
            }
        }
    }

    private fun updateCoverPath(path: String?) {
        _uiState.update { it.copy(currentCoverPath = path) }
        path?.let { p ->
            viewModelScope.launch(Dispatchers.Default) {
                val dominantColor = extractCoverDominantColorArgb(p)
                _uiState.update { it.copy(backgroundColorArgb = dominantColor) }
            }
        }
    }

    private fun updateThumbnailPath(path: String?) {
        _uiState.update { it.copy(currentThumbnailPath = path) }
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
