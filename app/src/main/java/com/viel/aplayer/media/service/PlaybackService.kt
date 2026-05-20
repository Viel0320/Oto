package com.viel.aplayer.media.service

import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.mp3.Mp3Extractor
import androidx.media3.extractor.mp4.Mp4Extractor
import androidx.media3.extractor.ts.AdtsExtractor
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.viel.aplayer.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.viel.aplayer.data.AppSettingsRepository
import com.viel.aplayer.data.LibraryRepository
import com.viel.aplayer.data.entity.BookFileEntity
import com.viel.aplayer.media.NotificationProgressPlayer
import com.viel.aplayer.media.PositionMapper

class PlaybackService : MediaSessionService() {
    private var mediaSession: MediaSession? = null
    // 通知栏使用独立 session，避免通知显示进度反向污染 App/UI controller。
    private var notificationSession: MediaSession? = null
    private lateinit var rewindButton: CommandButton
    private lateinit var forwardButton: CommandButton
    private lateinit var bookmarkButton: CommandButton
    private lateinit var libraryRepository: LibraryRepository
    private lateinit var settingsRepository: AppSettingsRepository
    private lateinit var notificationPlayer: NotificationProgressPlayer
    // 通知层缓存当前书籍 ID，用来防止切书瞬间误用上一书的文件列表。
    private var notificationBookId: String? = null
    // 通知层缓存当前书籍文件列表，只服务于通知命令和进度显示映射。
    private var notificationFiles: List<BookFileEntity> = emptyList()
    // ExoPlayer may report the same failing item more than once; keep one skip job per queue item.
    private var unavailableSkipKey: String? = null
    private var exitJob: Job? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    companion object {
        const val ACTION_REWIND = "ACTION_REWIND"
        const val ACTION_FORWARD = "ACTION_FORWARD"
        const val ACTION_BOOKMARK = "ACTION_BOOKMARK"
    }

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()
        
        libraryRepository = LibraryRepository.getInstance(this)
        settingsRepository = AppSettingsRepository.getInstance(this)

        val loadControl = androidx.media3.exoplayer.DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                30000, // Min buffer 30s
                30000, // Max buffer 30s
                1000,  // Buffer for playback 1s
                2000   // Buffer for playback after rebuffer 2s
            )
            .build()

        val renderersFactory = androidx.media3.exoplayer.DefaultRenderersFactory(this)
            // 允许 Media3 在硬件解码器失败时尝试备用解码器（甚至是软件解码器），增加稳定性。
            .setEnableDecoderFallback(true)
            // 注意：不再强制禁用异步队列。在 Android 12+ 上，异步 MediaCodec 更加稳定。

        val extractorsFactory = DefaultExtractorsFactory()
            .setMp3ExtractorFlags(Mp3Extractor.FLAG_ENABLE_INDEX_SEEKING)
            .setAdtsExtractorFlags(AdtsExtractor.FLAG_ENABLE_CONSTANT_BITRATE_SEEKING)
            // Note: In newer Media3 (1.2.0+), consider adding .setMp4ExtractorFlags(Mp4Extractor.FLAG_READ_SAMPLE_TABLE_DIRECTLY)
            // to further reduce memory usage for large M4B files.

        val mediaSourceFactory = DefaultMediaSourceFactory(this, extractorsFactory)

        val player = ExoPlayer.Builder(this, renderersFactory)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                    .setUsage(C.USAGE_MEDIA)
                    .build(),
                true
            )
            .setLoadControl(loadControl)
            .setMediaSourceFactory(mediaSourceFactory)
            .setSeekBackIncrementMs(10000)
            .setSeekForwardIncrementMs(30000)
            .build()

        player.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                Log.e("PlaybackService", "Player error: ${error.message}", error)
                if (isUnavailableMediaError(error)) {
                    handleUnavailableMediaItem(player)
                }
                if (error.errorCode == PlaybackException.ERROR_CODE_IO_UNSPECIFIED) {
                    val cause = error.cause
                    if (cause is androidx.media3.common.ParserException) {
                        Log.e("PlaybackService", "Parser exception: contentIsMalformed=${cause.contentIsMalformed}, dataType=${cause.dataType}")
                    }
                }
            }

            override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) {
                // A successful transition clears the previous failed-item guard.
                unavailableSkipKey = null
                updateNotificationTimeline(mediaItem)
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) {
                    // 详尽的中文注释：当检测到整个播放队列播放结束（STATE_ENDED）时，弹出提示并启动 5 秒倒计时。
                    // 倒计时结束后，清空播放队列并销毁服务。
                    exitJob?.cancel()
                    exitJob = serviceScope.launch {
                        Toast.makeText(this@PlaybackService, "播放结束，5秒后将自动关闭", Toast.LENGTH_SHORT).show()
                        delay(5000)
                        player.clearMediaItems()
                        stopSelf()
                    }
                } else {
                    // 详尽的中文注释：若状态变为非结束（如用户手动操作），则取消待定的退出任务。
                    exitJob?.cancel()
                    exitJob = null
                }
            }
        })
        notificationPlayer = NotificationProgressPlayer(player)
        observeNotificationProgressMode()

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

        mediaSession = MediaSession.Builder(this, player)
            // App/UI controller 连接默认 session，必须看到真实文件级播放状态。
            .setId("ui")
            .setCallback(CustomCallback())
            .build()

        notificationSession = MediaSession.Builder(this, notificationPlayer)
            // 通知专用 session 可以包装进度，不影响 App/UI 的真实 controller。
            .setId("notification")
            .setCallback(CustomCallback())
            .build()
            
        // 顺序：快退 -> 快进 -> 书签。书签在列表最后，会显示在通知栏的最右侧槽位。
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
                // Keep MediaSession progress mode in sync even when the full player UI is closed.
                notificationPlayer.setChapterMode(settings.isChapterProgressMode)
            }
        }
    }

    private fun updateNotificationTimeline(mediaItem: androidx.media3.common.MediaItem?) {
        val mediaId = mediaItem?.mediaId ?: return
        if (!mediaId.contains(":")) return
        val bookId = mediaId.substringBefore(":")

        serviceScope.launch(Dispatchers.IO) {
            val files = libraryRepository.getFilesForBookSync(bookId)
            val chapters = libraryRepository.getChaptersForBookSync(bookId)
            if (files.isNotEmpty()) {
                launch(Dispatchers.Main) {
                    // A book can be one file with many chapters or many files as one book; both are mapped through global positions.
                    notificationBookId = bookId
                    notificationFiles = files
                    notificationPlayer.updateBookTimeline(bookId, files, chapters)
                }
            }
        }
    }

    @OptIn(UnstableApi::class)
    private fun isUnavailableMediaError(error: PlaybackException): Boolean {
        val isIoError = when (error.errorCode) {
            PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
            PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE,
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
            PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
            PlaybackException.ERROR_CODE_IO_NO_PERMISSION,
            PlaybackException.ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED,
            PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE -> true
            else -> false
        }
        // Parser errors mean the file was opened but malformed; keep that separate from missing/unavailable media.
        return isIoError && error.cause !is androidx.media3.common.ParserException
    }

    private fun handleUnavailableMediaItem(player: Player) {
        val mediaItem = player.currentMediaItem ?: return
        val mediaId = mediaItem.mediaId
        if (!mediaId.contains(":")) return
        val bookId = mediaId.substringBefore(":")
        val queueIndex = player.currentMediaItemIndex.coerceAtLeast(0)
        val skipKey = "$bookId:$queueIndex"
        if (unavailableSkipKey == skipKey) return
        unavailableSkipKey = skipKey

        serviceScope.launch {
            // The service owns playback failures: mark the bad file, notify once, then continue if possible.
            libraryRepository.markPlaybackFileUnavailable(bookId, queueIndex)
            Toast.makeText(this@PlaybackService, "文件不可用", Toast.LENGTH_SHORT).show()

            val next = libraryRepository.findNextAvailablePlaybackFile(bookId, queueIndex)
            if (next != null) {
                val (nextIndex, _) = next
                player.seekTo(nextIndex, 0L)
                player.prepare()
                player.play()
            } else {
                // If there is no later available file, stop instead of looping on the same broken item.
                player.pause()
                player.stop()
            }
        }
    }

    @UnstableApi
    private inner class CustomCallback : MediaSession.Callback {
        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo
        ): MediaSession.ConnectionResult {
            val sessionCommands = MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS.buildUpon()
                .add(rewindButton.sessionCommand!!)
                .add(forwardButton.sessionCommand!!)
                .add(bookmarkButton.sessionCommand!!)
                .build()

            val customLayout = listOf(rewindButton, forwardButton, bookmarkButton)

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
                    val mediaId = player.currentMediaItem?.mediaId
                    if (mediaId != null && mediaId.contains(":")) {
                        val bookId = mediaId.substringBefore(":")

                        serviceScope.launch {
                            // 书签命令来自真实或通知 session 时都保存全书位置，避免保存章节相对位置。
                            val positionMs = (session.player as? NotificationProgressPlayer)
                                ?.currentGlobalPosition()
                                ?: currentGlobalPosition(session.player, bookId)
                            libraryRepository.addBookmark(bookId, positionMs, "Bookmark")
                            Toast.makeText(this@PlaybackService, "Bookmark saved", Toast.LENGTH_SHORT).show()
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
            // 系统通知只使用通知专用 session；真实 UI session 不再生成第二套通知或虚拟进度。
            super.onUpdateNotification(session, startInForegroundRequired)
        }
    }

    private suspend fun currentGlobalPosition(player: Player, bookId: String): Long {
        val fileIndex = player.currentMediaItemIndex.coerceAtLeast(0)
        val positionInFile = player.currentPosition.coerceAtLeast(0L)
        val files = notificationFiles.takeIf { notificationBookId == bookId && it.isNotEmpty() }
            ?: libraryRepository.getFilesForBookSync(bookId)
        // 通知以外的命令也用同一套真实文件到全书位置映射。
        return if (files.isNotEmpty()) {
            PositionMapper.fileToGlobalPosition(fileIndex, positionInFile, files)
                .coerceIn(0L, files.sumOf { it.durationMs }.coerceAtLeast(0L))
        } else {
            positionInFile
        }
    }

    override fun onDestroy() {
        serviceScope.cancel()
        notificationSession?.run {
            // 通知 session 只包装同一个 ExoPlayer，先释放 session，避免重复释放 player。
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