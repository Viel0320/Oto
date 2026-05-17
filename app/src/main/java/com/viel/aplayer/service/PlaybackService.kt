package com.viel.aplayer.service

import android.os.Bundle
import android.util.Log
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
import com.viel.aplayer.data.LibraryRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class PlaybackService : MediaSessionService() {
    private var mediaSession: MediaSession? = null
    private lateinit var rewindButton: CommandButton
    private lateinit var forwardButton: CommandButton
    private lateinit var bookmarkButton: CommandButton
    private lateinit var libraryRepository: LibraryRepository
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
                if (error.errorCode == PlaybackException.ERROR_CODE_IO_UNSPECIFIED) {
                    val cause = error.cause
                    if (cause is androidx.media3.common.ParserException) {
                        Log.e("PlaybackService", "Parser exception: contentIsMalformed=${cause.contentIsMalformed}, dataType=${cause.dataType}")
                    }
                }
            }
        })

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
            .setCallback(CustomCallback())
            .build()
            
        // 顺序：快退 -> 快进 -> 书签。书签在列表最后，会显示在通知栏的最右侧槽位。
        mediaSession?.let {
            it.setCustomLayout(listOf(rewindButton, forwardButton, bookmarkButton))
            addSession(it)
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
                        val fileIndex = mediaId.substringAfter(":").toIntOrNull() ?: 0
                        val positionInFile = player.currentPosition.coerceAtLeast(0L)

                        serviceScope.launch {
                            val files = libraryRepository.getFilesForBookSync(bookId)
                            if (files.isNotEmpty()) {
                                val globalPos = com.viel.aplayer.playback.PositionMapper.fileToGlobalPosition(fileIndex, positionInFile, files)
                                libraryRepository.addBookmark(bookId, globalPos, "Bookmark")
                                android.widget.Toast.makeText(this@PlaybackService, "Bookmark saved", android.widget.Toast.LENGTH_SHORT).show()
                            }
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

    override fun onDestroy() {
        serviceScope.cancel()
        mediaSession?.run {
            player.release()
            release()
            mediaSession = null
        }
        super.onDestroy()
    }
}