package com.viel.oto.widget

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.viel.oto.logger.SecureLog

/**
 * Non-exported broadcast receiver for desktop widget playback actions.
 *
 * The receiver is non-exported and only handles app-authored widget PendingIntent broadcasts. It
 * creates a short-lived MediaController handshake for each command, then releases it after dispatch.
 */
class PlayerWidgetActionReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "PlayerWidgetActionReceiver"

        const val ACTION_PLAY_PAUSE = "com.viel.oto.widget.ACTION_PLAY_PAUSE"
        const val ACTION_REWIND = "com.viel.oto.widget.ACTION_REWIND"
        const val ACTION_FORWARD = "com.viel.oto.widget.ACTION_FORWARD"

        private const val PLAYBACK_SERVICE_CLASS_NAME = "com.viel.oto.media.service.PlaybackService"
    }

    @OptIn(UnstableApi::class)
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return

        if (action in listOf(ACTION_PLAY_PAUSE, ACTION_REWIND, ACTION_FORWARD)) {
            val pendingResult = goAsync()

            try {
                val appContext = context.applicationContext

                val sessionToken = playbackSessionToken(appContext)

                val controllerFuture = MediaController.Builder(appContext, sessionToken).buildAsync()

                controllerFuture.addListener({
                    try {
                        val controller = controllerFuture.get()

                        when (action) {
                            ACTION_PLAY_PAUSE -> {
                                if (controller.isPlaying) {
                                    controller.pause()
                                } else {
                                    controller.play()
                                }
                            }
                            ACTION_REWIND -> {
                                controller.seekBack()
                            }
                            ACTION_FORWARD -> {
                                controller.seekForward()
                            }
                        }

                        MediaController.releaseFuture(controllerFuture)
                    } catch (e: Exception) {
                        SecureLog.error(TAG, "MediaController exception during widget command dispatch: ${e.message}", e)
                    } finally {
                        pendingResult.finish()
                    }
                }, context.mainExecutor)
            } catch (e: Exception) {
                SecureLog.error(TAG, "Failed to initialize widget media controller: ${e.message}", e)
                pendingResult.finish()
            }
        }
    }

    /**
     * Resolves the app-owned MediaSessionService by manifest class name.
     *
     * The receiver owns widget command entrypoints, but the service implementation stays in
     * `:media:service`; using a component string keeps this module off that implementation classpath.
     */
    private fun playbackSessionToken(context: Context): SessionToken =
        SessionToken(
            context,
            ComponentName(context.packageName, PLAYBACK_SERVICE_CLASS_NAME)
        )
}
