package com.viel.aplayer.service

import android.os.Bundle
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
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
import com.viel.aplayer.R

class PlaybackService : MediaSessionService() {
    private var mediaSession: MediaSession? = null

    companion object {
        const val ACTION_REWIND = "ACTION_REWIND"
        const val ACTION_FORWARD = "ACTION_FORWARD"
        const val ACTION_BOOKMARK = "ACTION_BOOKMARK"
    }

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()
        
        val player = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                    .setUsage(C.USAGE_MEDIA)
                    .build(),
                true
            )
            .setSeekBackIncrementMs(10000)
            .setSeekForwardIncrementMs(30000)
            .build()

        val rewindButton = CommandButton.Builder(R.drawable.ic_replay_10)
            .setDisplayName("快退10秒")
            .setSessionCommand(SessionCommand(ACTION_REWIND, Bundle.EMPTY))
            .build()

        val forwardButton = CommandButton.Builder(R.drawable.ic_forward_30)
            .setDisplayName("快进30秒")
            .setSessionCommand(SessionCommand(ACTION_FORWARD, Bundle.EMPTY))
            .build()

        val bookmarkButton = CommandButton.Builder(R.drawable.ic_bookmark_add)
            .setDisplayName("添加书签")
            .setSessionCommand(SessionCommand(ACTION_BOOKMARK, Bundle.EMPTY))
            .build()

        mediaSession = MediaSession.Builder(this, player)
            .setCallback(CustomCallback())
            .build()
            
        // 顺序：快退 -> 快进 -> 书签。书签在列表最后，会显示在通知栏的最右侧槽位。
        mediaSession?.setCustomLayout(listOf(rewindButton, forwardButton, bookmarkButton))
    }

    @UnstableApi
    private class CustomCallback : MediaSession.Callback {
        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo
        ): MediaSession.ConnectionResult {
            val sessionCommands = MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS.buildUpon()
                .add(SessionCommand(ACTION_REWIND, Bundle.EMPTY))
                .add(SessionCommand(ACTION_FORWARD, Bundle.EMPTY))
                .add(SessionCommand(ACTION_BOOKMARK, Bundle.EMPTY))
                .build()

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
                    // TODO: 实现书签逻辑
                }
            }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        return mediaSession
    }

    override fun onDestroy() {
        mediaSession?.run {
            player.release()
            release()
            mediaSession = null
        }
        super.onDestroy()
    }
}
