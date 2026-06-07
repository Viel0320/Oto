package com.viel.aplayer.media.service

// Widget State Sync Imports (Import the helper classes, Glance widget manager, and coroutine utilities)
// Resolves UI and database mapping dependencies needed to update widget data store asynchronously.
import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.viel.aplayer.APlayerApplication
import com.viel.aplayer.MainActivity
import com.viel.aplayer.R
import com.viel.aplayer.data.AppSettingsRepository
import com.viel.aplayer.data.entity.BookFileEntity
import com.viel.aplayer.data.gateway.BookAvailabilityGateway
import com.viel.aplayer.data.gateway.BookQueryGateway
import com.viel.aplayer.data.gateway.ProgressGateway
import com.viel.aplayer.logger.PlaybackWorkflowLogger
import com.viel.aplayer.media.NotificationProgressPlayer
import com.viel.aplayer.media.PlaybackDomainEvent
import com.viel.aplayer.media.PlaybackDomainEventSink
import com.viel.aplayer.media.PlaybackMediaId
import com.viel.aplayer.media.PlaybackPlanGateway
import com.viel.aplayer.media.PositionMapper
import com.viel.aplayer.media.session.PlaybackSessionErrorDecision
import com.viel.aplayer.media.session.PlaybackSessionState
import com.viel.aplayer.widget.PlayerWidget
import com.viel.aplayer.widget.PlayerWidgetStateHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.milliseconds

/**
 * Core Foreground Playback Service (Manages life cycle of media playbacks and acts as a container for MediaSession instances)
 * Architectural refinement isolates concerns across specialized components:
 * 1. Customized ExoPlayer configuration is delegated entirely to [ExoPlayerFactory].
 * 2. System audio focus and notification ducking dynamics are isolated in [PlaybackAudioFocusManager].
 * 3. Media accessibility verification and error handling are managed by [PlaybackFailureHandler].
 * Under this design, the playback service serves as a lightweight harness wrapper, reducing visual coupling.
 */
@UnstableApi
class PlaybackService : MediaSessionService() {
    // Debounced Widget Update Job (Retains the active coroutine job reference to discard overlapping refresh tasks)
    private var widgetUpdateJob: Job? = null

    private var mediaSession: MediaSession? = null
    // Isolated Media Notification Session (Constructs a separate session to prevent timeline mock values from leaking into standard UI controllers)
    private var notificationSession: MediaSession? = null

    // Local Player Cache (Holds a reference to the active ExoPlayer instance for secure callback execution)
    private var player: ExoPlayer? = null

    // Audio Focus Manager Helper (Directly delegates system audio focus lifecycle tracking to the sub-component)
    private lateinit var audioFocusManager: PlaybackAudioFocusManager

    // Media Playback Error Handler (Handles stream validation and dynamic playback segment skipping on error)
    private lateinit var failureHandler: PlaybackFailureHandler

    // Legacy Field Cleanup (Removed silenceController after refactoring)

    private lateinit var rewindButton: CommandButton
    private lateinit var forwardButton: CommandButton
    private lateinit var bookmarkButton: CommandButton
    // Playback Book Query Gateway (Limits the media service to book, chapter, and bookmark reads it actually needs)
    // Keeping playback service on granular gateways prevents the foreground media core from depending on the broad UI-facing LibraryFacade.
    private lateinit var bookQueryGateway: BookQueryGateway
    // Playback Plan Gateway (Dedicated media-core read model for plan materialization)
    // Separating plan construction from BookQueryGateway keeps playback startup semantics out of generic book queries.
    private lateinit var playbackPlanGateway: PlaybackPlanGateway
    // Playback Progress Gateway (Provides resume checkpoints and runtime progress state without routing through the UI facade)
    // This keeps progress persistence and playback recovery aligned with the media-core gateway seam.
    private lateinit var progressGateway: ProgressGateway
    // Playback Availability Gateway (Provides status-writing track recovery without coupling it to progress persistence)
    // Runtime media failures use this seam to refresh READY/MISSING rows explicitly.
    private lateinit var bookAvailabilityGateway: BookAvailabilityGateway
    private lateinit var settingsRepository: AppSettingsRepository
    // Playback Domain Event Sink (Publishes media-service outcomes for the application bridge)
    // Foreground service commands no longer construct Toasts directly, keeping media code presentation-free.
    private lateinit var playbackEventSink: PlaybackDomainEventSink
    private lateinit var notificationPlayer: NotificationProgressPlayer

    // Notification Layer Book Metadata Cache (Stores the active book identifier to prevent track index cross-pollution during transitions)
    private var notificationBookId: String? = null
    // Notification Segment Reference List (Maintains track schemas exclusively for timeline calculations)
    private var notificationFiles: List<BookFileEntity> = emptyList()

    // Playback Session State Boundary (Centralizes first-frame and runtime failure classification)
    // The service now adapts Media3 callbacks into session events instead of sharing a mutable playback flag across handlers.
    private val playbackSessionState = PlaybackSessionState()

    private var exitJob: Job? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    companion object {
        const val ACTION_REWIND = "ACTION_REWIND"
        const val ACTION_FORWARD = "ACTION_FORWARD"
        const val ACTION_BOOKMARK = "ACTION_BOOKMARK"
        // Stable Notification Request Code (Ensures unique PendingIntent definition so that the launcher overlay parameters are not discarded)
        private const val REQUEST_OPEN_PLAYER_OVERLAY_FROM_NOTIFICATION = 4100
    }

    override fun onCreate() {
        super.onCreate()
        
        val playbackDependencies = APlayerApplication.getPlaybackRuntimeDependencies(applicationContext)
        // Playback Runtime Dependency Resolution (Resolve only the fine-grained media-core dependency view)
        // The service stores separate gateway references so future playback changes cannot accidentally reach unrelated library facade operations.
        bookQueryGateway = playbackDependencies.bookQueryGateway
        playbackPlanGateway = playbackDependencies.playbackPlanGateway
        progressGateway = playbackDependencies.progressGateway
        bookAvailabilityGateway = playbackDependencies.bookAvailabilityGateway
        settingsRepository = AppSettingsRepository.getInstance(this)
        // Playback Domain Event Sink Resolution (Route service-originated playback facts through the media event stream)
        playbackEventSink = playbackDependencies.playbackDomainEventSink

        // 1. Instantiate the dedicated audio focus manager component.
        audioFocusManager = PlaybackAudioFocusManager(
            context = this,
            serviceScope = serviceScope,
            settingsRepository = settingsRepository,
            playerProvider = { mediaSession?.player }
        )

        // 2. Instantiate the isolated error handler, passing progressGateway to decouple from the legacy repository.
        failureHandler = PlaybackFailureHandler(
            serviceScope = serviceScope,
            bookAvailabilityGateway = bookAvailabilityGateway,
            settingsRepository = settingsRepository,
            playbackEventSink = playbackEventSink
        )

        // 3. Delegate Configuration (Configures and instantiates the core player instance using ExoPlayerFactory)
        val playerInstance = ExoPlayerFactory.createExoPlayer(
            context = this,
             listener = object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    PlaybackWorkflowLogger.error("playbackService player error: code=${error.errorCode}, message=${error.message}", error)
                    val activePlayer = this@PlaybackService.player
                    when (playbackSessionState.classifyPlayerError()) {
                        PlaybackSessionErrorDecision.InitialMediaLoadFailure -> {
                            // Initial Load Failure Branch (Report any pre-playback source error without entering IO recovery)
                            // Applies regardless of saved progress because the session state has not observed playback for this item.
                            activePlayer?.let { failureHandler.handleInitialMediaLoadFailure(it, error) }
                            updateWidgetState()
                            return
                        }
                        PlaybackSessionErrorDecision.RuntimePlaybackFailure -> Unit
                    }
                    // Error Recovery Delegation (Routes server exceptions to the disaster recovery handler for track skipping)
                    if (failureHandler.isUnavailableMediaError(error)) {
                        activePlayer?.let { player ->
                            // Runtime IO Recovery (Keep existing retry and skip rules only after playback has actually started)
                            // Prevents first-load failures from marking tracks missing or entering skip dialogs before the user hears any media.
                            failureHandler.handleUnavailableMediaItem(player)
                        }
                    }
                    if (error.errorCode == PlaybackException.ERROR_CODE_IO_UNSPECIFIED) {
                        val cause = error.cause
                        if (cause is androidx.media3.common.ParserException) {
                            PlaybackWorkflowLogger.error("playbackService parser error: malformed=${cause.contentIsMalformed}", cause)
                        }
                    }
                    // Failure Widget Synchronization (Ensures any fatal playback error triggers a widget update to display current stopped state)
                    updateWidgetState()
                }
 
                override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) {
                    // Transient Artwork Permission Grant (Explicitly grants read access to System UI and external receivers like Android Auto)
                    // Resolves SecurityException crash caused by FileProvider isolation constraints in Android 11+.
                    grantArtworkPermission(mediaItem?.mediaMetadata?.artworkUri)

                    // Lock State Reset (Clears track-skipping transition guard to allow fresh recovery runs)
                    failureHandler.clearSkipGuard()
                    playbackSessionState.onMediaItemTransition()
                    updateNotificationTimeline(mediaItem)
                    // Transition Widget Synchronization (Triggers immediate widget update during track transition to reflect current metadata)
                    updateWidgetState()
                }
 
                override fun onPlaybackStateChanged(playbackState: Int) {
                    playbackSessionState.onPlaybackStateChanged(
                        isReady = playbackState == Player.STATE_READY,
                        isPlaying = this@PlaybackService.player?.isPlaying == true
                    )
                    if (playbackState == Player.STATE_ENDED) {
                        // Playback Queue Finished (Publish domain event and schedule a safe delayed shutdown of the service)
                        // The app-level bridge renders the user notification while the service keeps only playback lifecycle logic.
                        exitJob?.cancel()
                        exitJob = serviceScope.launch {
                            playbackEventSink.emit(PlaybackDomainEvent.PlaybackFinishedShutdownScheduled(delaySeconds = 5))
                            delay(5000.milliseconds)
                            this@PlaybackService.player?.clearMediaItems()
                            stopSelf()
                        }
                    } else {
                        // Active State Cancellation (Cancels the pending shutdown task since user returned to active state)
                        exitJob?.cancel()
                        exitJob = null
                    }
                    // State Change Widget Synchronization (Updates the widget immediately whenever the underlying playback state evolves)
                    updateWidgetState()
                }
 
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    playbackSessionState.onIsPlayingChanged(isPlaying)
                    // Audio Focus Delegation (Routes the active player state updates to keep system focus tracking in sync)
                    audioFocusManager.handlePlayerPlayingStateChanged(isPlaying)
                    // Playback Status Widget Synchronization (Reflects the changes of user-initiated play/pause toggles directly onto the widget)
                    updateWidgetState()
                }
            },
            isAutomaticAudioFocusAllowed = true // Default automatic audio focus (Delegates initial focus handling to ExoPlayer's internal system)
        )
        this.player = playerInstance

        notificationPlayer = NotificationProgressPlayer(playerInstance)
        observeNotificationProgressMode()

        // 4. Hot-Reload Configuration (Subscribes to setting flows to adjust dynamic playback parameters at runtime)
        serviceScope.launch {
            settingsRepository.settingsFlow.collect { settings ->
                /**
                 * Dynamic Silence Skipping (Updates dynamic silence skipping parameters using the standard ExoPlayer API)
                 */
                playerInstance.skipSilenceEnabled = settings.isSkipSilenceEnabled

                // Dynamic Notification Avoidance (Adjusts audio attributes and takes over player focus dynamically based on settings)
                val isAvoidanceEnabled = settings.isNotificationAvoidanceEnabled
                val audioAttributes = AudioAttributes.Builder()
                    .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                    .setUsage(C.USAGE_MEDIA)
                    .build()
                playerInstance.setAudioAttributes(audioAttributes, !isAvoidanceEnabled)

                if (isAvoidanceEnabled) {
                    if (playerInstance.isPlaying) {
                        audioFocusManager.handlePlayerPlayingStateChanged(true)
                    }
                } else {
                    audioFocusManager.reset()
                }
            }
        }



        // Custom Controller Assembly (Initializes the command button models for rewind, forward, and bookmark buttons)
        // Media Session Labels (Resolve user-visible command names from resources)
        // System media surfaces can display these labels, so they follow the same resource policy as in-app copy.
        rewindButton = CommandButton.Builder(CommandButton.ICON_UNDEFINED)
            .setDisplayName(getString(R.string.media_session_rewind_10))
            .setSessionCommand(SessionCommand(ACTION_REWIND, Bundle.EMPTY))
            .setCustomIconResId(R.drawable.ic_replay_10)
            .setEnabled(true)
            .build()

        forwardButton = CommandButton.Builder(CommandButton.ICON_UNDEFINED)
            .setDisplayName(getString(R.string.media_session_forward_30))
            .setSessionCommand(SessionCommand(ACTION_FORWARD, Bundle.EMPTY))
            .setCustomIconResId(R.drawable.ic_forward_30)
            .setEnabled(true)
            .build()

        bookmarkButton = CommandButton.Builder(CommandButton.ICON_UNDEFINED)
            .setDisplayName(getString(R.string.media_session_add_bookmark))
            .setSessionCommand(SessionCommand(ACTION_BOOKMARK, Bundle.EMPTY))
            .setCustomIconResId(R.drawable.ic_bookmark_add)
            .setEnabled(true)
            .build()

        // Intent Reuse Strategy (Configures playerOverlayPendingIntent to launch the active player overlay view directly)
        val playerOverlayPendingIntent = PendingIntent.getActivity(
            this,
            REQUEST_OPEN_PLAYER_OVERLAY_FROM_NOTIFICATION,
            MainActivity.createOpenPlayerOverlayIntent(this),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        mediaSession = MediaSession.Builder(this, playerInstance)
            // Real Timeline Bindings (Exposes original playback sequences directly to the foreground app controller UI)
            .setId("ui")
            .setSessionActivity(playerOverlayPendingIntent)
            .setCallback(CustomCallback())
            .build()

        notificationSession = MediaSession.Builder(this, notificationPlayer)
            // Isolated Notification Bindings (Enables custom timeline presentation specifically for system notifications)
            .setId("notification")
            .setSessionActivity(playerOverlayPendingIntent)
            .setCallback(CustomCallback())
            .build()
            
        // Custom Command Array (Orders buttons as rewind, forward, and bookmark to position actions consistently on the notification drawer)
        mediaSession?.let {
            it.setCustomLayout(listOf(rewindButton, forwardButton, bookmarkButton))
            addSession(it)
        }
        notificationSession?.let {
            it.setCustomLayout(listOf(rewindButton, forwardButton, bookmarkButton))
            addSession(it)
        }
    }

    private fun observeNotificationProgressMode() {
        serviceScope.launch {
            settingsRepository.settingsFlow.collect { settings ->
                notificationPlayer.setChapterMode(settings.isChapterProgressMode)
            }
        }
    }

    /**
     * Explicit Artwork Permission Grant (Grants transient read permissions for FileProvider-generated content:// URIs)
     * 
     * Rationale:
     * In Android 11+ scoped storage, System UI components cannot access private directories.
     * We must call grantUriPermission explicitly, otherwise the system notification service
     * will fail to read artwork images, throwing a SecurityException during metadata resolution.
     */
    private fun grantArtworkPermission(uri: Uri?) {
        if (uri == null || uri.scheme != "content") return

        // Artwork Permission Targets: Define the minimal set of package names for artwork permission delegation, removing broad "android" system package.
        val targetPackages = buildSet {
            add("com.android.systemui")
            mediaSession?.connectedControllers?.forEach { add(it.packageName) }
            notificationSession?.connectedControllers?.forEach { add(it.packageName) }
        }

        for (pkg in targetPackages) {
            try {
                grantUriPermission(pkg, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: Exception) {
                // Safe Exception Catching (Ignores dynamic package errors to ensure core playback flow continues without crashes)
            }
        }
    }

    private fun updateNotificationTimeline(mediaItem: androidx.media3.common.MediaItem?) {
        val mediaParts = PlaybackMediaId.parse(mediaItem?.mediaId) ?: return
        val bookId = mediaParts.bookId

        serviceScope.launch(Dispatchers.IO) {
            // Notification Timeline Query (Fetches only book files and chapters through the playback query gateway)
            // The notification player needs timeline metadata, not the full UI facade surface.
            val files = bookQueryGateway.getFilesForBookSync(bookId)
            val chapters = bookQueryGateway.getChaptersForBookSync(bookId)
            if (files.isNotEmpty()) {
                launch(Dispatchers.Main) {
                    notificationBookId = bookId
                    notificationFiles = files
                    // Safe Chapter Extraction (Unwraps ChapterWithBookFile objects to retrieve raw ChapterEntity blocks for the player tracker)
                    notificationPlayer.updateBookTimeline(bookId, files, chapters.map { it.chapter })
                }
            }
        }
    }

    @UnstableApi
    private inner class CustomCallback : MediaSession.Callback {
        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo
        ): MediaSession.ConnectionResult {
            // Client Package Whitelisting (Performs handshake verification against known package signatures)
            val callingPackage = controller.packageName
            val allowedPackages = listOf(
                packageName,
                "com.android.systemui",
                "com.google.android.projection.gearhead",
                "com.google.android.googlequicksearchbox"
            )
            if (callingPackage !in allowedPackages) {
                return MediaSession.ConnectionResult.reject()
            }

            val sessionCommandsBuilder = MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS.buildUpon()
            
            // Custom Layout Assembly (Injects the customized rewind, forward, and bookmark actions into the controller layout)
            rewindButton.sessionCommand?.let { sessionCommandsBuilder.add(it) }
            forwardButton.sessionCommand?.let { sessionCommandsBuilder.add(it) }
            bookmarkButton.sessionCommand?.let { sessionCommandsBuilder.add(it) }

            val sessionCommands = sessionCommandsBuilder.build()
            val customLayout = listOf(rewindButton, forwardButton, bookmarkButton)

            // Sequence Guard configuration (Removes system forward/backward commands to prevent index out of bounds on multi-track media items)
            val playerCommands = Player.Commands.Builder()
                .addAllCommands()
                .remove(Player.COMMAND_SEEK_TO_PREVIOUS)
                .remove(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
                .remove(Player.COMMAND_SEEK_TO_NEXT)
                .remove(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
                .build()

            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(sessionCommands)
                .setAvailablePlayerCommands(playerCommands)
                .setCustomLayout(customLayout)
                .build()
                .also {
                    // Immediate Client Permission Sync (Ensures that newly connected controllers receive immediate access to the current artwork)
                    session.player.currentMediaItem?.mediaMetadata?.artworkUri?.let { uri ->
                        try {
                            grantUriPermission(controller.packageName, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        } catch (_: Exception) {
                            // Ignore Exception (Ignores transient grant failures safely)
                        }
                    }
                }
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle
        ): ListenableFuture<SessionResult> {
            when (customCommand.customAction) {
                ACTION_REWIND -> session.player.seekBack()
                ACTION_FORWARD -> session.player.seekForward()
                ACTION_BOOKMARK -> {
                    val player = session.player
                    val mediaParts = PlaybackMediaId.parse(player.currentMediaItem?.mediaId)
                    if (mediaParts != null) {
                        val bookId = mediaParts.bookId

                        serviceScope.launch {
                            // Absolute bookmark matching (Determines bookmark timing using absolute millisecond offset across all combined audio tracks)
                            val positionMs = (session.player as? NotificationProgressPlayer)
                                ?.currentGlobalPosition()
                                ?: currentGlobalPosition(session.player, bookId)
                            // Notification Bookmark Command (Persists bookmark changes through the playback query gateway)
                            // Notification actions live in the media service, so they use the same granular gateway path as other playback metadata commands.
                            bookQueryGateway.addBookmark(bookId, positionMs, "Bookmark")
                            playbackEventSink.emit(PlaybackDomainEvent.BookmarkCreated(bookId, positionMs))
                        }
                    }
                }
            }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }

        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            beginPlayback: Boolean
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            // Playback Resumption Setup: Resolves the last played audiobook progress and timeline asynchronously to restore playback session seamlessly when triggered by system media button events.
            val future = com.google.common.util.concurrent.SettableFuture.create<MediaSession.MediaItemsWithStartPosition>()
            serviceScope.launch(Dispatchers.IO) {
                try {
                    val lastProgress = progressGateway.getLastPlayedProgressSync()
                    if (lastProgress == null) {
                        future.setException(UnsupportedOperationException("No last played book found"))
                        return@launch
                    }
                    // Resume Playback Plan Build (Use the dedicated playback-plan gateway)
                    // The foreground media service stays on granular playback seams instead of borrowing generic book queries.
                    val plan = playbackPlanGateway.buildPlaybackPlan(lastProgress.bookId)
                    if (plan == null || plan.files.isEmpty()) {
                        future.setException(UnsupportedOperationException("Playback plan is empty for book: ${lastProgress.bookId}"))
                        return@launch
                    }
                    val mediaItems = com.viel.aplayer.media.PlaybackPlanBuilder.buildMediaItems(plan)
                    val chapters = bookQueryGateway.getChaptersForBookSync(lastProgress.bookId)
                    val startIndex = if (lastProgress.currentFileIndex in mediaItems.indices) {
                        lastProgress.currentFileIndex
                    } else {
                        0
                    }
                    val startPositionMs = if (lastProgress.currentFileIndex in mediaItems.indices) {
                        lastProgress.positionInFileMs
                    } else {
                        0L
                    }

                    withContext(Dispatchers.Main) {
                        // Media Resumption Setup (Initialize player configuration and notification timeline before resuming)
                        notificationBookId = plan.bookId
                        notificationFiles = plan.files
                        notificationPlayer.updateBookTimeline(plan.bookId, plan.files, chapters.map { it.chapter })

                        val resumptionData = MediaSession.MediaItemsWithStartPosition(
                            mediaItems,
                            startIndex,
                            startPositionMs
                        )
                        future.set(resumptionData)
                    }
                } catch (e: Exception) {
                    future.setException(e)
                }
            }
            return future
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        return mediaSession
    }

    override fun onUpdateNotification(session: MediaSession, startInForegroundRequired: Boolean) {
        if (session == notificationSession) {
            super.onUpdateNotification(session, startInForegroundRequired)
        }
    }

    private suspend fun currentGlobalPosition(player: Player, bookId: String): Long {
        val fileIndex = player.currentMediaItemIndex.coerceAtLeast(0)
        val positionInFile = player.currentPosition.coerceAtLeast(0L)
        // Missing Cache Fetch (Retrieves the segments through the playback query gateway when cached files are missing)
        // This fallback keeps global-position mapping local to the media gateway surface instead of reaching the UI facade.
        val files = notificationFiles.takeIf { notificationBookId == bookId && it.isNotEmpty() }
            ?: bookQueryGateway.getFilesForBookSync(bookId)
        
        return if (files.isNotEmpty()) {
            PositionMapper.fileToGlobalPosition(fileIndex, positionInFile, files)
                .coerceIn(0L, files.sumOf { it.durationMs }.coerceAtLeast(0L))
        } else {
            positionInFile
        }
    }

    // Debounced Widget Updates (Controls update frequency and avoids database hits when no widgets are currently active)
    private fun updateWidgetState() {
        val playerInstance = player ?: return
        
        // Debounce Task Cancellation (Discards the previously scheduled update request to prevent redundant redraw overhead)
        widgetUpdateJob?.cancel()
        widgetUpdateJob = serviceScope.launch(Dispatchers.Main) {
            // Debounce Delay (Filters rapid status transitions by deferring the execution by 250ms)
            delay(250.milliseconds)

            // Main Thread Verification (Ensures player properties are queried on the main thread, satisfying media framework requirements)
            val isPlaying = playerInstance.isPlaying
            val mediaItem = playerInstance.currentMediaItem
            val mediaId = mediaItem?.mediaId
            val fallbackTitle = mediaItem?.mediaMetadata?.title?.toString()
            val fallbackArtist = mediaItem?.mediaMetadata?.artist?.toString()

            val mediaParts = PlaybackMediaId.parse(mediaId)
            if (mediaParts != null) {
                val bookId = mediaParts.bookId
                withContext(Dispatchers.IO) {
                    // Glance Widget Check (Pre-evaluates the active widget count to skip heavy database queries when no widgets are displayed)
                    val glanceIds = GlanceAppWidgetManager(this@PlaybackService)
                        .getGlanceIds(PlayerWidget::class.java)
                    if (glanceIds.isEmpty()) return@withContext

                    // Widget Metadata Query (Loads active book details through the playback query gateway)
                    // Widget updates are triggered from the media service and should not depend on the broad UI facade.
                    val book = bookQueryGateway.getBookById(bookId)
                    val title = book?.title ?: fallbackTitle
                    val author = book?.author ?: fallbackArtist
                    val coverPath = book?.thumbnailPath ?: book?.coverPath
                    
                    PlayerWidgetStateHelper.updateWidgetState(
                        context = this@PlaybackService,
                        isPlaying = isPlaying,
                        title = title,
                        author = author,
                        coverPath = coverPath
                    )
                }
            } else {
                withContext(Dispatchers.IO) {
                    // Idle Widget Check (Ensures idle status changes only write updates when active widgets are present)
                    val glanceIds = GlanceAppWidgetManager(this@PlaybackService)
                        .getGlanceIds(PlayerWidget::class.java)
                    if (glanceIds.isEmpty()) return@withContext

                    PlayerWidgetStateHelper.updateWidgetState(
                        context = this@PlaybackService,
                        isPlaying = false,
                        title = null,
                        author = null,
                        coverPath = null
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        // Job Cancellation (Discards pending debounced widget updates to prevent memory leaks during service shutdown)
        widgetUpdateJob?.cancel()

        // Non-blocking Widget Cleanup: Dispatches the widget state cleanup to the application scope, ensuring it runs to completion on an IO dispatcher without blocking the main thread during onDestroy.
        (applicationContext as? APlayerApplication)?.appScope?.launch {
            PlayerWidgetStateHelper.updateWidgetState(
                context = this@PlaybackService,
                isPlaying = false,
                title = null,
                author = null,
                coverPath = null
            )
        }
        serviceScope.cancel()
        audioFocusManager.reset() // Release System Focus (Releases audio focus resources when service terminates)
        notificationSession?.run {
            release()
            notificationSession = null
        }
        mediaSession?.run {
            player.release()
            release()
            mediaSession = null
        }
        super.onDestroy()
    }
}
