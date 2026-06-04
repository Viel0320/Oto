package com.viel.aplayer.ui.player

import android.content.Context
import android.media.AudioManager
import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.viel.aplayer.APlayerApplication
import com.viel.aplayer.data.AppSettingsRepository
import com.viel.aplayer.data.entity.BookmarkEntity
import com.viel.aplayer.media.AutoRewindManager
import com.viel.aplayer.media.PlaybackManager
import com.viel.aplayer.media.PlaybackMediaId
import com.viel.aplayer.media.parser.ImageProcessor
import com.viel.aplayer.ui.player.components.bookmarks.BookmarkManager
import com.viel.aplayer.ui.player.components.relatedsection.GetRelatedBooksUseCase
import com.viel.aplayer.ui.player.components.relatedsection.RelatedData
import com.viel.aplayer.ui.settings.PlayerSettingsManager
import com.viel.aplayer.ui.settings.PlayerSettingsState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PlayerViewModel : ViewModel() {
    companion object {
        private val PLAYBACK_SPEEDS = listOf(0.25f, 0.5f, 0.75f, 1.0f, 1.25f, 1.5f)
        private val SLEEP_TIMER_OPTIONS = listOf(0, -1, -2, 15, 30, 60)
    }

    private var playbackManager: PlaybackManager? = null
    // Downgrade database operations (To decouple direct heavy DB transactions from view models)
    private var libraryFacade: com.viel.aplayer.data.LibraryFacade? = null
    private var settingsRepository: AppSettingsRepository? = null
    private var getRelatedBooksUseCase: GetRelatedBooksUseCase? = null
    private var audioManager: AudioManager? = null
    
    private val _currentBookId = MutableStateFlow<String?>(null)
    val currentBookId: StateFlow<String?> = _currentBookId.asStateFlow()

    // Shared UI events flow (To dispatch one-time visual alerts like toast notices)
    // Listens to playback feedback events asynchronously without holding UI view references.
    private val _uiEvents = kotlinx.coroutines.flow.MutableSharedFlow<com.viel.aplayer.ui.common.UiEvent>(extraBufferCapacity = 1)
    val uiEvents = _uiEvents.asSharedFlow()

    private val _currentSubtitles = MutableStateFlow<List<com.viel.aplayer.ui.player.components.SubtitleLine>>(emptyList())

    // Subtitle async job (To prevent overlapping coroutine threads when changing tracks)
    private var subtitleLoadJob: kotlinx.coroutines.Job? = null

    private var bookmarkManager: BookmarkManager? = null
    private var playbackDelegate: MediaPlaybackDelegate? = null
    // Cached application context (To decouple Activity context bounds during initialization)
    private var appContext: Context? = null
    private val settingsManager: PlayerSettingsManager = PlayerSettingsManager(
        scope = viewModelScope,
        playbackManager = { playbackManager },
        audioManager = { audioManager },
        contextProvider = { appContext }
    )

    // =====================================================================
    // M-16 Fix — Elevate bookmark dialog states (To preserve user edit text during orientation changes)
    // Manages edit state in ViewModel scopes rather than transient composables.
    // =====================================================================

    /** Dialog visual states (To aggregate active edits and deletions options) */
    data class BookmarkDialogsState(
        val toDelete: BookmarkEntity? = null,
        val toEdit: BookmarkEntity? = null,
        val editTitle: String = ""
    )

    private val _bookmarkDialogs = MutableStateFlow(BookmarkDialogsState())
    /** Expose dialog flows (To stream dialog overlays state) */
    val bookmarkDialogs: StateFlow<BookmarkDialogsState> = _bookmarkDialogs.asStateFlow()

    /** Request bookmark deletion (To display confirmation modal dialog) */
    fun requestDeleteBookmark(b: BookmarkEntity) {
        _bookmarkDialogs.update { it.copy(toDelete = b) }
    }

    /** Request bookmark modification (To display edit dialog autofilled with existing content) */
    fun requestEditBookmark(b: BookmarkEntity) {
        _bookmarkDialogs.update { it.copy(toEdit = b, editTitle = b.title) }
    }

    /** Update editing text (To synchronizes edit input changes to state) */
    fun onBookmarkEditTitleChange(t: String) {
        _bookmarkDialogs.update { it.copy(editTitle = t) }
    }

    /** Dismiss dialog models (To wipe active bookmark edit memory) */
    fun dismissBookmarkDialogs() {
        _bookmarkDialogs.value = BookmarkDialogsState()
    }

    // =====================================================================
    // Track failure dialog states (To manage confirmation overlays for broken audio tracks)
    // =====================================================================

    /** Track failure model (To model broken audio details) */
    data class TrackUnavailableDialogState(
        val show: Boolean = false,
        val bookId: String = "",
        val queueIndex: Int = -1
    )

    private val _trackUnavailableDialog = MutableStateFlow(TrackUnavailableDialogState())
    /** Expose failure alerts (To stream broken track alerts to UI screens) */
    val trackUnavailableDialogState: StateFlow<TrackUnavailableDialogState> = _trackUnavailableDialog.asStateFlow()

    /** Display broken track warnings (To alert user that track files are missing) */
    fun showTrackUnavailableDialog(bookId: String, queueIndex: Int) {
        _trackUnavailableDialog.value = TrackUnavailableDialogState(true, bookId, queueIndex)
    }

    /** Close track warnings (To hide track failure dialogs) */
    fun dismissTrackUnavailableDialog() {
        _trackUnavailableDialog.value = TrackUnavailableDialogState()
    }

    private var _lastDominantColor = ImageProcessor.DEFAULT_BACKGROUND_ARGB

    val settingsState: StateFlow<PlayerSettingsState> = settingsManager.settingsState
    val sleepTimerMillis: StateFlow<Long> = settingsManager.sleepTimerMillis

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val metadataState: StateFlow<BookMetadataState> = _currentBookId
        .flatMapLatest { id ->
            val facade = libraryFacade ?: return@flatMapLatest flowOf(BookMetadataState())
            if (id == null) return@flatMapLatest flowOf(BookMetadataState())
 
            combine(
                facade.observeBookById(id),
                facade.getChapters(id),
                facade.getBookmarks(id),
                _currentSubtitles
            ) { entity: com.viel.aplayer.data.entity.BookEntity?, chapters: List<com.viel.aplayer.data.entity.ChapterWithBookFile>, bookmarks: List<BookmarkEntity>, subtitles: List<com.viel.aplayer.ui.player.components.SubtitleLine> ->
                BookMetadataState(
                    id = id,
                    title = entity?.title ?: "",
                    author = entity?.author ?: "",
                    narrator = entity?.narrator ?: "",
                    coverPath = entity?.coverPath,
                    thumbnailPath = entity?.thumbnailPath,
                    // Map database lastScannedAt timestamp (To force downstream updates when image caches are reconstructed)
                    coverLastUpdated = entity?.lastScannedAt ?: 0L,
                    chapters = chapters,
                    bookmarks = bookmarks,
                    subtitles = subtitles,
                    backgroundColorArgb = entity?.backgroundColorArgb ?: _lastDominantColor
                )
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), BookMetadataState())

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val _relatedData = metadataState
        .flatMapLatest { meta ->
            val id = meta.id
            if (id.isBlank() || id == "Unknown") {
                return@flatMapLatest flowOf(
                    RelatedData(
                        emptyList(),
                        emptyList(),
                        emptyList(),
                        emptyList()
                    )
                )
            }

            val author = meta.author
            val narrator = meta.narrator
            // Bind recommendations query (To query related catalog items reactively)
            // Relies on metadataState flows rather than reading snapshots to fetch data updates.
            getRelatedBooksUseCase?.invoke(id, author, narrator)
                ?: flowOf(RelatedData(emptyList(), emptyList(), emptyList(), emptyList()))
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000),
            RelatedData(emptyList(), emptyList(), emptyList(), emptyList())
        )

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

    // Spaced progress model (To capture position and duration attributes dynamically)
    data class PlaybackProgressViewState(
        val elapsedMs: Long = 0L,
        val durationMs: Long = 0L,
        val isChapterProgressMode: Boolean = false
    )

    // Spaced progress channel (To stream position updates without causing full-screen recompositions)
    // Uses distinctUntilChanged to isolate changes in pos, dur, and mode variables.
    val playbackProgressState: StateFlow<PlaybackProgressViewState> = combine(
        playbackState.map { it.currentPosition }.distinctUntilChanged(),
        playbackState.map { it.duration }.distinctUntilChanged(),
        settingsState.map { it.isChapterProgressMode }.distinctUntilChanged()
    ) { pos, dur, mode ->
        PlaybackProgressViewState(pos, dur, mode)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), PlaybackProgressViewState())

    // Chapter mapping flow (To match timestamps dynamically into chapter entities)
    val currentChapterState: StateFlow<com.viel.aplayer.data.entity.ChapterEntity?> = combine(
        playbackState.map { it.currentPosition }.distinctUntilChanged(),
        metadataState.map { it.chapters }.distinctUntilChanged()
    ) { pos, chapters ->
        PlaybackStateMapper.currentChapter(chapters.map { it.chapter }, pos)
    }
    .distinctUntilChanged()
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val playbackControlState: StateFlow<PlaybackControlState> = playbackState
        .map {
            PlaybackControlState(it.isPlaying, it.playbackSpeed, it.isSpeedManualMode)
        }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), PlaybackControlState(false, 1.0f, false))

    // Calculate progress percent (To calculate current progress percentage via PlaybackStateMapper)
    val currentPlaybackProgressPercent: StateFlow<Int> = playbackState
        .map { state ->
            PlaybackStateMapper.calculateProgressPercent(state.currentPosition, state.duration)
        }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    // Mini-player progress offset (To proxy complex offset calculations to PlaybackStateMapper)
    val miniPlayerProgress: StateFlow<Float> = combine(
        playbackState,
        metadataState.map { it.chapters }.distinctUntilChanged(),
        settingsState.map { it.isChapterProgressMode }.distinctUntilChanged()
    ) { state, chapters, isChapterMode ->
        PlaybackStateMapper.calculateMiniPlayerProgress(
            currentPosition = state.currentPosition,
            duration = state.duration,
            chapters = chapters.map { it.chapter },
            isChapterMode = isChapterMode,
            fallbackProgress = state.progress
        )
    }
    .distinctUntilChanged()
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0f)


    // Spaced global uiState (To decouple high-frequency position ticks from main screen layout)
    // Strips position values to avoid triggering constant layout recompositions.
    val uiState: StateFlow<PlayerUiState> = combine(
        metadataState,
        playbackControlState,
        settingsManager.settingsState,
        _relatedData
    ) { metadata, control, settings, related ->
        PlayerUiState(
            metadata = metadata,
            playback = PlaybackState(
                isPlaying = control.isPlaying,
                currentPosition = 0L, // Perform thorough "progress dehydration" to cut off high-frequency recompositions.
                duration = 0L,        // Progress dehydration.
                playbackSpeed = control.playbackSpeed,
                playWhenReady = control.isPlaying
            ),
            settings = settings,
            relatedAuthorSections = related.authorSections,
            relatedNarratorSections = related.narratorSections,
            recentlyAddedBooks = related.recentlyAdded,
            // Inject recommended data (To route catalog recommendations to uiState)
            heuristicRecommendedBooks = related.heuristicRecommended
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), PlayerUiState())

    data class PlaybackControlState(
        val isPlaying: Boolean,
        val playbackSpeed: Float,
        val isSpeedManualMode: Boolean
    )

    private var lastSeekPosition = 0L
    private var undoJob: kotlinx.coroutines.Job? = null
    // Prevents repeated app-level initialize calls from reloading the compact player over the current session.
    private var hasRestoredLastPlayedBook = false

    fun initialize(context: Context) {
        if (playbackManager != null) return
        val appContext = context.applicationContext
        this.appContext = appContext
        val container = (appContext as APlayerApplication).container
        
        // Resolve gateway dependencies (To load facade structures during initialization)
        // Avoids caching heavy repository references inside local scopes.
        val facade = container.libraryFacade
        libraryFacade = facade
        val queryGateway = container.bookQueryGateway
        settingsRepository = container.settingsRepository

        getRelatedBooksUseCase = GetRelatedBooksUseCase(queryGateway)
        audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        playbackManager = PlaybackManager.getInstance(appContext)

        bookmarkManager = BookmarkManager(queryGateway, viewModelScope)
        playbackDelegate = MediaPlaybackDelegate(
            playbackManager = { playbackManager },
            repository = queryGateway,
            scope = viewModelScope
        )

        observePlaybackManager()
        observeSettings()
        restoreLastPlayedBookToCompactPlayer()
    }

    private fun restoreLastPlayedBookToCompactPlayer() {
        if (hasRestoredLastPlayedBook) return
        hasRestoredLastPlayedBook = true

        viewModelScope.launch {
            // Perform progress self-healing (To resolve progress drift before restoring compact player UI)
            appContext?.let { ctx ->
                AutoRewindManager.getInstance(ctx).performColdStartSelfHealing()
            }

            // Query persistent playback checkpoint (To restore previous audiobook track coordinates)
            val lastProgress = libraryFacade?.getLastPlayedProgressSync() ?: return@launch
            if (_currentBookId.value == null) {
                loadBook(lastProgress.bookId, playWhenReady = false)
                // Restore compact player UI (To reveal mini-player controls in bottom navigation area)
                // Clears redundant visibility conditions to ensure proper layout hierarchy flow.
                settingsManager.setMiniPlayerHidden(false)
            }
        }
    }

    private fun observeSettings() {
        viewModelScope.launch {
            settingsRepository?.settingsFlow?.collect { settings ->
                if (settings.isChapterProgressMode != settingsState.value.isChapterProgressMode) {
                    settingsManager.setChapterProgressMode(settings.isChapterProgressMode)
                }
                // Synchronize sleep decay switch (To update PlayerSettingsManager fade-out options dynamically)
                settingsManager.isSleepFadeOutEnabled = settings.isSleepFadeOutEnabled
                // Synchronize shake reset switch (To update PlayerSettingsManager motion triggers options dynamically)
                settingsManager.isShakeToResetEnabled = settings.isShakeToResetEnabled
                // Synchronize timer strategy (To align PlayerSettingsManager sleep mode config with DataStore values)
                settingsManager.sleepMode = settings.sleepMode
            }
        }
    }

    private fun observePlaybackManager() {
        val manager = playbackManager ?: return

        viewModelScope.launch {
            // Forward background events (To pipe service-level notifications to observing UI components)
            // Emits notifications (like EVENT_SKIP_SILENCE) into shared flows.
            manager.uiEvents.collect { event ->
                _uiEvents.emit(event)
            }
        }

        viewModelScope.launch {
            manager.currentMediaItem.collectLatest { mediaItem ->
                if (mediaItem != null) {
                    val mediaParts = PlaybackMediaId.parse(mediaItem.mediaId)
                    if (mediaParts != null) {
                        val bookId = mediaParts.bookId
                        // Parse composite media identifier (To extract track path and book ID tokens)
                        // Uses trailing colon markers to avoid misparsing multi-colon identifiers.
                        val bookFileId = mediaParts.fileId
                        _currentBookId.value = bookId
                        settingsManager.setMiniPlayerHidden(false)

                        // Evict active track lyrics (To wipe previous subtitles lines when changing track selections)
                        subtitleLoadJob?.cancel()
                        _currentSubtitles.value = emptyList()

                        // Load track subtitles (To fetch text lines from filesystem asynchronously)
                        // Wraps jobs in ViewModel scope to avoid leak or concurrency risks.
                        subtitleLoadJob = viewModelScope.launch {
                            val externalSubs = kotlinx.coroutines.withContext(Dispatchers.IO) {
                                libraryFacade?.loadSubtitlesForBookFile(bookFileId) ?: emptyList()
                            }
                            _currentSubtitles.value = externalSubs
                        }
                    } else {
                        // Clear subtitle tasks (To wipe subtitle state when identifier parsing fails)
                        subtitleLoadJob?.cancel()
                        _currentSubtitles.value = emptyList()
                    }
                } else {
                    // Clear subtitle tasks (To wipe subtitle state when track is null)
                    subtitleLoadJob?.cancel()
                    _currentSubtitles.value = emptyList()
                }
            }
        }

        viewModelScope.launch {
            // Monitor track completions (To close active playback screen upon track end)
            // Delays for 5 seconds to synchronize UI dismissals with PlaybackService actions.
            manager.playbackState.collectLatest { state ->
                if (state == androidx.media3.common.Player.STATE_ENDED) {
                    delay(5000.milliseconds)
                    // Verify completion status (To ensure player is still idle before dismissing screen)
                    val currentState = manager.playbackState.value
                    if (currentState == androidx.media3.common.Player.STATE_ENDED || 
                        currentState == androidx.media3.common.Player.STATE_IDLE) {
                        closeCurrentPlayback()
                    }
                }
            }
        }
    }

    fun loadBook(id: String, playWhenReady: Boolean = true) {
        // Log loadBook duration (To identify performance lag during playback startup)
        val loadBookRequestStart = SystemClock.elapsedRealtime()
        // Prevent loading duplicate tracks (To avoid interrupting active playback sessions)
        // Ignores load requests matching current book ID.
        if (_currentBookId.value == id) {
            // Restore active playback (To resume paused sessions without reloading files)
            if (playWhenReady && !playbackState.value.isPlaying) {
                play()
            }
            return
        }

        // Evict previous track metadata (To prepare views for subsequent loading session)
        subtitleLoadJob?.cancel()
        _currentBookId.value = id
        _currentSubtitles.value = emptyList() // Reset subtitles of the previous book.
        settingsManager.setUndoSeekVisible(false)
        settingsManager.dismissChapterList()
        settingsManager.dismissBookmarkDialog()

        viewModelScope.launch {
            val playbackPlanStart = SystemClock.elapsedRealtime()
            val plan = libraryFacade?.getPlaybackPlan(id)
            val playbackPlanCost = SystemClock.elapsedRealtime() - playbackPlanStart
            com.viel.aplayer.logger.PlaybackTimingLogger.logPlaybackPlanBuild(
                bookId = id,
                costMs = playbackPlanCost,
                planReady = plan != null,
                playWhenReady = playWhenReady
            )
            if (plan != null) {
                val totalCost = SystemClock.elapsedRealtime() - loadBookRequestStart
                com.viel.aplayer.logger.PlaybackTimingLogger.logLoadBookReady(
                    bookId = id,
                    totalMs = totalCost,
                    fileCount = plan.files.size,
                    startPosition = plan.startGlobalPositionMs
                )
                playbackDelegate?.loadBook(plan, playWhenReady) { updateCoverPath(it) }
            } else {
                val totalCost = SystemClock.elapsedRealtime() - loadBookRequestStart
                com.viel.aplayer.logger.PlaybackTimingLogger.logLoadBookNoPlan(
                    bookId = id,
                    totalMs = totalCost
                )
            }
        }
    }

    fun deleteBookmark(bookmark: BookmarkEntity) = bookmarkManager?.deleteBookmark(bookmark)
    fun updateBookmark(bookmark: BookmarkEntity, newTitle: String) = bookmarkManager?.updateBookmark(bookmark, newTitle)
    fun addBookmark(title: String) {
        val id = _currentBookId.value ?: return
        bookmarkManager?.addBookmark(id, playbackState.value.currentPosition, title)
    }

    /** Stop active playback (To pause media player and wipe current tracks cache) */
    fun closePlayback(bookId: String) {
        if (_currentBookId.value == bookId) {
            // Clear subtitle tasks (To cancel active lyrics threads)
            subtitleLoadJob?.cancel()
            _currentBookId.value = null
            _currentSubtitles.value = emptyList()
            playbackManager?.pause()
            settingsManager.setFullPlayerVisible(false)
            settingsManager.setMiniPlayerHidden(true)
        }
    }

    fun closeCurrentPlayback() {
        // Compact player can request a self-exit when its restored media is no longer available.
        _currentBookId.value?.let(::closePlayback)
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
                delay(3000.milliseconds)
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
    
    // Dispatch UI notifications (To pipe temporary alert events to the main thread)
    fun sendUiEvent(event: com.viel.aplayer.ui.common.UiEvent) {
        viewModelScope.launch {
            _uiEvents.emit(event)
        }
    }

    fun showChapterList() = settingsManager.showChapterList()
    fun dismissChapterList() = settingsManager.dismissChapterList()
    fun showBookmarkDialog() = settingsManager.showBookmarkDialog()
    fun dismissBookmarkDialog() = settingsManager.dismissBookmarkDialog()
    fun updateBookmarkTitle(title: String) = settingsManager.updateBookmarkTitle(title)
    fun saveBookmarkFromDialog() {
        addBookmark(settingsState.value.bookmarkTitle.ifBlank { "Bookmark" })
        dismissBookmarkDialog()
        // Alert bookmark additions (To emit ShowToast event in uiEvents flow)
        sendUiEvent(com.viel.aplayer.ui.common.UiEvent.ShowToast("Bookmark added"))
    }
    fun setSelectedContentTab(tab: Int) = settingsManager.setSelectedContentTab(tab)
    fun setFullPlayerVisible(visible: Boolean) {
        settingsManager.setFullPlayerVisible(visible)
        if (visible) {
            // Restore mini-player visibility (To reveal compact playback controls in root layout)
            // Ensures bottom control panel is restored when leaving full-screen views.
            settingsManager.setMiniPlayerHidden(false)
        }
    }
    fun setMiniPlayerHidden(hidden: Boolean) = settingsManager.setMiniPlayerHidden(hidden)

    fun currentBookAvailability(bookId: String): kotlinx.coroutines.flow.Flow<Boolean> = flow {
        // Empty metadata means the compact player is not attached to a real restored book yet.
        if (bookId.isBlank()) {
            emit(true)
            return@flow
        }
        emit(libraryFacade?.checkCurrentPlaybackFileAvailability(bookId) ?: false)
    }
    
    fun toggleProgressMode() {
        viewModelScope.launch {
            val nextMode = !settingsState.value.isChapterProgressMode
            settingsRepository?.updateChapterProgressMode(nextMode)
        }
    }
    fun onRouteChanged() = settingsManager.setMiniPlayerHidden(false)

    fun skipToNextChapter() = playbackDelegate?.skipToNextChapter(metadataState.value.chapters.map { it.chapter }, playbackState.value.currentPosition)
    fun skipToPreviousChapter() = playbackDelegate?.skipToPreviousChapter(metadataState.value.chapters.map { it.chapter }, playbackState.value.currentPosition)

    /**
     * Skip damaged tracks (To jump current playback session to next available track item)
     */
    fun skipToNextAvailableTrack(bookId: String, queueIndex: Int) {
        playbackManager?.skipToNextAvailableTrack(bookId, queueIndex)
    }

    fun updateCoverPath(path: String?) {
        val id = _currentBookId.value ?: return
        path?.let { p ->
            viewModelScope.launch(Dispatchers.Default) {
                val entity = libraryFacade?.getBookById(id)
                if (entity?.backgroundColorArgb != null) {
                    _lastDominantColor = entity.backgroundColorArgb
                } else {
                    val color = ImageProcessor.getDominantColor(p)
                    _lastDominantColor = color
                    libraryFacade?.updateBackgroundColor(id, color)
                }
                settingsManager.setSelectedContentTab(settingsState.value.selectedContentTab)
            }
        }
    }
}
