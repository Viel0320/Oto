package com.viel.aplayer.media

import android.content.ComponentName
import android.content.Context
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.viel.aplayer.data.AppSettingsRepository
import com.viel.aplayer.data.entity.BookFileEntity
import com.viel.aplayer.data.entity.BookProgressEntity
import com.viel.aplayer.logger.PlaybackWorkflowLogger
import com.viel.aplayer.media.service.PlaybackService
import com.viel.aplayer.media.subtitle.SubtitleLine
import com.viel.aplayer.timeline.PositionMapper

import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(UnstableApi::class)
class PlaybackManager internal constructor(
    context: Context,
    playbackDependencies: com.viel.aplayer.di.dependencies.PlaybackRuntimeDependencies,
    settingsRepository: AppSettingsRepository,
    autoRewindManager: AutoRewindManager
) {

    private val appContext = context.applicationContext
    private val exceptionHandler = CoroutineExceptionHandler { _, exception ->
        PlaybackWorkflowLogger.error("playbackManager coroutine failure", exception)
    }
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob() + exceptionHandler)

    private val bookCatalogGateway = playbackDependencies.bookCatalogGateway
    private val progressGateway = playbackDependencies.progressGateway
    private val bookAvailabilityGateway = playbackDependencies.bookAvailabilityGateway
    private val absPlaybackSessionSyncer = playbackDependencies.absPlaybackSessionSyncer
    private val playbackSourcePreflight = playbackDependencies.playbackSourcePreflight
    private val playbackEventSink = playbackDependencies.playbackDomainEventSink

    private val settingsRepository = settingsRepository
    private val autoRewindManager = autoRewindManager

    private val progressSyncTracker: ProgressSyncTracker

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var mediaController: MediaController? = null
    private var pendingPlayWhenReady = false

    private val _playbackState = MutableStateFlow(Player.STATE_IDLE)
    val playbackState = _playbackState.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying = _isPlaying.asStateFlow()

    private val _currentMediaItem = MutableStateFlow<MediaItem?>(null)
    val currentMediaItem = _currentMediaItem.asStateFlow()

    private val _currentSubtitles = MutableStateFlow<List<SubtitleLine>>(emptyList())
    val currentSubtitles = _currentSubtitles.asStateFlow()

    private val _currentPosition = MutableStateFlow(0L)
    val currentPosition = _currentPosition.asStateFlow()

    private val _bufferedPosition = MutableStateFlow(0L)
    val bufferedPosition = _bufferedPosition.asStateFlow()

    private val _duration = MutableStateFlow(0L)
    val duration = _duration.asStateFlow()

    private val _playbackSpeed = MutableStateFlow(1.0f)
    val playbackSpeed = _playbackSpeed.asStateFlow()

    private var currentPlan: BookPlaybackPlan? = null

    private var lastIsPlaying = false

    /** Retrieve active plan book ID thread-safely without suspending. */
    val currentPlayingBookId: String?
        get() = currentPlan?.bookId

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            val controller = mediaController ?: return
            val wasPlaying = lastIsPlaying
            lastIsPlaying = isPlaying
            _isPlaying.value = isPlaying
            progressSyncTracker.saveProgress()

            scope.launch {
                try {
                    settingsRepository.updateLastPlaybackInterrupted(isPlaying)
                } catch (e: Exception) {
                    PlaybackWorkflowLogger.error("playbackManager updateLastPlaybackInterrupted failed", e)
                }
            }

            if (wasPlaying && !isPlaying) {
                autoRewindManager.handlePause(
                    controller = controller,
                    currentPlan = currentPlan,
                    scope = scope,
                    onProgressUpdated = { conn -> progressSyncTracker.updateProgress(conn) },
                    onSaveProgress = { progressSyncTracker.saveProgress() }
                )
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            _playbackState.value = playbackState
            val controller = mediaController
            if (playbackState == Player.STATE_READY && controller != null) {
                progressSyncTracker.updateProgress(controller)
            }
            progressSyncTracker.saveProgress()
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            _currentMediaItem.value = mediaItem
            _currentSubtitles.value = emptyList()
            val controller = mediaController
            if (controller != null) {
                progressSyncTracker.updateProgress(controller)
            }
            progressSyncTracker.saveProgress()
        }

        override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
            val controller = mediaController
            if (controller != null) {
                progressSyncTracker.updateProgress(controller)
            }
        }

        override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
            _playbackSpeed.value = playbackParameters.speed
        }
    }

    init {
        progressSyncTracker = ProgressSyncTracker(
            context = appContext,
            bookCatalogGateway = bookCatalogGateway,
            progressGateway = progressGateway,
            absPlaybackSessionSyncer = absPlaybackSessionSyncer,
            scope = scope,
            getController = { mediaController },
            getCurrentPlan = { currentPlan },
            onProgressUpdated = { positionMs, durationMs, bufferedPositionMs ->
                _currentPosition.value = positionMs
                _duration.value = durationMs
                _bufferedPosition.value = bufferedPositionMs
            }
        )
        initializeController()
        progressSyncTracker.startPolling()
    }

    private fun initializeController() {
        val sessionToken = SessionToken(appContext, ComponentName(appContext, PlaybackService::class.java))
        controllerFuture = MediaController.Builder(appContext, sessionToken)
            .setListener(object : MediaController.Listener {
                override fun onCustomCommand(
                    controller: MediaController,
                    command: androidx.media3.session.SessionCommand,
                    args: android.os.Bundle
                ): ListenableFuture<androidx.media3.session.SessionResult> {
                    when (command.customAction) {
                        "EVENT_SKIP_SILENCE" -> {
                        }
                        "EVENT_TRACK_UNAVAILABLE" -> {
                            val bookId = args.getString("bookId") ?: ""
                            val queueIndex = args.getInt("queueIndex", -1)
                            val bookTitle = args.getString("bookTitle")
                                ?: currentPlan?.takeIf { it.bookId == bookId }?.title
                            playbackEventSink.emit(PlaybackDomainEvent.TrackUnavailable(bookId, queueIndex, bookTitle))
                        }
                    }
                    return com.google.common.util.concurrent.Futures.immediateFuture(
                        androidx.media3.session.SessionResult(androidx.media3.session.SessionResult.RESULT_SUCCESS)
                    )
                }
            })
            .buildAsync()

        controllerFuture?.addListener({
            scope.launch {
                try {
                    val controller = withContext(Dispatchers.IO) { controllerFuture?.get() }
                    mediaController = controller
                    controller?.let { conn ->
                        setupController(conn)
                        currentPlan?.let { setBookPlaybackPlan(it) }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }, ContextCompat.getMainExecutor(appContext))
    }

    private fun setupController(controller: MediaController) {
        lastIsPlaying = controller.isPlaying
        _isPlaying.value = controller.isPlaying
        _playbackState.value = controller.playbackState
        _currentMediaItem.value = controller.currentMediaItem
        progressSyncTracker.updateProgress(controller)
        _playbackSpeed.value = controller.playbackParameters.speed

        controller.addListener(playerListener)
    }

    /**
     * Flush memory cached positions down to database storage.
     *
     * Retained to satisfy contract calls from settings controls and AutoRewindManager.
     */
    fun saveProgress() {
        progressSyncTracker.saveProgress()
    }

    /**
     * Initialize playback engine with files, artwork, and offsets.
     *
     * Executed on Main thread within coroutine. Maps plan values and routes control logic.
     */
    fun setBookPlaybackPlan(plan: BookPlaybackPlan, playWhenReady: Boolean = false) {
        scope.launch {
            val setPlanStart = SystemClock.elapsedRealtime()
            val settingsReadStart = SystemClock.elapsedRealtime()
            val settings = settingsRepository.settingsFlow.first()
            val settingsReadCost = SystemClock.elapsedRealtime() - settingsReadStart
            val finalPlan = plan

            when (val preflight = playbackSourcePreflight.check(finalPlan, settings)) {
                PlaybackSourcePreflightResult.Available -> Unit
                PlaybackSourcePreflightResult.CleartextHttpBlocked -> {
                    playbackEventSink.emit(PlaybackDomainEvent.CleartextPlaybackBlocked(bookTitle = plan.title))
                    PlaybackWorkflowLogger.warn("playbackManager cleartext preflight blocked: bookId=${plan.bookId}")
                    return@launch
                }
                is PlaybackSourcePreflightResult.Blocked -> {
                    playbackEventSink.emit(
                        PlaybackDomainEvent.SourcePreflightBlocked(
                            reason = preflight.reason,
                            rootName = preflight.rootName,
                            bookTitle = plan.title
                        )
                    )
                    PlaybackWorkflowLogger.warn("playbackManager source preflight blocked: bookId=${plan.bookId}, reason=${preflight.reason}")
                    return@launch
                }
            }

            com.viel.aplayer.logger.PlaybackTimingLogger.logSetPlanEntry(
                bookId = plan.bookId,
                settingsReadMs = settingsReadCost,
                originalStart = plan.startGlobalPositionMs,
                finalStart = finalPlan.startGlobalPositionMs,
                fileCount = finalPlan.files.size,
                playWhenReady = playWhenReady
            )

            withContext(Dispatchers.Main) {
                autoRewindManager.ignoreNextAutoRewind = true

                val previousBookId = this@PlaybackManager.currentPlan?.bookId
                val previousAbsSessionSnapshot = if (previousBookId != null && previousBookId != finalPlan.bookId) {
                    captureAbsSessionSnapshot()
                } else {
                    null
                }
                this@PlaybackManager.currentPlan = finalPlan
                this@PlaybackManager.pendingPlayWhenReady = playWhenReady
                if (previousBookId != finalPlan.bookId) {
                    scheduleAbsSessionTransition(previousAbsSessionSnapshot, finalPlan.bookId)
                }

                val totalDur = finalPlan.files.sumOf { it.durationMs }
                _currentPosition.value = finalPlan.startGlobalPositionMs
                _bufferedPosition.value = finalPlan.startGlobalPositionMs
                _duration.value = totalDur

                val preApplyCost = SystemClock.elapsedRealtime() - setPlanStart
                com.viel.aplayer.logger.PlaybackTimingLogger.logPreApplyCost(
                    bookId = plan.bookId,
                    preApplyCostMs = preApplyCost
                )
                executeOnMain { applyPlaybackPlan(finalPlan) }
            }
        }
    }

    private fun applyPlaybackPlan(plan: BookPlaybackPlan) {
        val applyPlanStart = SystemClock.elapsedRealtime()
        val mediaItems = PlaybackPlanBuilder.buildMediaItems(plan)
        val mediaItemsBuildCost = SystemClock.elapsedRealtime() - applyPlanStart
        val (fileIndex, positionInFile) = PositionMapper.globalToFilePosition(plan.startGlobalPositionMs, plan.files)

        mediaController?.let { controller ->
            val controllerDispatchStart = SystemClock.elapsedRealtime()
            controller.setMediaItems(mediaItems, fileIndex, positionInFile)
            controller.prepare()
            val controllerDispatchCost = SystemClock.elapsedRealtime() - controllerDispatchStart
            val totalApplyCost = SystemClock.elapsedRealtime() - applyPlanStart
            com.viel.aplayer.logger.PlaybackTimingLogger.logApplyPlan(
                bookId = plan.bookId,
                mediaItemsBuildMs = mediaItemsBuildCost,
                controllerDispatchMs = controllerDispatchCost,
                totalMs = totalApplyCost,
                fileCount = mediaItems.size,
                fileIndex = fileIndex,
                positionInFile = positionInFile
            )
            if (pendingPlayWhenReady) {
                pendingPlayWhenReady = false
                controller.play()
                com.viel.aplayer.logger.PlaybackTimingLogger.logAutoplayConsumed(plan.bookId)
            }
            _currentSubtitles.value = emptyList()
            progressSyncTracker.persistProgress(plan.bookId, fileIndex, positionInFile)
        } ?: run {
            val totalApplyCost = SystemClock.elapsedRealtime() - applyPlanStart
            com.viel.aplayer.logger.PlaybackTimingLogger.logApplyPlanSkipped(
                bookId = plan.bookId,
                totalMs = totalApplyCost
            )
        }
    }

    fun play() {
        scope.launch {
            mediaController?.play()
            currentPlan?.bookId?.let { bookId -> scheduleAbsSessionOpen(bookId) }
        }
    }

    fun pause() {
        scope.launch {
            mediaController?.pause()
            scheduleAbsSessionClose(captureAbsSessionSnapshot(), skipIfPlaybackResumed = true)
        }
    }


    /**
     * Get or set player engine volume without affecting system settings.
     *
     * Used in volume fades to gradually quiet the playback smoothly.
     */
    var playerVolume: Float
        get() = mediaController?.volume ?: 1.0f
        set(value) {
            executeOnMain {
                mediaController?.volume = value.coerceIn(0.0f, 1.0f)
            }
        }

    fun seekTo(globalPositionMs: Long) {
        scope.launch {
            val controller = mediaController ?: return@launch
            val mediaParts = PlaybackMediaId.parse(controller.currentMediaItem?.mediaId) ?: return@launch
            val bookId = mediaParts.bookId
            val files = bookCatalogGateway.getFilesForBookSync(bookId)
            if (files.isNotEmpty()) {
                val totalDuration = files.sumOf { it.durationMs }
                val targetGlobal = globalPositionMs.coerceIn(0L, totalDuration.coerceAtLeast(0L))
                val (fileIndex, positionInFile) = PositionMapper.globalToFilePosition(targetGlobal, files)
                controller.seekTo(fileIndex, positionInFile)
                controller.play()
                _currentPosition.value = targetGlobal
                _bufferedPosition.value = targetGlobal
                _duration.value = totalDuration
                _currentSubtitles.value = emptyList()
                progressSyncTracker.persistProgress(bookId, fileIndex, positionInFile)
            }
        }
    }

    fun setPlaybackSpeed(speed: Float) {
        executeOnMain {
            mediaController?.setPlaybackSpeed(speed)
        }
    }

    fun release() {
        progressSyncTracker.stopPolling()
        scope.cancel()
        mediaController?.removeListener(playerListener)
        controllerFuture?.let {
            MediaController.releaseFuture(it)
            controllerFuture = null
        }
        mediaController = null
    }

    /**
     * Get connected MediaController asynchronously using coroutine wait logic.
     *
     * Awaits future completion using Dispatchers.IO to safely resolve blocking calls without halting UI.
     */
    suspend fun getController(): MediaController? {
        val controller = mediaController
        if (controller != null) return controller

        val future = controllerFuture ?: return null
        return try {
            withContext(Dispatchers.IO) {
                future.get()
            }.also { mediaController = it }
        } catch (e: Exception) {
            PlaybackWorkflowLogger.error("playbackManager getController failed", e)
            null
        }
    }

    /**
     * Resolve ID of the currently playing audiobook.
     *
     * Checks both the active MediaItem configuration and the loaded fallback plan.
     */
    fun getCurrentBookId(): String? {
        val mediaId = currentMediaItem.value?.mediaId ?: currentPlan?.bookId
        return PlaybackMediaId.parse(mediaId)?.bookId ?: mediaId
    }

    /**
     * Suspend active playback, clear queue, and reset flows.
     *
     * Block-waits controller readiness to ensure stop command hits ExoPlayer even from background threads.
     */
    suspend fun stopPlayback() {
        val controller = getController()
        val snapshot = withContext(Dispatchers.Main) {
            autoRewindManager.ignoreNextAutoRewind = true
            val capturedSnapshot = if (controller != null) {
                controller.pause()
                val snapshot = captureAbsSessionSnapshot()
                controller.stop()
                controller.clearMediaItems()
                snapshot
            } else {
                captureAbsSessionSnapshot()
            }
            currentPlan = null
            _currentMediaItem.value = null
            _currentPosition.value = 0L
            _bufferedPosition.value = 0L
            _duration.value = 0L
            _isPlaying.value = false
            _playbackState.value = Player.STATE_IDLE
            capturedSnapshot
        }
        scheduleAbsSessionClose(snapshot)
    }

    private fun scheduleAbsSessionTransition(previousSnapshot: AbsSessionSnapshot?, nextBookId: String) {
        scope.launch(Dispatchers.IO) {
            closeAbsSessionIfNeeded(previousSnapshot)
            openAbsSessionIfNeeded(nextBookId)
        }
    }

    private fun scheduleAbsSessionOpen(bookId: String) {
        scope.launch(Dispatchers.IO) {
            openAbsSessionIfNeeded(bookId)
        }
    }

    private fun scheduleAbsSessionClose(
        snapshot: AbsSessionSnapshot?,
        skipIfPlaybackResumed: Boolean = false
    ) {
        scope.launch(Dispatchers.IO) {
            if (skipIfPlaybackResumed && isSnapshotPlayingAgain(snapshot)) return@launch
            closeAbsSessionIfNeeded(snapshot)
        }
    }

    private suspend fun isSnapshotPlayingAgain(snapshot: AbsSessionSnapshot?): Boolean {
        if (snapshot == null) return false
        return withContext(Dispatchers.Main) {
            currentPlan?.bookId == snapshot.bookId && mediaController?.isPlaying == true
        }
    }

    private suspend fun openAbsSessionIfNeeded(bookId: String) {
        val book = bookCatalogGateway.getBookById(bookId) ?: return
        if (book.sourceType != com.viel.aplayer.data.db.AudiobookSchema.SourceType.ABS_REMOTE) return
        val remoteItemId = book.id.substringAfter(":item:", missingDelimiterValue = "")
        if (remoteItemId.isBlank()) return
        absPlaybackSessionSyncer.openSession(book, remoteItemId)
    }

    private suspend fun closeAbsSessionIfNeeded(snapshot: AbsSessionSnapshot?) {
        val sessionSnapshot = snapshot ?: return
        val book = bookCatalogGateway.getBookById(sessionSnapshot.bookId) ?: return
        if (book.sourceType != com.viel.aplayer.data.db.AudiobookSchema.SourceType.ABS_REMOTE) return
        val progress = resolveCloseProgress(sessionSnapshot)
        absPlaybackSessionSyncer.closeSession(book, progress, sessionSnapshot.durationMs)
    }

    private suspend fun resolveCloseProgress(snapshot: AbsSessionSnapshot): BookProgressEntity? {
        val capturedProgress = snapshot.toProgressEntity()
        if (capturedProgress != null) {
            progressGateway.saveProgress(capturedProgress)
            return capturedProgress
        }
        return progressGateway.getProgressForBookSync(snapshot.bookId)
    }

    private fun captureAbsSessionSnapshot(plan: BookPlaybackPlan? = currentPlan): AbsSessionSnapshot? {
        val activePlan = plan ?: return null
        val controller = mediaController
        val hasControllerPosition = controller?.currentMediaItem != null && activePlan.files.isNotEmpty()
        val fileIndex = if (hasControllerPosition) {
            controller.currentMediaItemIndex.coerceIn(0, activePlan.files.lastIndex)
        } else {
            null
        }
        val positionInFileMs = if (hasControllerPosition) {
            controller.currentPosition.coerceAtLeast(0L)
        } else {
            null
        }
        return AbsSessionSnapshot(
            bookId = activePlan.bookId,
            files = activePlan.files,
            fileIndex = fileIndex,
            positionInFileMs = positionInFileMs,
            capturedAtMs = System.currentTimeMillis()
        )
    }

    /**
     * Skip forward to the next available playable audio track.
     *
     * @param bookId The unique identifier of the book.
     * @param queueIndex The track index that failed to load.
     */
    fun skipToNextAvailableTrack(bookId: String, queueIndex: Int) {
        scope.launch {
            val next = bookAvailabilityGateway.findNextAvailablePlaybackFileAndRefreshStatus(bookId, queueIndex)
            if (next != null) {
                val (nextIndex, _) = next
                com.viel.aplayer.logger.PlaybackFailureLogger.logSelfHealSuccess(nextIndex)
                mediaController?.let { controller ->
                    controller.seekTo(nextIndex, 0L)
                    controller.prepare()
                    controller.play()
                }
            } else {
                PlaybackWorkflowLogger.warn("playbackManager no next available track: bookId=$bookId, queueIndex=$queueIndex")
                val bookTitle = currentPlan?.takeIf { it.bookId == bookId }?.title
                playbackEventSink.emit(PlaybackDomainEvent.NoAvailableTrackAfterFailure(bookTitle))
            }
        }
    }

    private fun executeOnMain(action: () -> Unit) {
        if (Thread.currentThread() == android.os.Looper.getMainLooper().thread) {
            action()
        } else {
            scope.launch(Dispatchers.Main) { action() }
        }
    }

    /**
     * Captures local playback coordinates before the active plan mutates.
     * Background remote close calls use this immutable snapshot so book switches and stop commands cannot read a newer plan with an older position.
     */
    private data class AbsSessionSnapshot(
        val bookId: String,
        val files: List<BookFileEntity>,
        val fileIndex: Int?,
        val positionInFileMs: Long?,
        val capturedAtMs: Long
    ) {
        val durationMs: Long = files.sumOf { it.durationMs }

        fun toProgressEntity(): BookProgressEntity? {
            if (fileIndex == null || positionInFileMs == null || files.isEmpty()) return null
            val safeFileIndex = fileIndex.coerceIn(0, files.lastIndex)
            val safePositionInFile = positionInFileMs.coerceAtLeast(0L)
            val globalPosition = PositionMapper.fileToGlobalPosition(safeFileIndex, safePositionInFile, files)
                .coerceIn(0L, durationMs.coerceAtLeast(0L))
            return BookProgressEntity(
                bookId = bookId,
                globalPositionMs = globalPosition,
                bookFileId = files.getOrNull(safeFileIndex)?.id,
                currentFileIndex = safeFileIndex,
                positionInFileMs = safePositionInFile,
                lastPlayedAt = capturedAtMs
            )
        }
    }
}
