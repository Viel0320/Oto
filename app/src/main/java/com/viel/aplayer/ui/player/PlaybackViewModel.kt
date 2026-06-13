package com.viel.aplayer.ui.player

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.viel.aplayer.APlayerApplication
import com.viel.aplayer.application.library.player.PlayerChapterItem
import com.viel.aplayer.application.library.player.PlayerLibraryMetadata
import com.viel.aplayer.application.library.player.PlayerRelatedData
import com.viel.aplayer.application.usecase.ResolveProgressConflictUseCase
import com.viel.aplayer.event.feedback.FeedbackMessage
import com.viel.aplayer.event.feedback.FeedbackMessages
import com.viel.aplayer.media.PlaybackMediaId
import com.viel.aplayer.media.PlaybackSeekStepPolicy
import com.viel.aplayer.media.subtitle.SubtitleParser
import com.viel.aplayer.ui.settings.PlayerSettingsState
import kotlinx.coroutines.CoroutineScope
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
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

// Create PlaybackViewModel (Handles media playback and state updates to separate concerns)
// This ViewModel isolates all play, pause, seek, and conflict resolution states away from other UI concerns.
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PlaybackViewModel(
    application: Application,
    rawExternalScope: CoroutineScope? = null
) : AndroidViewModel(application) {

    // Shift scope to viewModelScope to prevent lifecycle leaks and screen rotation freezes
    // Fall back to viewModelScope if rawExternalScope is null to ensure coroutines are correctly managed.
    private val externalScope = rawExternalScope ?: viewModelScope

    companion object {
        private val PLAYBACK_SPEEDS = listOf(0.8f, 0.9f, 1.0f, 1.1f, 1.2f, 1.3f)
    }

    // Resolve dependencies (Fetches screen dependencies and managers from application presentation di)
    // Instantiates controllers, coordinators, and read models locally to control playback.
    // Title: Non-nullable dependency optimization (Remove safe calls as screen dependencies are guaranteed to be initialized and non-nullable)
    private val playerDependencies = APlayerApplication.getPlayerScreenDependencies(application)
    private val playbackController = playerDependencies.playerPlaybackController
    private val playerLibraryReadModel = playerDependencies.playerLibraryReadModel
    private val buildPlaybackPlanUseCase = playerDependencies.buildPlaybackPlanUseCase
    private val resolveProgressConflictUseCase = playerDependencies.resolveProgressConflictUseCase
    private val appEventSink = playerDependencies.appEventSink

    private val _currentBookId = MutableStateFlow<String?>(null)
    val currentBookId: StateFlow<String?> = _currentBookId.asStateFlow()

    private val _currentSubtitles = MutableStateFlow<List<com.viel.aplayer.media.subtitle.SubtitleLine>>(emptyList())
    private var subtitleLoadJob: kotlinx.coroutines.Job? = null

    // Track unavailable dialog state (Manages confirmation alert overlays for broken audio tracks)
    data class TrackUnavailableDialogState(
        val show: Boolean = false,
        val bookId: String = "",
        val queueIndex: Int = -1
    )

    private val _trackUnavailableDialog = MutableStateFlow(TrackUnavailableDialogState())
    val trackUnavailableDialogState: StateFlow<TrackUnavailableDialogState> = _trackUnavailableDialog.asStateFlow()

    // ABS progress conflict dialog state (Tracks local-vs-server resume choice states)
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

    private data class PendingAbsProgressLoadRequest(
        val bookId: String,
        val playWhenReady: Boolean,
        val requestStartMs: Long
    )

    private val _absProgressConflictDialog = MutableStateFlow(AbsProgressConflictDialogState())
    val absProgressConflictDialogState: StateFlow<AbsProgressConflictDialogState> = _absProgressConflictDialog.asStateFlow()

    private var pendingAbsProgressConflict: ResolveProgressConflictUseCase.ConflictSnapshot? = null
    private var pendingAbsProgressLoadRequest: PendingAbsProgressLoadRequest? = null

    private val playbackDelegate = MediaPlaybackDelegate(
        playbackController = { playbackController },
        playerLibraryReadModel = playerLibraryReadModel,
        scope = externalScope
    )

    private var onCoverUpdateCallback: ((String?) -> Unit)? = null

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val metadataState: StateFlow<BookMetadataState> = _currentBookId
        .flatMapLatest { id ->
            if (id == null) return@flatMapLatest flowOf(BookMetadataState())

            playerLibraryReadModel.observeMetadata(id, _currentSubtitles)
                .map { metadata -> metadata.toBookMetadataState() }
        }
        .stateIn(externalScope, SharingStarted.WhileSubscribed(5000), BookMetadataState())

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
            // Title: Remove redundant null-safety check (Invokes read model directly since it is non-nullable)
            playerLibraryReadModel.relatedData(id, author, narrator)
        }
        .stateIn(externalScope, SharingStarted.WhileSubscribed(5000),
            PlayerRelatedData(emptyList(), emptyList(), emptyList(), emptyList())
        )

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val playbackState: StateFlow<PlaybackState> = _currentBookId
        .flatMapLatest { _ ->
            // Title: Remove redundant flatMapLatest null-safety check (Combines playback controller states directly since playbackController is non-nullable)
            combine(
                playbackController.isPlaying,
                playbackController.playbackState,
                playbackController.currentPosition,
                playbackController.duration,
                playbackController.playbackSpeed
            ) { isPlaying, _, pos, dur, speed ->
                PlaybackState(
                    isPlaying = isPlaying,
                    currentPosition = pos,
                    duration = dur,
                    playbackSpeed = speed,
                    playWhenReady = isPlaying
                )
            }
        }
        .stateIn(externalScope, SharingStarted.WhileSubscribed(5000), PlaybackState())

    // Spaced progress view state (Exposes playback positions dynamically to prevent full-screen recompositions)
    data class PlaybackProgressViewState(
        val elapsedMs: Long = 0L,
        val durationMs: Long = 0L,
        val isChapterProgressMode: Boolean = false
    )

    private val _isChapterProgressMode = MutableStateFlow(false)

    fun setChapterProgressMode(enabled: Boolean) {
        _isChapterProgressMode.value = enabled
    }

    val playbackProgressState: StateFlow<PlaybackProgressViewState> = combine(
        playbackState.map { it.currentPosition }.distinctUntilChanged(),
        playbackState.map { it.duration }.distinctUntilChanged(),
        _isChapterProgressMode
    ) { pos, dur, mode ->
        PlaybackProgressViewState(pos, dur, mode)
    }.stateIn(externalScope, SharingStarted.WhileSubscribed(5000), PlaybackProgressViewState())

    val currentChapterState: StateFlow<PlayerChapterItem?> = combine(
        playbackState.map { it.currentPosition }.distinctUntilChanged(),
        metadataState.map { it.chapters }.distinctUntilChanged()
    ) { pos, chapters ->
        PlaybackStateMapper.currentChapter(chapters, pos)
    }
    .distinctUntilChanged()
    .stateIn(externalScope, SharingStarted.WhileSubscribed(5000), null)

    data class PlaybackControlState(
        val isPlaying: Boolean,
        val playbackSpeed: Float,
        val isSpeedManualMode: Boolean
    )

    val playbackControlState: StateFlow<PlaybackControlState> = playbackState
        .map {
            PlaybackControlState(it.isPlaying, it.playbackSpeed, it.isSpeedManualMode)
        }
        .distinctUntilChanged()
        .stateIn(externalScope, SharingStarted.WhileSubscribed(5000), PlaybackControlState(false, 1.0f, false))

    val currentPlaybackProgressPercent: StateFlow<Int> = playbackState
        .map { state ->
            PlaybackStateMapper.calculateProgressPercent(state.currentPosition, state.duration)
        }
        .distinctUntilChanged()
        .stateIn(externalScope, SharingStarted.WhileSubscribed(5000), 0)

    val miniPlayerProgress: StateFlow<Float> = combine(
        playbackState,
        metadataState.map { it.chapters }.distinctUntilChanged(),
        _isChapterProgressMode
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
    .stateIn(externalScope, SharingStarted.WhileSubscribed(5000), 0f)

    val uiState: StateFlow<PlayerUiState> = combine(
        metadataState,
        playbackControlState,
        _relatedData
    ) { metadata, control, related ->
        PlayerUiState(
            metadata = metadata,
            playback = PlaybackState(
                isPlaying = control.isPlaying,
                currentPosition = 0L,
                duration = 0L,
                playbackSpeed = control.playbackSpeed,
                playWhenReady = control.isPlaying
            ),
            settings = PlayerSettingsState(), // Keeps a default layout settings state in uiState
            relatedAuthorSections = related.authorSections,
            relatedNarratorSections = related.narratorSections,
            recentlyAddedBooks = related.recentlyAdded,
            heuristicRecommendedBooks = related.heuristicRecommended
        )
    }.stateIn(externalScope, SharingStarted.WhileSubscribed(5000), PlayerUiState())

    private var lastSeekPosition = 0L
    private var undoJob: kotlinx.coroutines.Job? = null
    private var hasRestoredLastPlayedBook = false

    private var _playbackSeekStepConfig = com.viel.aplayer.data.store.PlaybackSeekStepConfig()

    fun setPlaybackSeekStepConfig(config: com.viel.aplayer.data.store.PlaybackSeekStepConfig) {
        _playbackSeekStepConfig = config
    }

    private var onUndoSeekVisibilityChanged: ((Boolean) -> Unit)? = null
    private var onFullPlayerMinimized: (() -> Unit)? = null
    private var onMiniPlayerHiddenChanged: ((Boolean) -> Unit)? = null

    // Initialize (Observe playback updates, last played context and settings changes)
    // Runs on external scope to coordinate background observations.
    init {
        observePlaybackController()
        restoreLastPlayedBookToCompactPlayer()
        observeSettings()
    }

    private fun observeSettings() {
        val readModel = playerDependencies.settingsReadModel
        externalScope.launch {
            // Title: Observe settings via read model (Observes the settings read model flow)
            readModel.settingsFlow.collect { settings ->
                if (settings.isChapterProgressMode != _isChapterProgressMode.value) {
                    setChapterProgressMode(settings.isChapterProgressMode)
                }
                setPlaybackSeekStepConfig(settings.playbackSeekStepConfig)
            }
        }
    }

    private fun restoreLastPlayedBookToCompactPlayer() {
        if (hasRestoredLastPlayedBook) return
        hasRestoredLastPlayedBook = true

        externalScope.launch {
            // Title: Self-heal and restore last played progress (Invokes non-nullable playback controller and library read model)
            playbackController.performColdStartSelfHealing()
            val lastProgress = playerLibraryReadModel.getLastPlayedSnapshot() ?: return@launch
            if (_currentBookId.value == null) {
                loadBook(lastProgress.bookId, playWhenReady = false)
            }
        }
    }

    private fun observePlaybackController() {
        // Title: Initialize controller observer (Sets up listeners directly since controller is guaranteed to be non-nullable)
        val controller = playbackController

        externalScope.launch {
            controller.observeCurrentMediaItemId().collectLatest { mediaItemId ->
                if (mediaItemId != null) {
                    val mediaParts = PlaybackMediaId.parse(mediaItemId)
                    if (mediaParts != null) {
                        val bookId = mediaParts.bookId
                        val bookFileId = mediaParts.fileId
                        _currentBookId.value = bookId
                        onMiniPlayerHiddenChanged?.invoke(false)

                        subtitleLoadJob?.cancel()
                        _currentSubtitles.value = emptyList()

                        subtitleLoadJob = externalScope.launch {
                            val externalSubs = kotlinx.coroutines.withContext(Dispatchers.IO) {
                                // Title: Load subtitles directly (Accesses non-nullable library read model)
                                playerLibraryReadModel.loadSubtitlesForBookFile(bookFileId)
                            }
                            _currentSubtitles.value = SubtitleParser.limitForPlayerState(externalSubs)
                        }
                    } else {
                        subtitleLoadJob?.cancel()
                        _currentSubtitles.value = emptyList()
                    }
                } else {
                    subtitleLoadJob?.cancel()
                    _currentSubtitles.value = emptyList()
                }
            }
        }

        externalScope.launch {
            controller.playbackState.collectLatest { state ->
                if (state == androidx.media3.common.Player.STATE_ENDED) {
                    delay(5000.milliseconds)
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
        val loadBookRequestStart = SystemClock.elapsedRealtime()
        if (_currentBookId.value == id && isMediaSourceLoadedFor(id)) {
            if (playWhenReady && !playbackState.value.isPlaying) {
                play()
            }
            return
        }

        externalScope.launch {
            if (!prepareAbsProgressBeforeLoad(id, playWhenReady, loadBookRequestStart)) {
                return@launch
            }
            loadBookAfterProgressDecision(id, playWhenReady, loadBookRequestStart)
        }
    }

    private suspend fun prepareAbsProgressBeforeLoad(
        id: String,
        playWhenReady: Boolean,
        loadBookRequestStart: Long
    ): Boolean {
        // Title: Resolve ABS conflicts on prepare (Invokes non-nullable conflict resolution usecase)
        val useCase = resolveProgressConflictUseCase
        return when (val decision = useCase.preparePlayback(id)) {
            ResolveProgressConflictUseCase.PlaybackDecisionResult.ContinueLocal -> true
            is ResolveProgressConflictUseCase.PlaybackDecisionResult.ApplyRemote -> {
                useCase.acceptRemoteProgress(decision.conflict)
                true
            }
            is ResolveProgressConflictUseCase.PlaybackDecisionResult.AskUser -> {
                pendingAbsProgressConflict = decision.conflict
                pendingAbsProgressLoadRequest = PendingAbsProgressLoadRequest(id, playWhenReady, loadBookRequestStart)
                _absProgressConflictDialog.value = decision.conflict.toDialogState()
                false
            }
        }
    }

    private suspend fun loadBookAfterProgressDecision(
        id: String,
        playWhenReady: Boolean,
        loadBookRequestStart: Long
    ) {
        subtitleLoadJob?.cancel()
        _currentBookId.value = id
        _currentSubtitles.value = emptyList()
        onUndoSeekVisibilityChanged?.invoke(false)

        val playbackPlanStart = SystemClock.elapsedRealtime()
        // Title: Build playback plan directly (Invokes non-nullable use case)
        val plan = buildPlaybackPlanUseCase.invoke(id)
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
            playbackDelegate.loadBook(plan, playWhenReady) { onCoverUpdateCallback?.invoke(it) }
        } else {
            val totalCost = SystemClock.elapsedRealtime() - loadBookRequestStart
            com.viel.aplayer.logger.PlaybackTimingLogger.logLoadBookNoPlan(
                bookId = id,
                totalMs = totalCost
            )
        }
    }

    fun acceptLocalAbsProgressConflict() {
        val conflict = pendingAbsProgressConflict ?: return
        val request = pendingAbsProgressLoadRequest ?: return
        // Title: Accept local progress conflict option (Invokes non-nullable conflict resolution usecase)
        resolveProgressConflictUseCase.acceptLocalProgress(conflict.bookId)
        clearPendingAbsProgressConflict()
        externalScope.launch {
            loadBookAfterProgressDecision(request.bookId, request.playWhenReady, request.requestStartMs)
        }
    }

    fun acceptRemoteAbsProgressConflict() {
        val conflict = pendingAbsProgressConflict ?: return
        val request = pendingAbsProgressLoadRequest ?: return
        // Title: Accept remote progress conflict option (Invokes non-nullable conflict resolution usecase)
        val useCase = resolveProgressConflictUseCase
        externalScope.launch {
            runCatching {
                useCase.acceptRemoteProgress(conflict)
            }.onSuccess {
                clearPendingAbsProgressConflict()
                loadBookAfterProgressDecision(request.bookId, request.playWhenReady, request.requestStartMs)
            }.onFailure { error ->
                showToast(FeedbackMessages.playbackRemoteProgressSaveFailed(error.message))
            }
        }
    }

    fun dismissAbsProgressConflictDialog() {
        clearPendingAbsProgressConflict()
    }

    private fun clearPendingAbsProgressConflict() {
        pendingAbsProgressConflict = null
        pendingAbsProgressLoadRequest = null
        _absProgressConflictDialog.value = AbsProgressConflictDialogState()
    }

    private fun ResolveProgressConflictUseCase.ConflictSnapshot.toDialogState(): AbsProgressConflictDialogState =
        AbsProgressConflictDialogState(
            show = true,
            bookTitle = bookTitle,
            localPositionMs = localPositionMs,
            remotePositionMs = remotePositionMs,
            localUpdatedAt = localUpdatedAt,
            remoteUpdatedAt = remoteUpdatedAt,
            localFinished = localFinished,
            remoteFinished = remoteFinished
        )

    fun closePlayback(bookId: String) {
        if (_currentBookId.value == bookId) {
            subtitleLoadJob?.cancel()
            _currentBookId.value = null
            _currentSubtitles.value = emptyList()
            // Title: Pause playback on close (Invokes non-nullable controller directly)
            playbackController.pause()
            onFullPlayerMinimized?.invoke()
            onMiniPlayerHiddenChanged?.invoke(true)
        }
    }

    fun closeCurrentPlayback() {
        _currentBookId.value?.let(::closePlayback)
    }

    fun togglePlayPause() = if (playbackState.value.isPlaying) pause() else play()

    fun play() {
        val id = _currentBookId.value
        if (id != null && !isMediaSourceLoadedFor(id)) {
            loadBook(id, playWhenReady = true)
        } else {
            playbackDelegate.play()
        }
    }

    fun pause() = playbackDelegate.pause()

    private fun isMediaSourceLoadedFor(bookId: String): Boolean {
        // Title: Retrieve current media ID (Accesses non-nullable playback controller directly)
        val mediaId = playbackController.currentMediaItemId ?: return false
        return PlaybackMediaId.parse(mediaId)?.bookId == bookId
    }

    fun seekTo(positionMs: Long, allowUndo: Boolean = false) {
        if (allowUndo) {
            lastSeekPosition = playbackState.value.currentPosition
            onUndoSeekVisibilityChanged?.invoke(true)
            undoJob?.cancel()
            undoJob = externalScope.launch {
                delay(3000.milliseconds)
                onUndoSeekVisibilityChanged?.invoke(false)
            }
        } else {
            onUndoSeekVisibilityChanged?.invoke(false)
            undoJob?.cancel()
        }
        playbackDelegate.seekTo(positionMs)
    }

    fun undoSeek() {
        seekTo(lastSeekPosition, allowUndo = false)
        onUndoSeekVisibilityChanged?.invoke(false)
    }

    fun skipForward() {
        val state = playbackState.value
        seekTo(
            PlaybackSeekStepPolicy.forwardTarget(
                currentPositionMs = state.currentPosition,
                durationMs = state.duration,
                config = _playbackSeekStepConfig
            )
        )
    }

    fun skipBackward() {
        val state = playbackState.value
        seekTo(
            PlaybackSeekStepPolicy.backwardTarget(
                currentPositionMs = state.currentPosition,
                config = _playbackSeekStepConfig
            )
        )
    }

    fun setPlaybackSpeed(speed: Float) = playbackDelegate.setPlaybackSpeed(speed)

    fun cyclePlaybackSpeed() {
        val speed = playbackState.value.playbackSpeed
        val nextIndex = (PLAYBACK_SPEEDS.indexOf(speed).coerceAtLeast(0) + 1) % PLAYBACK_SPEEDS.size
        setPlaybackSpeed(PLAYBACK_SPEEDS[nextIndex])
    }

    fun resetPlaybackSpeed() = setPlaybackSpeed(1.0f)

    fun showToast(message: FeedbackMessage) {
        // Title: Display feedback toast (Invokes non-nullable event sink directly)
        appEventSink.showToast(message)
    }

    fun showTrackUnavailableDialog(bookId: String, queueIndex: Int) {
        _trackUnavailableDialog.value = TrackUnavailableDialogState(true, bookId, queueIndex)
    }

    fun dismissTrackUnavailableDialog() {
        _trackUnavailableDialog.value = TrackUnavailableDialogState()
    }

    fun currentBookAvailability(bookId: String): kotlinx.coroutines.flow.Flow<Boolean> = flow {
        if (bookId.isBlank()) {
            emit(true)
            return@flow
        }
        // Title: Check current book availability (Invokes non-nullable library read model directly)
        emit(playerLibraryReadModel.refreshCurrentPlaybackAvailability(bookId))
    }

    fun skipToNextChapter() = playbackDelegate.skipToNextChapter(metadataState.value.chapters, playbackState.value.currentPosition)
    fun skipToPreviousChapter() = playbackDelegate.skipToPreviousChapter(metadataState.value.chapters, playbackState.value.currentPosition)

    fun skipToNextAvailableTrack(bookId: String, queueIndex: Int) {
        // Title: Skip to next available track (Invokes non-nullable playback controller directly)
        playbackController.skipToNextAvailableTrack(bookId, queueIndex)
    }

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
