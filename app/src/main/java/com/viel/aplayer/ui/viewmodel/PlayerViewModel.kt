package com.viel.aplayer.ui.viewmodel

import android.content.Context
import android.media.AudioManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.viel.aplayer.APlayerApplication
import com.viel.aplayer.playback.PlaybackManager
import com.viel.aplayer.data.BookmarkEntity
import com.viel.aplayer.data.LibraryRepository
import com.viel.aplayer.data.AppSettingsRepository
import com.viel.aplayer.domain.GetRelatedBooksUseCase
import com.viel.aplayer.domain.RelatedData
import com.viel.aplayer.ui.state.PlayerUiState
import com.viel.aplayer.ui.state.BookMetadataState
import com.viel.aplayer.ui.state.PlaybackState
import com.viel.aplayer.ui.state.PlayerSettingsState
import com.viel.aplayer.util.image.ImageProcessor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PlayerViewModel : ViewModel() {
    companion object {
        private val PLAYBACK_SPEEDS = listOf(0.25f, 0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f)
        private val SLEEP_TIMER_OPTIONS = listOf(0, -1, -2, 15, 30, 60)
    }

    private var playbackManager: PlaybackManager? = null
    private var libraryRepository: LibraryRepository? = null
    private var settingsRepository: AppSettingsRepository? = null
    private var getRelatedBooksUseCase: GetRelatedBooksUseCase? = null
    private var audioManager: AudioManager? = null
    
    private val _currentBookId = MutableStateFlow<String?>(null)
    val currentBookId: StateFlow<String?> = _currentBookId.asStateFlow()

    private val _currentSubtitles = MutableStateFlow<List<com.viel.aplayer.ui.components.SubtitleLine>>(emptyList())

    private var bookmarkManager: BookmarkManager? = null
    private var playbackDelegate: MediaPlaybackDelegate? = null
    private val settingsManager: PlayerSettingsManager = PlayerSettingsManager(
        scope = viewModelScope,
        playbackManager = { playbackManager },
        audioManager = { audioManager }
    )

    private var _lastDominantColor = ImageProcessor.DEFAULT_BACKGROUND_ARGB

    val settingsState: StateFlow<PlayerSettingsState> = settingsManager.settingsState
    val sleepTimerMillis: StateFlow<Long> = settingsManager.sleepTimerMillis

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val _relatedData = _currentBookId
        .flatMapLatest { id ->
            if (id == null) return@flatMapLatest flowOf(RelatedData(emptyList(), emptyList(), emptyList()))
            
            val meta = metadataState.value
            val author = meta.author
            val narrator = meta.narrator
            getRelatedBooksUseCase?.invoke(id, author, narrator) ?: flowOf(RelatedData(emptyList(), emptyList(), emptyList()))
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), RelatedData(emptyList(), emptyList(), emptyList()))

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val metadataState: StateFlow<BookMetadataState> = _currentBookId
        .flatMapLatest { id ->
            val repo = libraryRepository ?: return@flatMapLatest flowOf(BookMetadataState())
            if (id == null) return@flatMapLatest flowOf(BookMetadataState())

            combine(
                repo.observeBookById(id),
                repo.getChapters(id),
                repo.getBookmarks(id),
                _currentSubtitles
            ) { entity: com.viel.aplayer.data.BookEntity?, chapters: List<com.viel.aplayer.data.ChapterEntity>, bookmarks: List<com.viel.aplayer.data.BookmarkEntity>, subtitles: List<com.viel.aplayer.ui.components.SubtitleLine> ->
                BookMetadataState(
                    id = id,
                    title = entity?.title ?: "",
                    author = entity?.author ?: "",
                    narrator = entity?.narrator ?: "",
                    coverPath = entity?.coverPath,
                    thumbnailPath = entity?.thumbnailPath,
                    chapters = chapters,
                    bookmarks = bookmarks,
                    subtitles = subtitles,
                    backgroundColorArgb = entity?.backgroundColorArgb ?: _lastDominantColor
                )
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), BookMetadataState())

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val playbackState: StateFlow<PlaybackState> = _currentBookId
        .flatMapLatest { _ ->
            playbackManager?.let { manager ->
                combine(
                    manager.isPlaying,
                    manager.playbackState,
                    manager.currentPosition,
                    manager.duration,
                    manager.playbackSpeed
                ) { isPlaying, _, pos, dur, speed ->
                    PlaybackState(
                        isPlaying = isPlaying,
                        currentPosition = pos,
                        duration = dur,
                        playbackSpeed = speed,
                        playWhenReady = isPlaying
                    )
                }
            } ?: flowOf(PlaybackState())
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), PlaybackState())

    val playbackControlState: StateFlow<PlaybackControlState> = playbackState
        .map { 
            PlaybackControlState(it.isPlaying, it.playbackSpeed, it.isSpeedManualMode)
        }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), PlaybackControlState(false, 1.0f, false))

    val uiState: StateFlow<PlayerUiState> = combine(
        metadataState,
        playbackState,
        settingsManager.settingsState,
        _relatedData
    ) { metadata, playback, settings, related ->
        PlayerUiState(
            metadata = metadata,
            playback = playback,
            settings = settings,
            relatedAuthorSections = related.authorSections,
            relatedNarratorSections = related.narratorSections,
            recentlyAddedBooks = related.recentlyAdded
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), PlayerUiState())

    data class PlaybackControlState(
        val isPlaying: Boolean,
        val playbackSpeed: Float,
        val isSpeedManualMode: Boolean
    )

    private var lastSeekPosition = 0L
    private var undoJob: kotlinx.coroutines.Job? = null

    fun initialize(context: Context) {
        if (playbackManager != null) return
        val appContext = context.applicationContext
        val container = (appContext as APlayerApplication).container
        libraryRepository = container.libraryRepository
        settingsRepository = container.settingsRepository
        
        getRelatedBooksUseCase = GetRelatedBooksUseCase(libraryRepository!!)
        audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        playbackManager = PlaybackManager.getInstance(appContext)

        bookmarkManager = BookmarkManager(libraryRepository!!, viewModelScope)
        playbackDelegate = MediaPlaybackDelegate(
            playbackManager = { playbackManager },
            repository = libraryRepository!!,
            scope = viewModelScope
        )

        observePlaybackManager()
        observeSettings()
    }

    private fun observeSettings() {
        viewModelScope.launch {
            settingsRepository?.settingsFlow?.collect { settings ->
                if (settings.isChapterProgressMode != settingsState.value.isChapterProgressMode) {
                    settingsManager.setChapterProgressMode(settings.isChapterProgressMode)
                }
            }
        }
    }

    private fun observePlaybackManager() {
        val manager = playbackManager ?: return

        viewModelScope.launch {
            manager.currentMediaItem.collect { mediaItem ->
                if (mediaItem != null) {
                    val mediaId = mediaItem.mediaId
                    if (mediaId.contains(":")) {
                        val bookId = mediaId.substringBefore(":")
                        _currentBookId.value = bookId
                        settingsManager.setMiniPlayerHidden(false)
                    }
                }
            }
        }
    }

    fun loadBook(id: String, playWhenReady: Boolean = true) {
        _currentBookId.value = id
        _currentSubtitles.value = emptyList() // 重置上一本书的字幕
        settingsManager.setUndoSeekVisible(false)
        settingsManager.dismissChapterList()
        settingsManager.dismissBookmarkDialog()

        viewModelScope.launch {
            // 异步加载字幕，不阻塞主元数据流
            val subs = libraryRepository?.loadSubtitles(id) ?: emptyList()
            _currentSubtitles.value = subs

            val plan = libraryRepository?.getPlaybackPlan(id)
            if (plan != null) {
                playbackDelegate?.loadBook(plan, playWhenReady) { updateCoverPath(it) }
            }
        }
    }

    fun deleteBookmark(bookmark: BookmarkEntity) = bookmarkManager?.deleteBookmark(bookmark)
    fun updateBookmark(bookmark: BookmarkEntity, newTitle: String) = bookmarkManager?.updateBookmark(bookmark, newTitle)
    fun addBookmark(title: String) {
        val id = _currentBookId.value ?: return
        bookmarkManager?.addBookmark(id, playbackState.value.currentPosition, title)
    }

    /**
     * 停止播放并清理当前书籍状态（主要用于删除书籍时）。
     */
    fun closePlayback(bookId: String) {
        if (_currentBookId.value == bookId) {
            _currentBookId.value = null
            _currentSubtitles.value = emptyList()
            playbackManager?.pause()
            settingsManager.setFullPlayerVisible(false)
            settingsManager.setMiniPlayerHidden(true)
        }
    }

    fun togglePlayPause() = if (playbackState.value.isPlaying) pause() else play()
    fun play() = playbackDelegate?.play()
    fun pause() = playbackDelegate?.pause()

    fun seekTo(positionMs: Long, allowUndo: Boolean = false) {
        if (allowUndo) {
            lastSeekPosition = playbackState.value.currentPosition
            settingsManager.setUndoSeekVisible(true)
            undoJob?.cancel()
            undoJob = viewModelScope.launch {
                delay(3000)
                settingsManager.setUndoSeekVisible(false)
            }
        } else {
            settingsManager.setUndoSeekVisible(false)
            undoJob?.cancel()
        }
        playbackDelegate?.seekTo(positionMs)
    }

    fun undoSeek() {
        if (settingsState.value.showUndoSeek) {
            seekTo(lastSeekPosition, allowUndo = false)
            settingsManager.setUndoSeekVisible(false)
        }
    }

    fun skipForward() = seekTo((playbackState.value.currentPosition + 30000).coerceAtMost(playbackState.value.duration))
    fun skipBackward() = seekTo((playbackState.value.currentPosition - 10000).coerceAtLeast(0L))

    fun setPlaybackSpeed(speed: Float) = playbackDelegate?.setPlaybackSpeed(speed)
    fun cyclePlaybackSpeed() {
        val speed = playbackState.value.playbackSpeed
        val nextIndex = (PLAYBACK_SPEEDS.indexOf(speed).coerceAtLeast(0) + 1) % PLAYBACK_SPEEDS.size
        setPlaybackSpeed(PLAYBACK_SPEEDS[nextIndex])
    }
    fun resetPlaybackSpeed() = setPlaybackSpeed(1.0f)

    fun cycleSleepTimer() {
        val options = SLEEP_TIMER_OPTIONS
        val nextIndex = (options.indexOf(settingsState.value.selectedSleepTimer).coerceAtLeast(0) + 1) % options.size
        setSleepTimer(options[nextIndex])
    }

    fun setSleepTimer(minutes: Int) = settingsManager.setSleepTimer(minutes, { playbackState.value }, { metadataState.value })
    fun adjustVolume(delta: Float) = settingsManager.adjustVolume(delta)
    
    fun showChapterList() = settingsManager.showChapterList()
    fun dismissChapterList() = settingsManager.dismissChapterList()
    fun showBookmarkDialog() = settingsManager.showBookmarkDialog()
    fun dismissBookmarkDialog() = settingsManager.dismissBookmarkDialog()
    fun updateBookmarkTitle(title: String) = settingsManager.updateBookmarkTitle(title)
    fun saveBookmarkFromDialog() {
        addBookmark(settingsState.value.bookmarkTitle.ifBlank { "Bookmark" })
        dismissBookmarkDialog()
    }
    fun setSelectedContentTab(tab: Int) = settingsManager.setSelectedContentTab(tab)
    fun setFullPlayerVisible(visible: Boolean) = settingsManager.setFullPlayerVisible(visible)
    fun setMiniPlayerHidden(hidden: Boolean) = settingsManager.setMiniPlayerHidden(hidden)
    
    fun toggleProgressMode() {
        viewModelScope.launch {
            val nextMode = !settingsState.value.isChapterProgressMode
            settingsRepository?.updateChapterProgressMode(nextMode)
        }
    }
    fun onRouteChanged() = settingsManager.setMiniPlayerHidden(false)

    fun skipToNextChapter() = playbackDelegate?.skipToNextChapter(metadataState.value.chapters, playbackState.value.currentPosition)
    fun skipToPreviousChapter() = playbackDelegate?.skipToPreviousChapter(metadataState.value.chapters, playbackState.value.currentPosition)

    fun updateCoverPath(path: String?) {
        val id = _currentBookId.value ?: return
        path?.let { p ->
            viewModelScope.launch(Dispatchers.Default) {
                val entity = libraryRepository?.getBookById(id)
                if (entity?.backgroundColorArgb != null) {
                    _lastDominantColor = entity.backgroundColorArgb
                } else {
                    val color = ImageProcessor.getDominantColor(p)
                    _lastDominantColor = color
                    libraryRepository?.updateBackgroundColor(id, color)
                }
                settingsManager.setSelectedContentTab(settingsState.value.selectedContentTab)
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        playbackManager?.release()
    }
}
