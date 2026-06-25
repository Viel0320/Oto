package com.viel.oto.ui.player

import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.viel.oto.application.library.player.PlayerChapterItem
import com.viel.oto.application.library.player.PlayerLibraryMetadata
import com.viel.oto.application.library.player.PlayerLibraryReadModel
import com.viel.oto.application.library.player.PlayerRelatedData
import com.viel.oto.application.library.settings.AppSettingsCommands
import com.viel.oto.application.library.settings.AppSettingsReadModel
import com.viel.oto.application.playback.PlayerPlaybackController
import com.viel.oto.application.usecase.BuildPlaybackPlanUseCase
import com.viel.oto.application.usecase.ResolveProgressConflictUseCase
import com.viel.oto.event.AppEventSink
import com.viel.oto.event.feedback.LibraryAccessFeedbackFacts
import com.viel.oto.event.feedback.PlaybackControlFeedbackFacts
import com.viel.oto.event.feedback.RecoveryFeedbackFacts
import com.viel.oto.media.PlaybackMediaId
import com.viel.oto.media.PlaybackSeekStepPolicy
import com.viel.oto.media.subtitle.SubtitleParser
import com.viel.oto.ui.settings.PlayerSettingsState
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

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PlaybackViewModel(
    private val playbackController: PlayerPlaybackController,
    private val playerLibraryReadModel: PlayerLibraryReadModel,
    private val buildPlaybackPlanUseCase: BuildPlaybackPlanUseCase,
    private val resolveProgressConflictUseCase: ResolveProgressConflictUseCase,
    private val appEventSink: AppEventSink,
    private val settingsReadModel: AppSettingsReadModel,
    private val settingsCommands: AppSettingsCommands,
    rawExternalScope: CoroutineScope? = null
) : ViewModel() {

    private val externalScope = rawExternalScope ?: viewModelScope

    companion object {
        private val PLAYBACK_SPEEDS = listOf(0.8f, 0.9f, 1.0f, 1.1f, 1.2f, 1.3f)
        private const val MAX_SUBTITLE_SYNC_OFFSET_MS = 30_000L
    }

    private val _currentBookId = MutableStateFlow<String?>(null)
    val currentBookId: StateFlow<String?> = _currentBookId.asStateFlow()

    private val _currentSubtitles = MutableStateFlow<List<com.viel.oto.media.subtitle.SubtitleLine>>(emptyList())
    private var subtitleLoadJob: kotlinx.coroutines.Job? = null

    /**
     * App-wide subtitle cue offset mirrored from playback settings.
     *
     * The player scene keeps a StateFlow for fast UI reads, while mutation flows through
     * AppSettingsCommands so subtitle alignment survives book changes and process restarts.
     */
    private val _subtitleSyncOffsetMs = MutableStateFlow(0L)
    val subtitleSyncOffsetMs: StateFlow<Long> = _subtitleSyncOffsetMs.asStateFlow()

    data class TrackUnavailableDialogState(
        val show: Boolean = false,
        val bookId: String = "",
        val queueIndex: Int = -1,
        val bookTitle: String = ""
    )

    private val _trackUnavailableDialog = MutableStateFlow(TrackUnavailableDialogState())
    val trackUnavailableDialogState: StateFlow<TrackUnavailableDialogState> = _trackUnavailableDialog.asStateFlow()

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

    /**
     * Prepared media metadata for the currently selected playback target.
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val metadataState: StateFlow<BookMetadataState> = _currentBookId
        .flatMapLatest { id ->
            if (id == null) return@flatMapLatest flowOf(BookMetadataState())

            playerLibraryReadModel.observeMetadata(id, _currentSubtitles)
                .map { metadata -> metadata.toBookMetadataState() }
        }
        .stateIn(externalScope, SharingStarted.WhileSubscribed(5000), BookMetadataState())

    /**
     * Prepared-media recommendation rows.
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val _relatedData = combine(
        metadataState,
        _currentBookId
    ) { meta, preparedBookId -> meta to preparedBookId }
        .flatMapLatest { (meta, preparedBookId) ->
            val id = meta.id
            if (id.isBlank() || id == "Unknown" || preparedBookId != id) {
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
            playerLibraryReadModel.relatedData(id, author, narrator)
        }
        .stateIn(externalScope, SharingStarted.WhileSubscribed(5000),
            PlayerRelatedData(emptyList(), emptyList(), emptyList(), emptyList())
        )

    /**
     * High-frequency playback coordinates for progress-only UI consumers.
     *
     * Position, buffer, and duration are isolated from the full PlaybackState object so 500ms
     * progress ticks do not force parent player surfaces to rebuild low-frequency control state.
     * The controller already exposes StateFlow sources, so they are combined directly instead of
     * applying a redundant distinctUntilChanged operator that StateFlow ignores.
     */
    private val playbackPositionState: StateFlow<PlaybackProgressViewState> = combine(
        playbackController.currentPosition,
        playbackController.bufferedPosition,
        playbackController.duration
    ) { pos, bufferedPos, dur ->
        PlaybackProgressViewState(
            elapsedMs = pos,
            bufferedMs = bufferedPos.coerceIn(pos, dur.coerceAtLeast(pos)),
            durationMs = dur
        )
    }.stateIn(externalScope, SharingStarted.WhileSubscribed(5000), PlaybackProgressViewState())

    private val controllerPlaybackState = combine(
        playbackController.isPlaying,
        playbackPositionState,
        playbackController.playbackSpeed
    ) { isPlaying, progress, speed ->
        PlaybackState(
            isPlaying = isPlaying,
            currentPosition = progress.elapsedMs,
            bufferedPosition = progress.bufferedMs,
            duration = progress.durationMs,
            playbackSpeed = speed,
            playWhenReady = isPlaying
        )
    }

    /**
     * Full playback projection retained for callers that need the aggregate engine snapshot.
     *
     * Progress-rendering Compose surfaces should use playbackProgressState instead, and control
     * hosts should use playbackControlState, so this aggregate flow only runs when explicitly needed.
     */
    val playbackState: StateFlow<PlaybackState> = controllerPlaybackState
        .stateIn(externalScope, SharingStarted.WhileSubscribed(5000), PlaybackState())

    private val _isChapterProgressMode = MutableStateFlow(false)

    fun setChapterProgressMode(enabled: Boolean) {
        _isChapterProgressMode.value = enabled
    }

    val playbackProgressState: StateFlow<PlaybackProgressViewState> = combine(
        playbackPositionState,
        _isChapterProgressMode
    ) { progress, mode ->
        progress.copy(isChapterProgressMode = mode)
    }.stateIn(externalScope, SharingStarted.WhileSubscribed(5000), PlaybackProgressViewState())

    val currentChapterState: StateFlow<PlayerChapterItem?> = combine(
        playbackPositionState.map { it.elapsedMs }.distinctUntilChanged(),
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

    /**
     * Low-frequency transport state for parent surfaces and button rows.
     *
     * This intentionally avoids playbackPositionState so overlay hosts only recompose on actual
     * control changes such as play/pause or speed updates, not on progress polling ticks.
     * The controller StateFlow inputs are already equality-aware, so only the combined projection
     * needs distinctUntilChanged after the data class is created.
     */
    val playbackControlState: StateFlow<PlaybackControlState> = combine(
        playbackController.isPlaying,
        playbackController.playbackSpeed
    ) { isPlaying, speed ->
        PlaybackControlState(isPlaying, speed, false)
    }
        .distinctUntilChanged()
        .stateIn(externalScope, SharingStarted.WhileSubscribed(5000), PlaybackControlState(false, 1.0f, false))

    val currentPlaybackProgressPercent: StateFlow<Int> = playbackPositionState
        .map { state ->
            PlaybackStateMapper.calculateProgressPercent(state.elapsedMs, state.durationMs)
        }
        .distinctUntilChanged()
        .stateIn(externalScope, SharingStarted.WhileSubscribed(5000), 0)

    val miniPlayerProgress: StateFlow<Float> = combine(
        playbackPositionState,
        metadataState.map { it.chapters }.distinctUntilChanged(),
        _isChapterProgressMode
    ) { progress, chapters, isChapterMode ->
        PlaybackStateMapper.calculateMiniPlayerProgress(
            currentPosition = progress.elapsedMs,
            duration = progress.durationMs,
            chapters = chapters,
            isChapterMode = isChapterMode,
            fallbackProgress = if (progress.durationMs > 0L) {
                progress.elapsedMs.toFloat() / progress.durationMs.toFloat()
            } else {
                0f
            }
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
            settings = PlayerSettingsState(),
            relatedAuthorSections = related.authorSections,
            relatedNarratorSections = related.narratorSections,
            recentlyAddedBooks = related.recentlyAdded,
            heuristicRecommendedBooks = related.heuristicRecommended
        )
    }.stateIn(externalScope, SharingStarted.WhileSubscribed(5000), PlayerUiState())

    private var lastSeekPosition = 0L
    private var undoJob: kotlinx.coroutines.Job? = null
    private var hasRestoredLastPlayedBook = false

    private var _playbackSeekStepConfig = com.viel.oto.shared.settings.PlaybackSeekStepConfig()

    fun setPlaybackSeekStepConfig(config: com.viel.oto.shared.settings.PlaybackSeekStepConfig) {
        _playbackSeekStepConfig = config
    }

    var onUndoSeekVisibilityChanged: ((Boolean) -> Unit)? = null
    private var onFullPlayerMinimized: (() -> Unit)? = null
    private var onMiniPlayerHiddenChanged: ((Boolean) -> Unit)? = null

    init {
        observePlaybackController()
        restoreLastPlayedBookToCompactPlayer()
        observeSettings()
    }

    private fun observeSettings() {
        val readModel = settingsReadModel
        externalScope.launch {
            readModel.settingsFlow.collect { settings ->
                if (settings.isChapterProgressMode != _isChapterProgressMode.value) {
                    setChapterProgressMode(settings.isChapterProgressMode)
                }
                setPlaybackSeekStepConfig(settings.playbackSeekStepConfig)
                _subtitleSyncOffsetMs.value = settings.subtitleSyncOffsetMs
            }
        }
    }

    /**
     * Reads the latest playback engine snapshot without keeping full PlaybackState subscribed.
     *
     * Commands, sleep timers, and bookmark creation need an immediate value even when no UI surface
     * is collecting the aggregate state; reading the controller StateFlows preserves those command
     * semantics while letting Compose subscribe only to narrower projections.
     */
    fun currentPlaybackSnapshot(): PlaybackState {
        val currentPosition = playbackController.currentPosition.value
        val duration = playbackController.duration.value
        val isPlaying = playbackController.isPlaying.value
        return PlaybackState(
            isPlaying = isPlaying,
            playWhenReady = isPlaying,
            currentPosition = currentPosition,
            bufferedPosition = playbackController.bufferedPosition.value
                .coerceIn(currentPosition, duration.coerceAtLeast(currentPosition)),
            duration = duration,
            playbackSpeed = playbackController.playbackSpeed.value,
            isSpeedManualMode = false
        )
    }

    /**
     * Cold-start media recovery for the last played book.
     *
     * Startup restores the persisted progress target by building the real playback plan with
     * playWhenReady=false, so the controller owns an actual media source before the user presses
     * play while still avoiding automatic audio output.
     */
    private fun restoreLastPlayedBookToCompactPlayer() {
        if (hasRestoredLastPlayedBook) return
        hasRestoredLastPlayedBook = true

        externalScope.launch {
            playbackController.performColdStartSelfHealing()
            val lastProgress = playerLibraryReadModel.getLastPlayedSnapshot() ?: return@launch
            if (_currentBookId.value == null) {
                loadBook(lastProgress.bookId, playWhenReady = false)
            }
        }
    }

    private fun observePlaybackController() {
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
            if (playWhenReady && !currentPlaybackSnapshot().isPlaying) {
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
        val plan = buildPlaybackPlanUseCase.invoke(id)
        val playbackPlanCost = SystemClock.elapsedRealtime() - playbackPlanStart
        com.viel.oto.logger.PlaybackTimingLogger.logPlaybackPlanBuild(
            bookId = id,
            costMs = playbackPlanCost,
            planReady = plan != null,
            playWhenReady = playWhenReady
        )
        if (plan != null) {
            val totalCost = SystemClock.elapsedRealtime() - loadBookRequestStart
            com.viel.oto.logger.PlaybackTimingLogger.logLoadBookReady(
                bookId = id,
                totalMs = totalCost,
                fileCount = plan.files.size,
                startPosition = plan.startGlobalPositionMs
            )
            playbackDelegate.loadBook(plan, playWhenReady) { onCoverUpdateCallback?.invoke(it) }
        } else {
            val totalCost = SystemClock.elapsedRealtime() - loadBookRequestStart
            com.viel.oto.logger.PlaybackTimingLogger.logLoadBookNoPlan(
                bookId = id,
                totalMs = totalCost
            )
        }
    }

    fun acceptLocalAbsProgressConflict() {
        val conflict = pendingAbsProgressConflict ?: return
        val request = pendingAbsProgressLoadRequest ?: return
        resolveProgressConflictUseCase.acceptLocalProgress(conflict.bookId)
        clearPendingAbsProgressConflict()
        externalScope.launch {
            loadBookAfterProgressDecision(request.bookId, request.playWhenReady, request.requestStartMs)
        }
    }

    fun acceptRemoteAbsProgressConflict() {
        val conflict = pendingAbsProgressConflict ?: return
        val request = pendingAbsProgressLoadRequest ?: return
        val useCase = resolveProgressConflictUseCase
        externalScope.launch {
            runCatching {
                useCase.acceptRemoteProgress(conflict)
            }.onSuccess {
                clearPendingAbsProgressConflict()
                loadBookAfterProgressDecision(request.bookId, request.playWhenReady, request.requestStartMs)
            }.onFailure { error ->
                appEventSink.emitFeedback(
                    LibraryAccessFeedbackFacts.remoteProgressSaveFailed(request.bookId, error.message)
                )
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
        val closesPreparedBook = _currentBookId.value == bookId
        if (closesPreparedBook) {
            subtitleLoadJob?.cancel()
            _currentBookId.value = null
            _currentSubtitles.value = emptyList()
            playbackController.pause()
            onFullPlayerMinimized?.invoke()
            onMiniPlayerHiddenChanged?.invoke(true)
        }
    }

    fun closeCurrentPlayback() {
        _currentBookId.value?.let(::closePlayback)
    }

    fun togglePlayPause() = if (currentPlaybackSnapshot().isPlaying) pause() else play()

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
        val mediaId = playbackController.currentMediaItemId ?: return false
        return PlaybackMediaId.parse(mediaId)?.bookId == bookId
    }

    fun seekTo(positionMs: Long, allowUndo: Boolean = false) {
        if (allowUndo) {
            lastSeekPosition = currentPlaybackSnapshot().currentPosition
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

    /**
     * Adjusts the global subtitle cue matching offset.
     *
     * The clamp keeps accidental repeated taps within a small synchronization window while preserving
     * one persisted playback preference for future books and app launches.
     */
    fun adjustSubtitleSyncOffset(deltaMs: Long) {
        val nextOffset = (_subtitleSyncOffsetMs.value + deltaMs)
            .coerceIn(-MAX_SUBTITLE_SYNC_OFFSET_MS, MAX_SUBTITLE_SYNC_OFFSET_MS)
        _subtitleSyncOffsetMs.value = nextOffset
        externalScope.launch {
            settingsCommands.updateSubtitleSyncOffsetMs(nextOffset)
        }
    }

    /**
     * Restores the global subtitle cue offset to parsed file timestamps.
     */
    fun resetSubtitleSyncOffset() {
        _subtitleSyncOffsetMs.value = 0L
        externalScope.launch {
            settingsCommands.updateSubtitleSyncOffsetMs(0L)
        }
    }

    fun undoSeek() {
        seekTo(lastSeekPosition, allowUndo = false)
        onUndoSeekVisibilityChanged?.invoke(false)
    }

    fun skipForward() {
        val state = currentPlaybackSnapshot()
        seekTo(
            PlaybackSeekStepPolicy.forwardTarget(
                currentPositionMs = state.currentPosition,
                durationMs = state.duration,
                config = _playbackSeekStepConfig
            )
        )
    }

    fun skipBackward() {
        val state = currentPlaybackSnapshot()
        seekTo(
            PlaybackSeekStepPolicy.backwardTarget(
                currentPositionMs = state.currentPosition,
                config = _playbackSeekStepConfig
            )
        )
    }

    fun setPlaybackSpeed(speed: Float) {
        playbackDelegate.setPlaybackSpeed(speed)
        appEventSink.emitFeedback(PlaybackControlFeedbackFacts.playbackSpeedChanged(speed))
    }

    fun cyclePlaybackSpeed() {
        val speed = playbackController.playbackSpeed.value
        val nextIndex = (PLAYBACK_SPEEDS.indexOf(speed).coerceAtLeast(0) + 1) % PLAYBACK_SPEEDS.size
        setPlaybackSpeed(PLAYBACK_SPEEDS[nextIndex])
    }

    fun resetPlaybackSpeed() = setPlaybackSpeed(1.0f)

    /**
     * Publishes the recovery feedback fact for a tapped missing chapter.
     *
     * The chapter row raises the intent with the book id; this command owner classifies it as a
     * playback-content recovery outcome before the app shell renders it.
     */
    fun reportMissingChapterFile(bookId: String) {
        appEventSink.emitFeedback(RecoveryFeedbackFacts.chapterPhysicalFileMissing(bookId))
    }

    fun showTrackUnavailableDialog(bookId: String, queueIndex: Int, bookTitle: String?) {
        _trackUnavailableDialog.value = TrackUnavailableDialogState(
            show = true,
            bookId = bookId,
            queueIndex = queueIndex,
            bookTitle = bookTitle.orEmpty()
        )
    }

    fun dismissTrackUnavailableDialog() {
        _trackUnavailableDialog.value = TrackUnavailableDialogState()
    }

    fun currentBookAvailability(bookId: String): kotlinx.coroutines.flow.Flow<Boolean> = flow {
        if (bookId.isBlank()) {
            emit(true)
            return@flow
        }
        emit(playerLibraryReadModel.refreshCurrentPlaybackAvailability(bookId))
    }

    fun skipToNextChapter() = playbackDelegate.skipToNextChapter(
        metadataState.value.chapters,
        currentPlaybackSnapshot().currentPosition
    )

    fun skipToPreviousChapter() = playbackDelegate.skipToPreviousChapter(
        metadataState.value.chapters,
        currentPlaybackSnapshot().currentPosition
    )

    fun skipToNextAvailableTrack(bookId: String, queueIndex: Int) {
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
