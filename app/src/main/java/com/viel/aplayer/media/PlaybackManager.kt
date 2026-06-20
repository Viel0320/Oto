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
class PlaybackManager private constructor(context: Context) {

    private val appContext = context.applicationContext
    // Coroutine Exception Handler (Intercept uncaught coroutine errors to prevent process crashes)
    // Captures failures arising from broken local tracks, network disconnects, or storage issues.
    private val exceptionHandler = CoroutineExceptionHandler { _, exception ->
        PlaybackWorkflowLogger.error("playbackManager coroutine failure", exception)
    }
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob() + exceptionHandler)

    // Playback Runtime Dependency View (Resolve only the media-core dependencies used by this manager)
    // This keeps PlaybackManager from depending on the full application container while preserving the existing singleton lifecycle.
    private val playbackDependencies = com.viel.aplayer.APlayerApplication.getPlaybackRuntimeDependencies(appContext)
    // Playback Catalog Gateway (Resolve only book metadata and track inventory for runtime coordination)
    // PlaybackManager does not mutate bookmarks, chapters, or text metadata, so the catalog seam keeps the runtime dependency narrow.
    private val bookCatalogGateway = playbackDependencies.bookCatalogGateway
    private val progressGateway = playbackDependencies.progressGateway
    // Playback Availability Gateway (Owns status-writing recovery checks for failed or missing tracks)
    // PlaybackManager uses this seam for failover discovery instead of binding availability refreshes to progress persistence.
    private val bookAvailabilityGateway = playbackDependencies.bookAvailabilityGateway
    private val absPlaybackSessionSyncer = playbackDependencies.absPlaybackSessionSyncer
    private val playbackSourcePreflight = playbackDependencies.playbackSourcePreflight
    // Playback Domain Event Sink (Publishes media-core outcomes without constructing UI events)
    // The application bridge translates these playback facts into Toasts or dialogs outside the media package.
    private val playbackEventSink = playbackDependencies.playbackDomainEventSink

    // Configuration Repository Reference (Access runtime settings such as cleartext HTTP configurations)
    private val settingsRepository = AppSettingsRepository.getInstance(appContext)
    // Automatic Rewind Coordinator Reference (Initialize manager to coordinate pause-rewind triggers)
    private val autoRewindManager = AutoRewindManager.getInstance(appContext)

    // High-Frequency Poller (Isolate database writes from high-frequency UI updates to follow single-responsibility rules)
    private val progressSyncTracker: ProgressSyncTracker

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var mediaController: MediaController? = null
    // Autoplay Intention Buffer (Cache play requests issued while MediaController is establishing its connection)
    // Prevents loss of command triggers initiated during cold startup before setup completes.
    private var pendingPlayWhenReady = false

    // Exposed Flows for UI to observe
    private val _playbackState = MutableStateFlow(Player.STATE_IDLE)
    val playbackState = _playbackState.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying = _isPlaying.asStateFlow()

    private val _currentMediaItem = MutableStateFlow<MediaItem?>(null)
    val currentMediaItem = _currentMediaItem.asStateFlow()

    // External Subtitle Dispatcher (Expose a hot stream of external subtitles to the subscriber UI)
    // Media3/ExoPlayer manages internal tag parses natively; hence, only physical sidecar files are tracked here.
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

    // State Transition Cache (Store playing state of previous frame to identify transition from active to paused)
    // Used to accurately capture events such as headset unplug or manual pauses to trigger auto-rewind.
    private var lastIsPlaying = false

    /** Book Identifier Snapshot (Retrieve active plan book ID thread-safely without suspending) */
    val currentPlayingBookId: String?
        get() = currentPlan?.bookId

    // Retained Player Listener (Define persistent member variable to avoid inner anonymous classes)
    // Ensures listener is successfully released on teardown to prevent context leakage.
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
        // Coordinate Poller (Instantiate tracker and bind lambdas to update position StateFlows directly)
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
        // Custom Session Interceptor (Decode service-originated playback commands)
        // Converts legacy media-session callbacks into playback-domain events instead of UI events.
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
                            // Damaged Track Intercept (Extract payload and publish a playback-domain recovery event)
                            // The app-level bridge uses the feedback presentation to choose one renderer.
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
            // Async Future Awaiter (Await token retrieval inside IO thread to satisfy warning check rules)
            // ListenableFuture.get() remains a blocking call; wrapping in withContext(Dispatchers.IO) conforms to coroutine safety.
            scope.launch {
                try {
                    val controller = withContext(Dispatchers.IO) { controllerFuture?.get() }
                    mediaController = controller
                    controller?.let { conn ->
                        setupController(conn)
                        // Cold-start restore may set the playback plan before MediaController connects; apply it once ready.
                        currentPlan?.let { setBookPlaybackPlan(it) }
                        // Widget Update Bypass (Widget features are deprecated; widget refresh triggers are omitted)
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }, ContextCompat.getMainExecutor(appContext))
    }

    private fun setupController(controller: MediaController) {
        // Baseline Cache Snapshot (Synchronize local states with active MediaController attributes)
        lastIsPlaying = controller.isPlaying
        _isPlaying.value = controller.isPlaying
        _playbackState.value = controller.playbackState
        _currentMediaItem.value = controller.currentMediaItem
        progressSyncTracker.updateProgress(controller)
        _playbackSpeed.value = controller.playbackParameters.speed

        // Shared Listener Binding (Attach retained player listener to avoid anonymous leak routes)
        controller.addListener(playerListener)
    }

    /**
     * Commit Playback Offset (Flush memory cached positions down to database storage)
     *
     * Retained to satisfy contract calls from settings controls and AutoRewindManager.
     */
    fun saveProgress() {
        progressSyncTracker.saveProgress()
    }

    /**
     * Apply Playback Configuration (Initialize playback engine with files, artwork, and offsets)
     *
     * Executed on Main thread within coroutine. Maps plan values and routes control logic.
     */
    fun setBookPlaybackPlan(plan: BookPlaybackPlan, playWhenReady: Boolean = false) {
        scope.launch {
            // Performance Monitor Anchor (Capture start timestamp to profile pre-apply latency)
            val setPlanStart = SystemClock.elapsedRealtime()
            // Retrieve Configuration (Fetch settings from settingsRepository settingsFlow)
            val settingsReadStart = SystemClock.elapsedRealtime()
            val settings = settingsRepository.settingsFlow.first()
            val settingsReadCost = SystemClock.elapsedRealtime() - settingsReadStart
            // Bypass Interrupted Self-Healing (Bypassed since cold start restoration has been applied)
            val finalPlan = plan

            // DB-Only Root Preflight (Reject media source creation when Room already marks the root inactive)
            // This check deliberately avoids file opens, SAF traversal, WebDAV requests, and ABS API calls before MediaController receives media items.
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

            // UI Thread Transition (Switch thread context back to Main to handle UI update constraints)
            withContext(Dispatchers.Main) {
                // Bypassing Lock Activation (Prevent erroneous auto-rewind triggers during track reloading)
                autoRewindManager.ignoreNextAutoRewind = true

                val previousBookId = this@PlaybackManager.currentPlan?.bookId
                val previousAbsSessionSnapshot = if (previousBookId != null && previousBookId != finalPlan.bookId) {
                    // ABS Switch Snapshot (Capture old-book coordinates before currentPlan points at the next book)
                    // The remote close path must use the outgoing plan and controller position, not the replacement plan that is about to load.
                    captureAbsSessionSnapshot()
                } else {
                    null
                }
                this@PlaybackManager.currentPlan = finalPlan
                this@PlaybackManager.pendingPlayWhenReady = playWhenReady
                if (previousBookId != finalPlan.bookId) {
                    scheduleAbsSessionTransition(previousAbsSessionSnapshot, finalPlan.bookId)
                }

                // State Prefetching (Publish initial positions instantly to prevent UI frame flickers)
                val totalDur = finalPlan.files.sumOf { it.durationMs }
                _currentPosition.value = finalPlan.startGlobalPositionMs
                _bufferedPosition.value = finalPlan.startGlobalPositionMs
                _duration.value = totalDur
                // Widget Refresh Bypass (Omit Widget refresh since widget is deprecated)

                // Playback Plan Application (Runs only after root lifecycle and unsafe-network preflight pass)
                // The previous placeholder HTTP check is replaced by PlaybackSourcePreflight, which inspects the persisted remote root endpoints behind VFS media URIs.
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
        // Phase Latency Tracking (Split operation into translation phase and controller update phase)
        val applyPlanStart = SystemClock.elapsedRealtime()
        // Plan Mapper (Delegate compile logic to PlaybackPlanBuilder to detach transport translation details)
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
                // Autoplay Command Flush (Consume autoplay command post-prepare to prevent asynchronous loss)
                pendingPlayWhenReady = false
                controller.play()
                // Consume Command Logging (Mark autoplay execution for latency analysis)
                com.viel.aplayer.logger.PlaybackTimingLogger.logAutoplayConsumed(plan.bookId)
            }
            // Subtitle Delay Ingestion (ViewModel handles lazy load of subtitle tracks upon track transitions)
            _currentSubtitles.value = emptyList()
            // Loading a book should create/update BookProgress immediately, even before playback events fire.
            progressSyncTracker.persistProgress(plan.bookId, fileIndex, positionInFile)
        } ?: run {
            val totalApplyCost = SystemClock.elapsedRealtime() - applyPlanStart
            com.viel.aplayer.logger.PlaybackTimingLogger.logApplyPlanSkipped(
                bookId = plan.bookId,
                totalMs = totalApplyCost
            )
        }
    }

    // Commands
    fun play() {
        scope.launch {
            // Local Play First (Resume audible playback before remote ABS session bookkeeping)
            // The background session open is idempotent, so resume after a pause-close can recover server tracking without delaying controls.
            mediaController?.play()
            currentPlan?.bookId?.let { bookId -> scheduleAbsSessionOpen(bookId) }
        }
    }

    fun pause() {
        scope.launch {
            // Local Pause First (Apply the user-visible control before remote ABS teardown)
            // Slow ABS close requests must not keep audio playing after the user presses pause on weak networks.
            mediaController?.pause()
            scheduleAbsSessionClose(captureAbsSessionSnapshot(), skipIfPlaybackResumed = true)
        }
    }


    /**
     * Logarithmic Volume Scalar (Get or set player engine volume without affecting system settings)
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
        // Main-Thread Seek Enforcement (Redirect request to application main thread to safely interact with MediaController)
        scope.launch {
            val controller = mediaController ?: return@launch
            val mediaParts = PlaybackMediaId.parse(controller.currentMediaItem?.mediaId) ?: return@launch
            val bookId = mediaParts.bookId
            // Track Query Ingestion (Query track configuration details through the catalog gateway)
            // Global seek mapping requires only stored track inventory and should not inherit bookmark or metadata writes.
            val files = bookCatalogGateway.getFilesForBookSync(bookId)
            if (files.isNotEmpty()) {
                val totalDuration = files.sumOf { it.durationMs }
                val targetGlobal = globalPositionMs.coerceIn(0L, totalDuration.coerceAtLeast(0L))
                val (fileIndex, positionInFile) = PositionMapper.globalToFilePosition(targetGlobal, files)
                // Seek Position Mapping (Locate track index and track offset matching global timestamp)
                controller.seekTo(fileIndex, positionInFile)
                controller.play()
                _currentPosition.value = targetGlobal
                _bufferedPosition.value = targetGlobal
                _duration.value = totalDuration
                // Delay Subtitle Loading (Defer parsing to prevent sync disk reads during seek movements)
                _currentSubtitles.value = emptyList()
                // User-initiated seek must persist immediately so BookProgress is not dependent on later callbacks.
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
        // Listener Teardown (Deregister member listener explicitly from MediaController to prevent memory leaks)
        mediaController?.removeListener(playerListener)
        controllerFuture?.let {
            MediaController.releaseFuture(it)
            controllerFuture = null
        }
        mediaController = null
        // Singleton Cleanup Lock (Perform cleanup within companion lock to maintain concurrency safety)
        synchronized(Companion) {
            INSTANCE = null
        }
    }

    /**
     * Retrieve Controller Instance (Get connected MediaController asynchronously using coroutine wait logic)
     *
     * Awaits future completion using Dispatchers.IO to safely resolve blocking calls without halting UI.
     */
    suspend fun getController(): MediaController? {
        val controller = mediaController
        if (controller != null) return controller

        val future = controllerFuture ?: return null
        return try {
            withContext(Dispatchers.IO) {
                // Future Blocking Guard (Resolve .get() on IO thread pool to prevent blocking caller thread)
                future.get()
            }.also { mediaController = it }
        } catch (e: Exception) {
            PlaybackWorkflowLogger.error("playbackManager getController failed", e)
            null
        }
    }

    /**
     * Get Active Book ID (Resolve ID of the currently playing audiobook)
     *
     * Checks both the active MediaItem configuration and the loaded fallback plan.
     */
    fun getCurrentBookId(): String? {
        // Safe ID Parsing (Read StateFlow memory snapshot to avoid concurrent thread access conflicts)
        val mediaId = currentMediaItem.value?.mediaId ?: currentPlan?.bookId
        return PlaybackMediaId.parse(mediaId)?.bookId ?: mediaId
    }

    /**
     * Terminate Playback Session (Suspend active playback, clear queue, and reset flows)
     *
     * Block-waits controller readiness to ensure stop command hits ExoPlayer even from background threads.
     */
    suspend fun stopPlayback() {
        val controller = getController()
        val snapshot = withContext(Dispatchers.Main) {
            // Prevent Redundant Actions (Toggle ignoreNextAutoRewind flag prior to stop to suppress post-pause rewinds)
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
            // ABS Book Switch Ordering (Close the previous remote session before opening the next one)
            // The local player can move to the new book immediately, while remote session bookkeeping stays ordered in the background.
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
            // Pause Close Latest-Only Guard (Drops stale background close work after the same book resumes)
            // A quick pause/play sequence should keep the newly active local playback from being invalidated by the older pause teardown.
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
            // ABS Close Progress Flush (Persist the controller snapshot before sending remote close)
            // Closing uses the same fresh local checkpoint that the UI just observed, avoiding stale 10-second polling data.
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
     * Failover Next Track (Skip forward to the next available playable audio track)
     *
     * @param bookId The unique identifier of the book.
     * @param queueIndex The track index that failed to load.
     */
    fun skipToNextAvailableTrack(bookId: String, queueIndex: Int) {
        scope.launch {
            // Failover Track Search With Status Refresh (Finds the next eligible track while persisting checked availability)
            // PlaybackManager depends on the explicit refresh-named gateway method because failover inspection writes READY/MISSING states.
            val next = bookAvailabilityGateway.findNextAvailablePlaybackFileAndRefreshStatus(bookId, queueIndex)
            if (next != null) {
                val (nextIndex, _) = next
                com.viel.aplayer.logger.PlaybackFailureLogger.logSelfHealSuccess(nextIndex)
                mediaController?.let { controller ->
                    // Execute Redirect (Command controller to transition to the recovered track index)
                    controller.seekTo(nextIndex, 0L)
                    controller.prepare()
                    controller.play()
                }
            } else {
                PlaybackWorkflowLogger.warn("playbackManager no next available track: bookId=$bookId, queueIndex=$queueIndex")
                // Failover Exhausted Event (Notify the app layer that recovery cannot continue)
                // The media manager reports the domain outcome while UI rendering remains outside playback-core.
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
     * ABS Session Snapshot (Captures local playback coordinates before the active plan mutates)
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
