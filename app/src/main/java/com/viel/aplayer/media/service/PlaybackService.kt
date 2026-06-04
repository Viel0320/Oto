package com.viel.aplayer.media.service

// Widget State Sync Imports (Import the helper classes, Glance widget manager, and coroutine utilities)
// Resolves UI and database mapping dependencies needed to update widget data store asynchronously.
import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
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
import com.viel.aplayer.MainActivity
import com.viel.aplayer.R
import com.viel.aplayer.data.AppSettingsRepository
import com.viel.aplayer.data.LibraryFacade
import com.viel.aplayer.data.entity.BookFileEntity
import com.viel.aplayer.logger.PlaybackWorkflowLogger
import com.viel.aplayer.media.NotificationProgressPlayer
import com.viel.aplayer.media.PlaybackMediaId
import com.viel.aplayer.media.PositionMapper
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
    // Unified Library Facade Service (References the consolidated gateway domain instead of the legacy monolithic repository)
    private lateinit var libraryFacade: LibraryFacade
    private lateinit var settingsRepository: AppSettingsRepository
    private lateinit var notificationPlayer: NotificationProgressPlayer

    // Notification Layer Book Metadata Cache (Stores the active book identifier to prevent track index cross-pollution during transitions)
    private var notificationBookId: String? = null
    // Notification Segment Reference List (Maintains track schemas exclusively for timeline calculations)
    private var notificationFiles: List<BookFileEntity> = emptyList()

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
        
        val container = (applicationContext as com.viel.aplayer.APlayerApplication).container
        libraryFacade = container.libraryFacade
        settingsRepository = AppSettingsRepository.getInstance(this)

        // 1. Instantiate the dedicated audio focus manager component.
        audioFocusManager = PlaybackAudioFocusManager(
            context = this,
            serviceScope = serviceScope,
            settingsRepository = settingsRepository,
            playerProvider = { mediaSession?.player }
        )

        // 2. Instantiate the isolated error handler, passing progressGateway to decouple from the legacy repository.
        failureHandler = PlaybackFailureHandler(
            context = this,
            serviceScope = serviceScope,
            progressGateway = container.progressGateway,
            settingsRepository = settingsRepository
        )

        // 3. Delegate Configuration (Configures and instantiates the core player instance using ExoPlayerFactory)
        val playerInstance = ExoPlayerFactory.createExoPlayer(
            context = this,
             listener = object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    PlaybackWorkflowLogger.error("playbackService player error: code=${error.errorCode}, message=${error.message}", error)
                    // Error Recovery Delegation (Routes server exceptions to the disaster recovery handler for track skipping)
                    if (failureHandler.isUnavailableMediaError(error)) {
                        // Interactive Error Broadcast (Passes the mediaSession to allow broadcasting EVENT_TRACK_UNAVAILABLE command to connected controllers)
                        this@PlaybackService.player?.let { failureHandler.handleUnavailableMediaItem(it, mediaSession) }
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
                    updateNotificationTimeline(mediaItem)
                    // Transition Widget Synchronization (Triggers immediate widget update during track transition to reflect current metadata)
                    updateWidgetState()
                }
 
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_ENDED) {
                        // Playback Queue Finished (Prompts the user and schedules a safe 5-second delayed shutdown of the service)
                        exitJob?.cancel()
                        exitJob = serviceScope.launch {
                            Toast.makeText(this@PlaybackService, "播放结束，5秒后将自动关闭", Toast.LENGTH_SHORT).show()
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
        rewindButton = CommandButton.Builder(CommandButton.ICON_UNDEFINED)
            .setDisplayName("快退10秒")
            .setSessionCommand(SessionCommand(ACTION_REWIND, Bundle.EMPTY))
            .setCustomIconResId(R.drawable.ic_replay_10)
            .setEnabled(true)
            .build()

        forwardButton = CommandButton.Builder(CommandButton.ICON_UNDEFINED)
            .setDisplayName("快进30秒")
            .setSessionCommand(SessionCommand(ACTION_FORWARD, Bundle.EMPTY))
            .setCustomIconResId(R.drawable.ic_forward_30)
            .setEnabled(true)
            .build()

        bookmarkButton = CommandButton.Builder(CommandButton.ICON_UNDEFINED)
            .setDisplayName("添加书签")
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

        // Target Process Packages (Defines System UI and Core OS frameworks as target packages for permission delegation)
        val targetPackages = mutableSetOf("com.android.systemui", "android")

        // Client Authority Extension (Grants read access to currently connected controllers dynamically)
        mediaSession?.connectedControllers?.forEach { targetPackages.add(it.packageName) }
        notificationSession?.connectedControllers?.forEach { targetPackages.add(it.packageName) }

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
            // Sync Metadata Load (Fetches book file mappings and chapters directly using the library facade interface)
            val files = libraryFacade.getFilesForBookSync(bookId)
            val chapters = libraryFacade.getChaptersForBookSync(bookId)
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
                            // Unified Facade Persistence (Delegates persistence changes directly to the library facade endpoint)
                            libraryFacade.addBookmark(bookId, positionMs, "Bookmark")
                            Toast.makeText(this@PlaybackService, "已添加当前播放位置到书签", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
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
        // Missing Cache Fetch (Retrieves the segments using the facade when cached files are missing to ensure correct mapping)
        val files = notificationFiles.takeIf { notificationBookId == bookId && it.isNotEmpty() }
            ?: libraryFacade.getFilesForBookSync(bookId)
        
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

                    // Detailed Metadata Query (Loads active book details to populate widget text fields and remote artwork)
                    val book = libraryFacade.getBookById(bookId)
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

        // Clear Widget State (Enforces a clean idle update to the widget data store to prevent lingering notification displays)
        serviceScope.launch {
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
