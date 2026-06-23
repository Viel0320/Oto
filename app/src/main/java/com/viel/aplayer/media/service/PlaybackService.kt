package com.viel.aplayer.media.service

import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ApplicationInfo
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
import com.viel.aplayer.MainActivity
import com.viel.aplayer.R
import com.viel.aplayer.application.usecase.BuildPlaybackPlanUseCase
import com.viel.aplayer.data.AppSettingsRepository
import com.viel.aplayer.data.availability.BookAvailabilityGateway
import com.viel.aplayer.data.book.BookCatalogGateway
import com.viel.aplayer.data.book.BookmarkGateway
import com.viel.aplayer.data.book.ChapterGateway
import com.viel.aplayer.data.entity.BookFileEntity
import com.viel.aplayer.data.progress.ProgressGateway
import com.viel.aplayer.logger.PlaybackWorkflowLogger
import com.viel.aplayer.media.AutoRewindManager
import com.viel.aplayer.media.NotificationProgressPlayer
import com.viel.aplayer.media.PlaybackDomainEvent
import com.viel.aplayer.media.PlaybackDomainEventSink
import com.viel.aplayer.media.PlaybackMediaId
import com.viel.aplayer.media.PlaybackSourcePreflight
import com.viel.aplayer.media.SeekStepPresentation
import com.viel.aplayer.media.session.PlaybackSessionErrorDecision
import com.viel.aplayer.media.session.PlaybackSessionState
import com.viel.aplayer.shared.settings.PlaybackSeekStepConfig
import com.viel.aplayer.timeline.PositionMapper
import com.viel.aplayer.widget.PlayerWidget
import com.viel.aplayer.widget.PlayerWidgetStateHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import kotlin.time.Duration.Companion.milliseconds

/**
 * Manages life cycle of media playbacks and acts as a container for MediaSession instances.
 * Architectural refinement isolates concerns across specialized components:
 * 1. Customized ExoPlayer configuration is delegated entirely to [ExoPlayerFactory].
 * 2. System audio focus and notification ducking dynamics are isolated in [PlaybackAudioFocusManager].
 * 3. Media accessibility verification and error handling are managed by [PlaybackFailureHandler].
 * Under this design, the playback service serves as a lightweight harness wrapper, reducing visual coupling.
 */
@UnstableApi
class PlaybackService : MediaSessionService(), KoinComponent {
    private val injectedBookCatalogGateway: BookCatalogGateway by inject()
    private val injectedChapterGateway: ChapterGateway by inject()
    private val injectedBookmarkGateway: BookmarkGateway by inject()
    private val injectedBuildPlaybackPlanUseCase: BuildPlaybackPlanUseCase by inject()
    private val injectedPlaybackSourcePreflight: PlaybackSourcePreflight by inject()
    private val injectedProgressGateway: ProgressGateway by inject()
    private val injectedBookAvailabilityGateway: BookAvailabilityGateway by inject()
    private val injectedPlaybackEventSink: PlaybackDomainEventSink by inject()
    private val injectedSettingsRepository: AppSettingsRepository by inject()
    private val autoRewindManager: AutoRewindManager by inject()

    private var widgetUpdateJob: Job? = null

    private var mediaSession: MediaSession? = null
    private var notificationSession: MediaSession? = null

    private var player: ExoPlayer? = null

    private lateinit var audioFocusManager: PlaybackAudioFocusManager

    private lateinit var failureHandler: PlaybackFailureHandler


    private lateinit var rewindButton: CommandButton
    private lateinit var forwardButton: CommandButton
    private lateinit var bookmarkButton: CommandButton
    private lateinit var bookCatalogGateway: BookCatalogGateway
    private lateinit var chapterGateway: ChapterGateway
    private lateinit var bookmarkGateway: BookmarkGateway
    private lateinit var buildPlaybackPlanUseCase: BuildPlaybackPlanUseCase
    private lateinit var playbackSourcePreflight: PlaybackSourcePreflight
    private lateinit var progressGateway: ProgressGateway
    private lateinit var bookAvailabilityGateway: BookAvailabilityGateway
    private lateinit var settingsRepository: AppSettingsRepository
    private lateinit var playbackEventSink: PlaybackDomainEventSink
    private lateinit var resumptionPreflight: PlaybackResumptionPreflight
    private lateinit var notificationPlayer: NotificationProgressPlayer

    private var notificationBookId: String? = null
    private var notificationFiles: List<BookFileEntity> = emptyList()

    private val playbackSessionState = PlaybackSessionState()

    private var exitJob: Job? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    companion object {
        const val ACTION_REWIND = "ACTION_REWIND"
        const val ACTION_FORWARD = "ACTION_FORWARD"
        const val ACTION_BOOKMARK = "ACTION_BOOKMARK"
        private const val REQUEST_OPEN_PLAYER_OVERLAY_FROM_NOTIFICATION = 4100
    }

    override fun onCreate() {
        super.onCreate()

        bookCatalogGateway = injectedBookCatalogGateway
        chapterGateway = injectedChapterGateway
        bookmarkGateway = injectedBookmarkGateway
        buildPlaybackPlanUseCase = injectedBuildPlaybackPlanUseCase
        playbackSourcePreflight = injectedPlaybackSourcePreflight
        progressGateway = injectedProgressGateway
        bookAvailabilityGateway = injectedBookAvailabilityGateway
        settingsRepository = injectedSettingsRepository
        playbackEventSink = injectedPlaybackEventSink
        resumptionPreflight = PlaybackResumptionPreflight(
            playbackSourcePreflight = playbackSourcePreflight,
            settingsProvider = { settingsRepository.settingsFlow.first() },
            playbackEventSink = playbackEventSink
        )

        audioFocusManager = PlaybackAudioFocusManager(
            context = this,
            serviceScope = serviceScope,
            settingsRepository = settingsRepository,
            autoRewindManager = autoRewindManager,
            playerProvider = { mediaSession?.player }
        )

        failureHandler = PlaybackFailureHandler(
            serviceScope = serviceScope,
            bookAvailabilityGateway = bookAvailabilityGateway,
            settingsRepository = settingsRepository,
            playbackEventSink = playbackEventSink
        )

        val playerInstance = ExoPlayerFactory.createExoPlayer(
            context = this,
             listener = object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    PlaybackWorkflowLogger.error("playbackService player error: code=${error.errorCode}, message=${error.message}", error)
                    val activePlayer = this@PlaybackService.player
                    when (playbackSessionState.classifyPlayerError()) {
                        PlaybackSessionErrorDecision.InitialMediaLoadFailure -> {
                            activePlayer?.let { failureHandler.handleInitialMediaLoadFailure(it, error) }
                            updateWidgetState()
                            return
                        }
                        PlaybackSessionErrorDecision.RuntimePlaybackFailure -> Unit
                    }
                    if (failureHandler.isUnavailableMediaError(error)) {
                        activePlayer?.let { player ->
                            failureHandler.handleUnavailableMediaItem(player)
                        }
                    }
                    if (error.errorCode == PlaybackException.ERROR_CODE_IO_UNSPECIFIED) {
                        val cause = error.cause
                        if (cause is androidx.media3.common.ParserException) {
                            PlaybackWorkflowLogger.error("playbackService parser error: malformed=${cause.contentIsMalformed}", cause)
                        }
                    }
                    updateWidgetState()
                }

                override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) {
                    grantArtworkPermission(mediaItem?.mediaMetadata?.artworkUri)

                    failureHandler.clearSkipGuard()
                    playbackSessionState.onMediaItemTransition()
                    updateNotificationTimeline(mediaItem)
                    updateWidgetState()
                }

                override fun onPlaybackStateChanged(playbackState: Int) {
                    playbackSessionState.onPlaybackStateChanged(
                        isReady = playbackState == Player.STATE_READY,
                        isPlaying = this@PlaybackService.player?.isPlaying == true
                    )
                    if (playbackState == Player.STATE_ENDED) {
                        exitJob?.cancel()
                        exitJob = serviceScope.launch {
                            playbackEventSink.emit(PlaybackDomainEvent.PlaybackFinishedShutdownScheduled(delaySeconds = 5))
                            delay(5000.milliseconds)
                            this@PlaybackService.player?.clearMediaItems()
                            stopSelf()
                        }
                    } else {
                        exitJob?.cancel()
                        exitJob = null
                    }
                    updateWidgetState()
                }

                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    playbackSessionState.onIsPlayingChanged(isPlaying)
                    audioFocusManager.handlePlayerPlayingStateChanged(isPlaying)
                    updateWidgetState()
                }
            },
            isAutomaticAudioFocusAllowed = true
        )
        this.player = playerInstance

        notificationPlayer = NotificationProgressPlayer(playerInstance)
        observeNotificationProgressMode()

        serviceScope.launch {
            settingsRepository.settingsFlow.collect { settings ->
                /**
                 * Updates dynamic silence skipping parameters using the standard ExoPlayer API.
                 */
                playerInstance.skipSilenceEnabled = settings.isSkipSilenceEnabled
                val seekConfig = settings.playbackSeekStepConfig
                val backwardMs = seekConfig.backward.toMillis()
                val forwardMs = seekConfig.forward.toMillis()
                playerInstance.setSeekBackIncrementMs(backwardMs)
                playerInstance.setSeekForwardIncrementMs(forwardMs)
                notificationPlayer.setSeekIncrements(backwardMs = backwardMs, forwardMs = forwardMs)
                rebuildTransportButtons(seekConfig)
                applyCustomCommandLayout()

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



        rebuildTransportButtons(settingsRepository.cachedSettings.playbackSeekStepConfig)

        bookmarkButton = CommandButton.Builder(CommandButton.ICON_UNDEFINED)
            .setDisplayName(getString(R.string.bookmark_add_title))
            .setSessionCommand(SessionCommand(ACTION_BOOKMARK, Bundle.EMPTY))
            .setCustomIconResId(R.drawable.ic_bookmark_add)
            .setEnabled(true)
            .build()

        val playerOverlayPendingIntent = PendingIntent.getActivity(
            this,
            REQUEST_OPEN_PLAYER_OVERLAY_FROM_NOTIFICATION,
            MainActivity.createOpenPlayerOverlayIntent(this),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        mediaSession = MediaSession.Builder(this, playerInstance)
            .setId("ui")
            .setSessionActivity(playerOverlayPendingIntent)
            .setCallback(CustomCallback())
            .build()

        notificationSession = MediaSession.Builder(this, notificationPlayer)
            .setId("notification")
            .setSessionActivity(playerOverlayPendingIntent)
            .setCallback(CustomCallback())
            .build()

        applyCustomCommandLayout()
        mediaSession?.let { addSession(it) }
        notificationSession?.let { addSession(it) }
    }

    private fun rebuildTransportButtons(config: PlaybackSeekStepConfig) {
        rewindButton = CommandButton.Builder(CommandButton.ICON_UNDEFINED)
            .setDisplayName(getString(SeekStepPresentation.backwardLabel(config.backward)))
            .setSessionCommand(SessionCommand(ACTION_REWIND, Bundle.EMPTY))
            .setCustomIconResId(SeekStepPresentation.backwardIcon(config.backward))
            .setEnabled(true)
            .build()

        forwardButton = CommandButton.Builder(CommandButton.ICON_UNDEFINED)
            .setDisplayName(getString(SeekStepPresentation.forwardLabel(config.forward)))
            .setSessionCommand(SessionCommand(ACTION_FORWARD, Bundle.EMPTY))
            .setCustomIconResId(SeekStepPresentation.forwardIcon(config.forward))
            .setEnabled(true)
            .build()
    }

    private fun applyCustomCommandLayout() {
        if (!::rewindButton.isInitialized || !::forwardButton.isInitialized || !::bookmarkButton.isInitialized) return
        val layout = listOf(rewindButton, forwardButton, bookmarkButton)
        mediaSession?.setCustomLayout(layout)
        notificationSession?.setCustomLayout(layout)
    }

    private fun observeNotificationProgressMode() {
        serviceScope.launch {
            settingsRepository.settingsFlow.collect { settings ->
                notificationPlayer.setChapterMode(settings.isChapterProgressMode)
            }
        }
    }

    /**
     * Grants transient read permissions for FileProvider-generated content:// URIs.
     *
     * Rationale:
     * In Android 11+ scoped storage, System UI components cannot access private directories.
     * We must call grantUriPermission explicitly, otherwise the system notification service
     * will fail to read artwork images, throwing a SecurityException during metadata resolution.
     */
    private fun grantArtworkPermission(uri: Uri?) {
        if (uri == null || uri.scheme != "content") return

        val targetPackages = buildSet {
            add("com.android.systemui")
            mediaSession?.connectedControllers?.forEach { add(it.packageName) }
            notificationSession?.connectedControllers?.forEach { add(it.packageName) }
        }

        for (pkg in targetPackages) {
            try {
                grantUriPermission(pkg, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: Exception) {
            }
        }
    }

    private fun updateNotificationTimeline(mediaItem: androidx.media3.common.MediaItem?) {
        val mediaParts = PlaybackMediaId.parse(mediaItem?.mediaId) ?: return
        val bookId = mediaParts.bookId

        serviceScope.launch(Dispatchers.IO) {
            val files = bookCatalogGateway.getFilesForBookSync(bookId)
            val chapters = chapterGateway.getChaptersForBookSync(bookId)
            if (files.isNotEmpty()) {
                launch(Dispatchers.Main) {
                    notificationBookId = bookId
                    notificationFiles = files
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
            if (!isControllerPackageBoundToUid(controller)) {
                return MediaSession.ConnectionResult.reject()
            }

            val sessionCommandsBuilder = MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS.buildUpon()

            val customLayout = if (canUsePrivilegedMediaControls(controller)) {
                rewindButton.sessionCommand?.let { sessionCommandsBuilder.add(it) }
                forwardButton.sessionCommand?.let { sessionCommandsBuilder.add(it) }
                bookmarkButton.sessionCommand?.let { sessionCommandsBuilder.add(it) }
                listOf(rewindButton, forwardButton, bookmarkButton)
            } else {
                emptyList()
            }

            val sessionCommands = sessionCommandsBuilder.build()

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
                    session.player.currentMediaItem?.mediaMetadata?.artworkUri?.let { uri ->
                        try {
                            grantUriPermission(controller.packageName, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        } catch (_: Exception) {
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
                            val positionMs = (session.player as? NotificationProgressPlayer)
                                ?.currentGlobalPosition()
                                ?: currentGlobalPosition(session.player, bookId)
                            bookmarkGateway.addBookmark(bookId, positionMs, "Bookmark")
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
            val future = com.google.common.util.concurrent.SettableFuture.create<MediaSession.MediaItemsWithStartPosition>()
            serviceScope.launch(Dispatchers.IO) {
                try {
                    val lastProgress = progressGateway.getLastPlayedProgressSync()
                    if (lastProgress == null) {
                        future.setException(UnsupportedOperationException("No last played book found"))
                        return@launch
                    }
                    val plan = buildPlaybackPlanUseCase(lastProgress.bookId)
                    if (plan == null || plan.files.isEmpty()) {
                        future.setException(UnsupportedOperationException("Playback plan is empty for book: ${lastProgress.bookId}"))
                        return@launch
                    }
                    resumptionPreflight.requireAvailable(plan)
                    val mediaItems = com.viel.aplayer.media.PlaybackPlanBuilder.buildMediaItems(plan)
                    val chapters = chapterGateway.getChaptersForBookSync(lastProgress.bookId)
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

    private fun isControllerPackageBoundToUid(controller: MediaSession.ControllerInfo): Boolean {
        val packagesForUid = packageManager.getPackagesForUid(controller.uid) ?: return false
        return controller.packageName in packagesForUid
    }

    private fun canUsePrivilegedMediaControls(controller: MediaSession.ControllerInfo): Boolean {
        if (controller.packageName == packageName) return true
        return runCatching {
            val appInfo = packageManager.getApplicationInfo(controller.packageName, 0)
            val isSystemApp = appInfo.flags and ApplicationInfo.FLAG_SYSTEM != 0
            val isUpdatedSystemApp = appInfo.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP != 0
            isSystemApp || isUpdatedSystemApp
        }.getOrDefault(false)
    }

    override fun onUpdateNotification(session: MediaSession, startInForegroundRequired: Boolean) {
        if (session == notificationSession) {
            super.onUpdateNotification(session, startInForegroundRequired)
        }
    }

    private suspend fun currentGlobalPosition(player: Player, bookId: String): Long {
        val fileIndex = player.currentMediaItemIndex.coerceAtLeast(0)
        val positionInFile = player.currentPosition.coerceAtLeast(0L)
        val files = notificationFiles.takeIf { notificationBookId == bookId && it.isNotEmpty() }
            ?: bookCatalogGateway.getFilesForBookSync(bookId)

        return if (files.isNotEmpty()) {
            PositionMapper.fileToGlobalPosition(fileIndex, positionInFile, files)
                .coerceIn(0L, files.sumOf { it.durationMs }.coerceAtLeast(0L))
        } else {
            positionInFile
        }
    }

    private fun updateWidgetState() {
        val playerInstance = player ?: return

        widgetUpdateJob?.cancel()
        widgetUpdateJob = serviceScope.launch(Dispatchers.Main) {
            delay(250.milliseconds)

            val isPlaying = playerInstance.isPlaying
            val mediaItem = playerInstance.currentMediaItem
            val mediaId = mediaItem?.mediaId
            val fallbackTitle = mediaItem?.mediaMetadata?.title?.toString()
            val fallbackArtist = mediaItem?.mediaMetadata?.artist?.toString()

            val mediaParts = PlaybackMediaId.parse(mediaId)
            if (mediaParts != null) {
                val bookId = mediaParts.bookId
                withContext(Dispatchers.IO) {
                    val glanceIds = GlanceAppWidgetManager(this@PlaybackService)
                        .getGlanceIds(PlayerWidget::class.java)
                    if (glanceIds.isEmpty()) return@withContext

                    val book = bookCatalogGateway.getBookById(bookId)
                    val title = book?.title ?: fallbackTitle
                    val author = book?.author ?: fallbackArtist
                    val coverPath = book?.thumbnailPath ?: book?.coverPath
                    val seekConfig = settingsRepository.cachedSettings.playbackSeekStepConfig

                    PlayerWidgetStateHelper.updateWidgetState(
                        context = this@PlaybackService,
                        isPlaying = isPlaying,
                        title = title,
                        author = author,
                        coverPath = coverPath,
                        seekBackwardSeconds = seekConfig.backward.seconds,
                        seekForwardSeconds = seekConfig.forward.seconds
                    )
                }
            } else {
                withContext(Dispatchers.IO) {
                    val glanceIds = GlanceAppWidgetManager(this@PlaybackService)
                        .getGlanceIds(PlayerWidget::class.java)
                    if (glanceIds.isEmpty()) return@withContext

                    val seekConfig = settingsRepository.cachedSettings.playbackSeekStepConfig
                    PlayerWidgetStateHelper.updateWidgetState(
                        context = this@PlaybackService,
                        isPlaying = false,
                        title = null,
                        author = null,
                        coverPath = null,
                        seekBackwardSeconds = seekConfig.backward.seconds,
                        seekForwardSeconds = seekConfig.forward.seconds
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        widgetUpdateJob?.cancel()

        serviceScope.launch {
            PlayerWidgetStateHelper.updateWidgetState(
                context = this@PlaybackService,
                isPlaying = false,
                title = null,
                author = null,
                coverPath = null,
                seekBackwardSeconds = settingsRepository.cachedSettings.playbackSeekStepConfig.backward.seconds,
                seekForwardSeconds = settingsRepository.cachedSettings.playbackSeekStepConfig.forward.seconds
            )
        }
        serviceScope.cancel()
        audioFocusManager.reset()
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
