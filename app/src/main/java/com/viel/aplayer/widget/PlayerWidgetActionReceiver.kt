package com.viel.aplayer.widget

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.viel.aplayer.logger.SecureLog
import com.viel.aplayer.media.service.PlaybackService

/**
 * Non-exported broadcast receiver for desktop widget playback actions.
 *
 * Core Responsibilities:
 * 1. Configured physically with exported="false" to only receive and intercept process-safe media control action broadcasts dispatched by internal PendingIntents.
 * 2. Asynchronously establishes a MediaController handshake and binds the playback service within an isolated sandbox environment, sealing vulnerabilities that allow third-party apps to launch or manipulate the service.
 */
class PlayerWidgetActionReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "PlayerWidgetActionReceiver"

        // Custom playback action constants. Defines play/pause, skip backward, and skip forward intents migrated from the main widget receiver.
        const val ACTION_PLAY_PAUSE = "com.viel.aplayer.widget.ACTION_PLAY_PAUSE"
        const val ACTION_REWIND = "com.viel.aplayer.widget.ACTION_REWIND"
        const val ACTION_FORWARD = "com.viel.aplayer.widget.ACTION_FORWARD"
    }

    @OptIn(UnstableApi::class)
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return

        // Sandbox command processing. Safely process the custom playback control broadcast within an isolated sandboxed scope.
        if (action in listOf(ACTION_PLAY_PAUSE, ACTION_REWIND, ACTION_FORWARD)) {
            val pendingResult = goAsync() // Spawn asynchronous broadcast handler to prevent blocking the main thread during controller handshake.

            try {
                // Service binding context resolution. Use context.applicationContext to bypass bindService limitations imposed on RestrictedContext by the framework.
                val appContext = context.applicationContext

                // 1. Build a SessionToken targeting the background PlaybackService.
                val sessionToken = SessionToken(
                    appContext,
                    ComponentName(appContext, PlaybackService::class.java)
                )

                // 2. Asynchronously construct the MediaController instance.
                val controllerFuture = MediaController.Builder(appContext, sessionToken).buildAsync()
                
                controllerFuture.addListener({
                    try {
                        val controller = controllerFuture.get()
                        
                        // 3. Execute the corresponding action on the MediaController according to the received intent.
                        when (action) {
                            ACTION_PLAY_PAUSE -> {
                                if (controller.isPlaying) {
                                    controller.pause()
                                } else {
                                    controller.play()
                                }
                            }
                            ACTION_REWIND -> {
                                controller.seekBack() // Invoke standard Media3 backward seek.
                            }
                            ACTION_FORWARD -> {
                                controller.seekForward() // Invoke standard Media3 forward seek.
                            }
                        }

                        // 4. Safely release the MediaController connection once completed.
                        MediaController.releaseFuture(controllerFuture)
                    } catch (e: Exception) {
                        // Release Error Boundary (Sanitize widget command dispatch failures)
                        // MediaController exceptions can include service or URI details, so retained errors must pass through SecureLog.
                        SecureLog.error(TAG, "MediaController exception during widget command dispatch: ${e.message}", e)
                    } finally {
                        pendingResult.finish() // Explicitly terminate async broadcast cycle to prevent intent receiver leaks.
                    }
                }, context.mainExecutor)
            } catch (e: Exception) {
                // Release Error Boundary (Sanitize widget controller initialization failures)
                // Initialization errors are useful for diagnostics but should not retain raw platform exception text.
                SecureLog.error(TAG, "Failed to initialize widget media controller: ${e.message}", e)
                pendingResult.finish()
            }
        }
    }
}
