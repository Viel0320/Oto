package com.viel.aplayer.ui.viewmodel

import android.content.Context
import android.media.AudioManager
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.viel.aplayer.playback.PlaybackManager
import com.viel.aplayer.data.BookmarkEntity
import com.viel.aplayer.data.LibraryRepository
import com.viel.aplayer.domain.GetRelatedBooksUseCase
import com.viel.aplayer.domain.RelatedData
import com.viel.aplayer.ui.state.PlayerUiState
import com.viel.aplayer.ui.state.BookMetadataState
import com.viel.aplayer.ui.state.PlaybackState
import com.viel.aplayer.ui.state.PlayerSettingsState
import com.viel.aplayer.util.image.ImageProcessor
import com.viel.aplayer.util.parser.AudiobookParser
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
    private var getRelatedBooksUseCase: GetRelatedBooksUseCase? = null
    private var audioManager: AudioManager? = null
    
    // 替换为 MutableStateFlow 以驱动响应式流，减少对 settingsState 的依赖 (优化方向 1)
    private val _currentMediaUri = MutableStateFlow<String?>(null)
    val currentMediaUri: StateFlow<String?> = _currentMediaUri.asStateFlow()

    // --- Delegates (Managers & Delegates) ---
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

    // 1. 动态推荐流：仅在 URI 变化时重新触发 (优化方向 1)
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val _relatedData = _currentMediaUri
        .flatMapLatest { uri ->
            if (uri == null) return@flatMapLatest flowOf(RelatedData(emptyList(), emptyList(), emptyList()))
            
            val meta = playbackManager?.currentMediaItem?.value?.mediaMetadata
            val author = meta?.artist?.toString() ?: ""
            val narrator = meta?.composer?.toString() ?: ""
            getRelatedBooksUseCase?.invoke(uri, author, narrator) ?: flowOf(RelatedData(emptyList(), emptyList(), emptyList()))
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), RelatedData(emptyList(), emptyList(), emptyList()))

    // 2. 动态元数据流：仅在 URI 或书籍实体/章节/书签实质变化时触发 (优化方向 1 & 3)
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val metadataState: StateFlow<BookMetadataState> = _currentMediaUri
        .flatMapLatest { uri ->
            val repo = libraryRepository ?: return@flatMapLatest flowOf(BookMetadataState())
            if (uri == null) return@flatMapLatest flowOf(BookMetadataState())

            combine(
                repo.getByUriFlow(uri),
                repo.getChapters(uri),
                repo.getBookmarks(uri)
            ) { entity, chapters, bookmarks ->
                BookMetadataState(
                    uri = uri,
                    title = entity?.title ?: "",
                    author = entity?.author ?: "",
                    narrator = entity?.narrator ?: "",
                    coverPath = entity?.coverPath,
                    thumbnailPath = entity?.thumbnailPath,
                    chapters = chapters,
                    bookmarks = bookmarks,
                    backgroundColorArgb = entity?.backgroundColorArgb ?: _lastDominantColor
                )
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), BookMetadataState())

    // 3. 动态播放状态流：直接监听 PlaybackManager，不再依赖 settingsState (优化方向 1)
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val playbackState: StateFlow<PlaybackState> = _currentMediaUri
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

    // 4. 播放控制流（用于 UI 按钮）
    val playbackControlState: StateFlow<PlaybackControlState> = playbackState
        .map { 
            PlaybackControlState(it.isPlaying, it.playbackSpeed, it.isSpeedManualMode)
        }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), PlaybackControlState(false, 1.0f, false))

    // 5. 全局聚合状态
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
        libraryRepository = LibraryRepository.getInstance(appContext)
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
    }

    private fun observePlaybackManager() {
        val manager = playbackManager ?: return

        viewModelScope.launch {
            manager.metadataEntries.collect { entries ->
                if (entries.isNotEmpty()) {
                    _currentMediaUri.value?.let { uri ->
                        val chapters = AudiobookParser.extractChaptersFromMetadata(entries, uri)
                        if (chapters.isNotEmpty()) {
                            libraryRepository?.saveChapters(uri, chapters)
                        }
                    }
                }
            }
        }

        viewModelScope.launch {
            manager.currentMediaItem.collect { mediaItem ->
                if (mediaItem != null) {
                    _currentMediaUri.value = mediaItem.mediaId
                    settingsManager.setMiniPlayerHidden(false)
                }
            }
        }
    }

    fun loadMedia(uri: Uri, title: String, author: String, narrator: String = "", startPositionMs: Long = 0L, playWhenReady: Boolean = true) {
        _currentMediaUri.value = uri.toString()
        settingsManager.setUndoSeekVisible(false)
        settingsManager.dismissChapterList()
        settingsManager.dismissBookmarkDialog()

        playbackDelegate?.loadMedia(
            uri = uri,
            title = title,
            author = author,
            narrator = narrator,
            startPositionMs = startPositionMs,
            playWhenReady = playWhenReady,
            onCoverUpdate = { updateCoverPath(it) }
        )
    }

    // --- Delegate Calls ---
    fun deleteBookmark(bookmark: BookmarkEntity) = bookmarkManager?.deleteBookmark(bookmark)
    fun updateBookmark(bookmark: BookmarkEntity, newTitle: String) = bookmarkManager?.updateBookmark(bookmark, newTitle)
    fun addBookmark(title: String) {
        val uri = _currentMediaUri.value ?: return
        bookmarkManager?.addBookmark(uri, playbackState.value.currentPosition, title)
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
    fun toggleProgressMode() = settingsManager.toggleProgressMode()
    fun onRouteChanged() = settingsManager.setMiniPlayerHidden(false)

    fun skipToNextChapter() = playbackDelegate?.skipToNextChapter(metadataState.value.chapters, playbackState.value.currentPosition)
    fun skipToPreviousChapter() = playbackDelegate?.skipToPreviousChapter(metadataState.value.chapters, playbackState.value.currentPosition)

    private fun updateCoverPath(path: String?) {
        val uri = _currentMediaUri.value ?: return
        path?.let { p ->
            viewModelScope.launch(Dispatchers.Default) {
                // 优化方向 3：检查数据库是否已有颜色缓存，减少提取操作
                val entity = libraryRepository?.getByUri(uri)
                if (entity?.backgroundColorArgb != null) {
                    _lastDominantColor = entity.backgroundColorArgb
                } else {
                    val color = ImageProcessor.getDominantColor(p)
                    _lastDominantColor = color
                    // 存入数据库缓存，getByUriFlow 会自动触发 metadataState 更新
                    libraryRepository?.updateBackgroundColor(uri, color)
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
