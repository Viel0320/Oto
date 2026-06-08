package com.viel.aplayer.ui.player

// UseCase Import Update: Align imports with the standardized application usecase layer package.
import android.content.Context
import android.media.AudioManager
import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.viel.aplayer.APlayerApplication
import com.viel.aplayer.abs.playback.AbsProgressConflictCoordinator
import com.viel.aplayer.application.library.player.PlayerLibraryMetadata
import com.viel.aplayer.application.library.player.PlayerLibraryReadModel
import com.viel.aplayer.application.library.player.PlayerBookmarkItem
import com.viel.aplayer.application.library.player.PlayerChapterItem
import com.viel.aplayer.application.library.player.PlayerRelatedData
import com.viel.aplayer.application.playback.PlayerPlaybackController
import com.viel.aplayer.data.AppSettingsRepository
import com.viel.aplayer.data.db.AudiobookSchema
import com.viel.aplayer.application.usecase.BuildPlaybackPlanUseCase
import com.viel.aplayer.event.AppEventSink
import com.viel.aplayer.event.feedback.FeedbackMessage
import com.viel.aplayer.event.feedback.FeedbackMessages
import com.viel.aplayer.media.PlaybackMediaId
import com.viel.aplayer.media.subtitle.SubtitleParser
import com.viel.aplayer.ui.player.components.bookmarks.BookmarkManager
import com.viel.aplayer.ui.settings.PlayerSettingsManager
import com.viel.aplayer.ui.settings.PlayerSettingsState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
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

    private var playbackController: PlayerPlaybackController? = null
    // Player Scene Read Model Access (Centralizes player-screen library reads through the stage-four scene module)
    // PlayerViewModel remains a UI coordinator while the module owns book metadata, subtitles, recovery progress, and availability gateway calls.
    private var playerLibraryReadModel: PlayerLibraryReadModel? = null
    private var settingsRepository: AppSettingsRepository? = null
    private var buildPlaybackPlanUseCase: BuildPlaybackPlanUseCase? = null
    private var absProgressConflictCoordinator: AbsProgressConflictCoordinator? = null
    // Application Event Sink Reference (Routes player UI feedback to the shared app-shell event stream)
    // The PlayerViewModel keeps no local one-shot event bus after the playback feedback boundary was centralized.
    private var appEventSink: AppEventSink? = null
    private var audioManager: AudioManager? = null
    
    private val _currentBookId = MutableStateFlow<String?>(null)
    val currentBookId: StateFlow<String?> = _currentBookId.asStateFlow()

    private val _currentSubtitles = MutableStateFlow<List<com.viel.aplayer.media.subtitle.SubtitleLine>>(emptyList())

    /**
     * Restored Playback Snapshot (Stores visual-only cold-start progress without media source loading)
     * Lets compact and full player surfaces render the last title, cover, and progress while keeping ExoPlayer untouched until the user explicitly starts playback.
     */
    private data class RestoredPlaybackSnapshot(
        val bookId: String,
        val positionMs: Long,
        val durationMs: Long
    )

    private val _restoredPlaybackSnapshot = MutableStateFlow<RestoredPlaybackSnapshot?>(null)

    // Subtitle async job (To prevent overlapping coroutine threads when changing tracks)
    private var subtitleLoadJob: kotlinx.coroutines.Job? = null

    private var bookmarkManager: BookmarkManager? = null
    private var playbackDelegate: MediaPlaybackDelegate? = null
    // Cached application context (To decouple Activity context bounds during initialization)
    private var appContext: Context? = null
    private val settingsManager: PlayerSettingsManager = PlayerSettingsManager(
        scope = viewModelScope,
        playbackController = { playbackController },
        audioManager = { audioManager },
        contextProvider = { appContext },
        // Player Settings Feedback Hook (Forwards control tips to the centralized app event sink)
        // Sleep timer and speed controls remain stateful helpers, while Toast rendering stays in the app shell.
        onShowToast = { message -> showToast(message) }
    )

    // =====================================================================
    // M-16 Bookmark Dialog State (Elevate bookmark dialog states to preserve user edit text during orientation changes)
    // Manages edit state in ViewModel scopes rather than transient composables.
    // =====================================================================

    /** Dialog visual states (To aggregate active edits and deletions options) */
    data class BookmarkDialogsState(
        val toDelete: PlayerBookmarkItem? = null,
        val toEdit: PlayerBookmarkItem? = null,
        val editTitle: String = ""
    )

    private val _bookmarkDialogs = MutableStateFlow(BookmarkDialogsState())
    /** Expose dialog flows (To stream dialog overlays state) */
    val bookmarkDialogs: StateFlow<BookmarkDialogsState> = _bookmarkDialogs.asStateFlow()

    /** Request bookmark deletion (To display confirmation modal dialog) */
    fun requestDeleteBookmark(b: PlayerBookmarkItem) {
        _bookmarkDialogs.update { it.copy(toDelete = b) }
    }

    /** Request bookmark modification (To display edit dialog autofilled with existing content) */
    fun requestEditBookmark(b: PlayerBookmarkItem) {
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

    // =====================================================================
    // ABS progress conflict states (To coordinate local-vs-server resume choices)
    // =====================================================================

    /**
     * ABS Progress Conflict Dialog State (UI-safe representation of competing playback checkpoints)
     * Keeps the dialog layer independent from ABS DTOs while preserving the exact positions needed for an explicit user choice.
     */
    data class AbsProgressConflictDialogState(
        val show: Boolean = false,
        val bookTitle: String = "",
        val localPositionMs: Long? = null,
        val remotePositionMs: Long = 0L,
        val localUpdatedAt: Long? = null,
        val remoteUpdatedAt: Long? = null,
        val localFinished: Boolean = false,
        val remoteFinished: Boolean = false
    )

    /**
     * Pending Playback Request (Stores the original load command while the user selects progress authority)
     * The request is replayed after a choice so the caller does not need to resend the same play action.
     */
    private data class PendingAbsProgressLoadRequest(
        val bookId: String,
        val playWhenReady: Boolean,
        val requestStartMs: Long
    )

    private val _absProgressConflictDialog = MutableStateFlow(AbsProgressConflictDialogState())
    val absProgressConflictDialogState: StateFlow<AbsProgressConflictDialogState> = _absProgressConflictDialog.asStateFlow()

    private var pendingAbsProgressConflict: AbsProgressConflictCoordinator.ProgressConflict? = null
    private var pendingAbsProgressLoadRequest: PendingAbsProgressLoadRequest? = null

    // Deprecated: _lastDominantColor is removed

    val settingsState: StateFlow<PlayerSettingsState> = settingsManager.settingsState
    val sleepTimerMillis: StateFlow<Long> = settingsManager.sleepTimerMillis

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val metadataState: StateFlow<BookMetadataState> = _currentBookId
        .flatMapLatest { id ->
            val readModel = playerLibraryReadModel ?: return@flatMapLatest flowOf(BookMetadataState())
            if (id == null) return@flatMapLatest flowOf(BookMetadataState())
 
            readModel.observeMetadata(id, _currentSubtitles)
                .map { metadata -> metadata.toBookMetadataState() }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), BookMetadataState())

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val _relatedData = metadataState
        .flatMapLatest { meta ->
            val id = meta.id
            if (id.isBlank() || id == "Unknown") {
                return@flatMapLatest flowOf(
                    PlayerRelatedData(
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
            playerLibraryReadModel?.relatedData(id, author, narrator)
                ?: flowOf(PlayerRelatedData(emptyList(), emptyList(), emptyList(), emptyList()))
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000),
            PlayerRelatedData(emptyList(), emptyList(), emptyList(), emptyList())
        )

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val playbackState: StateFlow<PlaybackState> = _currentBookId
        .flatMapLatest { id ->
            playbackController?.let { controller ->
                val engineState = combine(
                    controller.isPlaying,
                    controller.playbackState,
                    controller.currentPosition,
                    controller.duration,
                    controller.playbackSpeed
                ) { isPlaying, _, pos, dur, speed ->
                    PlaybackState(
                        isPlaying = isPlaying,
                        currentPosition = pos,
                        duration = dur,
                        playbackSpeed = speed,
                        playWhenReady = isPlaying
                    )
                }
                combine(
                    engineState,
                    controller.observeCurrentMediaItemId(),
                    _restoredPlaybackSnapshot
                ) { engine, mediaItemId, restored ->
                    // Cold-Start Preview State (Prefer restored progress only while no media item has been prepared)
                    // Once MediaController owns a MediaItem, engine state becomes authoritative and the preview snapshot is ignored.
                    val snapshot = restored
                    if (mediaItemId == null && snapshot != null && snapshot.bookId == id) {
                        engine.copy(
                            isPlaying = false,
                            playWhenReady = false,
                            currentPosition = snapshot.positionMs,
                            duration = snapshot.durationMs
                        )
                    } else {
                        engine
                    }
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
    val currentChapterState: StateFlow<PlayerChapterItem?> = combine(
        playbackState.map { it.currentPosition }.distinctUntilChanged(),
        metadataState.map { it.chapters }.distinctUntilChanged()
    ) { pos, chapters ->
        PlaybackStateMapper.currentChapter(chapters, pos)
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
            chapters = chapters,
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
        if (playbackController != null) return
        val appContext = context.applicationContext
        this.appContext = appContext
        val playerDependencies = APlayerApplication.getPlayerScreenDependencies(appContext)
        
        // Player Screen Dependency Resolution (Resolve only UI-facing player dependencies)
        // The media core continues to resolve granular runtime gateways separately, while this ViewModel and its UI helpers consume player scene interfaces.
        val readModel = playerDependencies.playerLibraryReadModel
        val bookmarkCommands = playerDependencies.playerBookmarkCommands
        playerLibraryReadModel = readModel
        settingsRepository = playerDependencies.settingsRepository
        absProgressConflictCoordinator = playerDependencies.absProgressConflictCoordinator
        appEventSink = playerDependencies.appEventSink
        buildPlaybackPlanUseCase = playerDependencies.buildPlaybackPlanUseCase
        playbackController = playerDependencies.playerPlaybackController

        audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager

        bookmarkManager = BookmarkManager(bookmarkCommands, viewModelScope)
        playbackDelegate = MediaPlaybackDelegate(
            playbackController = { playbackController },
            playerLibraryReadModel = readModel,
            scope = viewModelScope
        )

        observePlaybackController()
        observeSettings()
        restoreLastPlayedBookToCompactPlayer()
    }

    private fun restoreLastPlayedBookToCompactPlayer() {
        if (hasRestoredLastPlayedBook) return
        hasRestoredLastPlayedBook = true

        viewModelScope.launch {
            // Perform progress self-healing (To resolve progress drift before restoring compact player UI)
            playbackController?.performColdStartSelfHealing()

            // Query persistent playback checkpoint (To restore previous audiobook track coordinates)
            val lastProgress = playerLibraryReadModel?.getLastPlayedSnapshot() ?: return@launch
            if (_currentBookId.value == null) {
                restoreBookPreview(lastProgress.bookId, lastProgress.positionMs)
            }
        }
    }

    /**
     * Restore Book Preview (Loads only persisted UI metadata and progress snapshots)
     * Updates the active book ID and visual progress from Room without building a playback plan, preparing MediaController, or opening VFS media streams.
     */
    private suspend fun restoreBookPreview(bookId: String, positionMs: Long) {
        val preview = playerLibraryReadModel?.getBookPreview(bookId) ?: return
        subtitleLoadJob?.cancel()
        _currentBookId.value = bookId
        _currentSubtitles.value = emptyList()
        _restoredPlaybackSnapshot.value = RestoredPlaybackSnapshot(
            bookId = bookId,
            positionMs = positionMs,
            durationMs = preview.durationMs
        )
        // Compact Preview Visibility (Shows the last-played card without implying that media has been prepared)
        // The next explicit play action will still run the normal load pipeline and root preflight before touching ExoPlayer.
        settingsManager.setMiniPlayerHidden(false)
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

    private fun observePlaybackController() {
        val controller = playbackController ?: return

        viewModelScope.launch {
            controller.observeCurrentMediaItemId().collectLatest { mediaItemId ->
                if (mediaItemId != null) {
                    val mediaParts = PlaybackMediaId.parse(mediaItemId)
                    if (mediaParts != null) {
                        val bookId = mediaParts.bookId
                        // Parse composite media identifier (To extract track path and book ID tokens)
                        // Uses trailing colon markers to avoid misparsing multi-colon identifiers.
                        val bookFileId = mediaParts.fileId
                        _currentBookId.value = bookId
                        _restoredPlaybackSnapshot.value = null
                        settingsManager.setMiniPlayerHidden(false)

                        // Evict active track lyrics (To wipe previous subtitles lines when changing track selections)
                        subtitleLoadJob?.cancel()
                        _currentSubtitles.value = emptyList()

                        // Load track subtitles (To fetch text lines from filesystem asynchronously)
                        // Wraps jobs in ViewModel scope to avoid leak or concurrency risks.
                        subtitleLoadJob = viewModelScope.launch {
                            val externalSubs = kotlinx.coroutines.withContext(Dispatchers.IO) {
                                playerLibraryReadModel?.loadSubtitlesForBookFile(bookFileId) ?: emptyList()
                            }
                            // Subtitle State Budget Clamp (Keep external subtitle payloads bounded before publishing to Compose)
                            // This defensive clamp protects the player if future subtitle gateways bypass parser-side cue and text budgets.
                            _currentSubtitles.value = SubtitleParser.limitForPlayerState(externalSubs)
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
            controller.playbackState.collectLatest { state ->
                if (state == androidx.media3.common.Player.STATE_ENDED) {
                    delay(5000.milliseconds)
                    // Verify completion status (To ensure player is still idle before dismissing screen)
                    val currentState = controller.playbackState.value
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
        if (_currentBookId.value == id && isMediaSourceLoadedFor(id)) {
            // Restore active playback (To resume paused sessions without reloading files)
            if (playWhenReady && !playbackState.value.isPlaying) {
                play()
            }
            return
        }

        viewModelScope.launch {
            if (!prepareAbsProgressBeforeLoad(id, playWhenReady, loadBookRequestStart)) {
                return@launch
            }
            loadBookAfterProgressDecision(id, playWhenReady, loadBookRequestStart)
        }
    }

    /**
     * Prepare ABS Progress Before Loading (Checks remote progress before mutating the active player target)
     * Returns false when playback must wait for a user decision, preventing premature current-book switching on cancel.
     */
    private suspend fun prepareAbsProgressBeforeLoad(
        id: String,
        playWhenReady: Boolean,
        loadBookRequestStart: Long
    ): Boolean {
        val coordinator = absProgressConflictCoordinator ?: return true
        return when (val decision = coordinator.preparePlayback(id)) {
            AbsProgressConflictCoordinator.PlaybackDecision.ContinueLocal -> true
            is AbsProgressConflictCoordinator.PlaybackDecision.ApplyRemote -> {
                coordinator.acceptRemoteProgress(decision.conflict)
                true
            }
            is AbsProgressConflictCoordinator.PlaybackDecision.AskUser -> {
                pendingAbsProgressConflict = decision.conflict
                pendingAbsProgressLoadRequest = PendingAbsProgressLoadRequest(id, playWhenReady, loadBookRequestStart)
                _absProgressConflictDialog.value = decision.conflict.toDialogState()
                false
            }
        }
    }

    /**
     * Continue Load After Progress Decision (Builds and applies playback plans after conflict arbitration)
     * This keeps the normal local playback pipeline unchanged once the selected checkpoint has been persisted.
     */
    private suspend fun loadBookAfterProgressDecision(
        id: String,
        playWhenReady: Boolean,
        loadBookRequestStart: Long
    ) {
        // Evict previous track metadata (To prepare views for subsequent loading session)
        subtitleLoadJob?.cancel()
        _currentBookId.value = id
        _currentSubtitles.value = emptyList() // Reset subtitles of the previous book.
        settingsManager.setUndoSeekVisible(false)
        settingsManager.dismissChapterList()
        settingsManager.dismissBookmarkDialog()

        val playbackPlanStart = SystemClock.elapsedRealtime()
        // Playback Plan Use Case (Build playable track plan through the playback application seam)
        // PlayerViewModel no longer asks generic library queries for playback startup semantics.
        val plan = buildPlaybackPlanUseCase?.invoke(id)
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

    /**
     * Accept Local ABS Progress (Authorizes the device checkpoint for the pending playback request)
     * The selection marks only the current ABS playback window as locally authoritative before replaying the deferred load.
     */
    fun acceptLocalAbsProgressConflict() {
        val conflict = pendingAbsProgressConflict ?: return
        val request = pendingAbsProgressLoadRequest ?: return
        absProgressConflictCoordinator?.acceptLocalProgress(conflict.book.id)
        clearPendingAbsProgressConflict()
        viewModelScope.launch {
            loadBookAfterProgressDecision(request.bookId, request.playWhenReady, request.requestStartMs)
        }
    }

    /**
     * Accept Remote ABS Progress (Persists the server checkpoint and replays the pending playback request)
     * Failing to save the server checkpoint leaves the dialog open so the user can choose another authority instead of silently playing stale data.
     */
    fun acceptRemoteAbsProgressConflict() {
        val conflict = pendingAbsProgressConflict ?: return
        val request = pendingAbsProgressLoadRequest ?: return
        val coordinator = absProgressConflictCoordinator ?: return
        viewModelScope.launch {
            runCatching {
                coordinator.acceptRemoteProgress(conflict)
            }.onSuccess {
                clearPendingAbsProgressConflict()
                loadBookAfterProgressDecision(request.bookId, request.playWhenReady, request.requestStartMs)
            }.onFailure { error ->
                showToast(FeedbackMessages.playbackRemoteProgressSaveFailed(error.message))
            }
        }
    }

    /**
     * Dismiss ABS Progress Conflict (Cancels the deferred playback request)
     * Closing the dialog intentionally avoids starting playback so neither side is overwritten by an accidental default choice.
     */
    fun dismissAbsProgressConflictDialog() {
        clearPendingAbsProgressConflict()
    }

    private fun clearPendingAbsProgressConflict() {
        pendingAbsProgressConflict = null
        pendingAbsProgressLoadRequest = null
        _absProgressConflictDialog.value = AbsProgressConflictDialogState()
    }

    private fun AbsProgressConflictCoordinator.ProgressConflict.toDialogState(): AbsProgressConflictDialogState =
        AbsProgressConflictDialogState(
            show = true,
            bookTitle = book.title,
            localPositionMs = localProgress?.globalPositionMs,
            remotePositionMs = remoteProgress.globalPositionMs,
            localUpdatedAt = localProgress?.lastPlayedAt,
            remoteUpdatedAt = remoteProgress.lastPlayedAt,
            localFinished = book.readStatus == AudiobookSchema.ReadStatus.FINISHED,
            remoteFinished = remoteIsFinished == true
        )

    fun deleteBookmark(bookmark: PlayerBookmarkItem) = bookmarkManager?.deleteBookmark(bookmark)
    fun updateBookmark(bookmark: PlayerBookmarkItem, newTitle: String) = bookmarkManager?.updateBookmark(bookmark, newTitle)
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
            _restoredPlaybackSnapshot.value = null
            _currentSubtitles.value = emptyList()
            playbackController?.pause()
            settingsManager.setFullPlayerVisible(false)
            settingsManager.setMiniPlayerHidden(true)
        }
    }

    fun closeCurrentPlayback() {
        // Compact player can request a self-exit when its restored media is no longer available.
        _currentBookId.value?.let(::closePlayback)
    }

    fun togglePlayPause() = if (playbackState.value.isPlaying) pause() else play()

    /**
     * Play Current Book (Promotes preview-only restore into real media loading on demand)
     * If cold start restored only UI metadata, this routes through loadBook so root preflight and media preparation happen exactly once before playback.
     */
    fun play() {
        val id = _currentBookId.value
        if (id != null && !isMediaSourceLoadedFor(id)) {
            loadBook(id, playWhenReady = true)
        } else {
            playbackDelegate?.play()
        }
    }

    fun pause() = playbackDelegate?.pause()

    /**
     * Media Source Loaded Check (Distinguishes visual selection from an ExoPlayer-backed queue)
     * Reads the current MediaItem ID so duplicate-load protection does not suppress the first real play after cold-start preview restoration.
     */
    private fun isMediaSourceLoadedFor(bookId: String): Boolean {
        val mediaId = playbackController?.currentMediaItemId ?: return false
        return PlaybackMediaId.parse(mediaId)?.bookId == bookId
    }

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
    
    // Player Feedback Dispatch (Proxy player-screen tips to the application event sink)
    // This keeps local player controls from owning or exposing a parallel UI event stream.
    fun showToast(message: FeedbackMessage) {
        appEventSink?.showToast(message)
    }

    fun showChapterList() = settingsManager.showChapterList()
    fun dismissChapterList() = settingsManager.dismissChapterList()
    fun showBookmarkDialog() = settingsManager.showBookmarkDialog()
    fun dismissBookmarkDialog() = settingsManager.dismissBookmarkDialog()
    fun updateBookmarkTitle(title: String) = settingsManager.updateBookmarkTitle(title)
    fun saveBookmarkFromDialog() {
        addBookmark(settingsState.value.bookmarkTitle.ifBlank { defaultBookmarkTitle() })
        dismissBookmarkDialog()
        // Bookmark Dialog Feedback (Notify through the centralized app event sink)
        // The ViewModel reports the outcome while the app shell owns the Toast widget.
        showToast(FeedbackMessages.playbackBookmarkCreated())
    }

    private fun defaultBookmarkTitle(): String {
        // Default Bookmark Title Resource (Avoid persisting a hard-coded English fallback into user data)
        // Bookmark titles are visible in the saved bookmark list, so the default label is resolved from Android resources when the app context is ready.
        return appContext?.getString(com.viel.aplayer.R.string.bookmark_default_title)
            ?: error("PlayerViewModel must be initialized before resolving the default bookmark title.")
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
        // Current Playback Status Refresh (Updates persisted availability before emitting mini-player eligibility)
        // The player scene method name exposes that this read also refreshes file and book status rows.
        emit(playerLibraryReadModel?.refreshCurrentPlaybackAvailability(bookId) ?: false)
    }
    
    fun toggleProgressMode() {
        viewModelScope.launch {
            val nextMode = !settingsState.value.isChapterProgressMode
            settingsRepository?.updateChapterProgressMode(nextMode)
        }
    }
    fun onRouteChanged() = settingsManager.setMiniPlayerHidden(false)

    fun skipToNextChapter() = playbackDelegate?.skipToNextChapter(metadataState.value.chapters, playbackState.value.currentPosition)
    fun skipToPreviousChapter() = playbackDelegate?.skipToPreviousChapter(metadataState.value.chapters, playbackState.value.currentPosition)

    /**
     * Skip damaged tracks (To jump current playback session to next available track item)
     */
    fun skipToNextAvailableTrack(bookId: String, queueIndex: Int) {
        playbackController?.skipToNextAvailableTrack(bookId, queueIndex)
    }

    // Keep cover path update triggers (Notify downstream state listeners without calculating or saving dominant color fields)
    fun updateCoverPath(path: String?) {
        // Since we retrieve colors dynamically via ImageProcessor inside Composable, database-persisted backgroundColorArgb is fully deprecated.
        settingsManager.setSelectedContentTab(settingsState.value.selectedContentTab)
    }

    /**
     * Player Metadata UI Mapping (Adapt scene metadata into the existing UI state model)
     * Keeps the application player module independent from UI package types while preserving the current PlayerUiState data shape.
     */
    private fun PlayerLibraryMetadata.toBookMetadataState(): BookMetadataState {
        return BookMetadataState(
            id = id,
            title = title,
            author = author,
            narrator = narrator,
            coverPath = coverPath,
            thumbnailPath = thumbnailPath,
            coverLastUpdated = coverLastUpdated,
            chapters = chapters,
            bookmarks = bookmarks,
            subtitles = subtitles
        )
    }
}
