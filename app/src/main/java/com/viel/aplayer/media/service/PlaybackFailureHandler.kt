package com.viel.aplayer.media.service

import android.content.Context
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import com.viel.aplayer.data.AppSettingsRepository
import com.viel.aplayer.data.gateway.ProgressGateway
import com.viel.aplayer.media.PlaybackMediaId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Playback Disaster Recovery Handler (Safeguards cleartext network compliance and intercepts database state changes on file missing)
 * Captures core I/O errors, halts looping reloads on error, and broadcasts unavailable track indicators to control interfaces.
 * Decouples system recovery complexity and storage validation limits from the core player service domain.
 */
@UnstableApi
class PlaybackFailureHandler(
    context: Context,
    private val serviceScope: CoroutineScope,
    private val progressGateway: ProgressGateway,
    private val settingsRepository: AppSettingsRepository
) {
    // Application Context Cache (Extracts application context once to avoid service or activity memory leaks)
    private val appContext = context.applicationContext

    // Debounce Guard Key (Format: "bookId:queueIndex" to prevent infinite loops of repeated error events)
    private var unavailableSkipKey: String? = null

    /**
     * Error Accessibility Assessment (Determines if the exception represents a dynamic file or network accessibility failure)
     * Filters out standard format parsing exceptions, isolating pure physical medium delivery errors.
     */
    fun isUnavailableMediaError(error: PlaybackException): Boolean {
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
        // Parser Filter Rule (Distinguishes raw format corruption from actual storage or network delivery failures)
        return isIoError && error.cause !is androidx.media3.common.ParserException
    }

    /**
     * Error Recovery Routine (Pauses playback, flags db entries, and broadcasts UI events to prompt for user choice)
     */
    fun handleUnavailableMediaItem(player: Player, mediaSession: MediaSession?) {
        val mediaItem = player.currentMediaItem ?: return
        val mediaParts = PlaybackMediaId.parse(mediaItem.mediaId) ?: return
        val bookId = mediaParts.bookId
        val queueIndex = player.currentMediaItemIndex.coerceAtLeast(0)
        
        // Debounce Validation (Ensures each failing segment goes through the validation logic only once to avoid crashing loop)
        val skipKey = "$bookId:$queueIndex"
        if (unavailableSkipKey == skipKey) return
        unavailableSkipKey = skipKey

        serviceScope.launch {
            // 1. Cleartext Traffic Security Audit
            val currentUri = mediaItem.localConfiguration?.uri?.toString() ?: ""
            if (currentUri.startsWith("http://")) {
                val isAllowed = settingsRepository.settingsFlow.first().isCleartextTrafficAllowed
                if (!isAllowed) {
                    Toast.makeText(appContext, "安全拦截：明文 HTTP 播放未授权。请在设置中允许。", Toast.LENGTH_LONG).show()
                    player.pause()
                    player.stop()
                    Log.w("FailureHandler", "安全拦截：用户未授权播放明文 HTTP 协议音频流")
                    return@launch
                }
            }

            // 2. Persistence State Serialization (Flags the failing book track segment as unavailable in Room repository)
            progressGateway.markPlaybackFileUnavailable(bookId, queueIndex)
            
            // Halt Loop Reloads (Enforces immediate playback pause and stop to intercept ExoPlayer's infinite buffer loop)
            player.pause()
            player.stop()
            
            // User Alert Prompt (Triggers toast indication to ensure listener awareness before the dialog resolves)
            Toast.makeText(appContext, "当前分轨文件不可用，请确认是否跳轨收听", Toast.LENGTH_LONG).show()
            com.viel.aplayer.logger.PlaybackFailureLogger.logTrackMarkedUnavailable(skipKey)

            // 3. Media Session Event Broadcast (Dispatches custom EVENT_TRACK_UNAVAILABLE event to notify foreground screen views)
            val args = Bundle().apply {
                putString("bookId", bookId)
                putInt("queueIndex", queueIndex)
            }
            mediaSession?.broadcastCustomCommand(SessionCommand("EVENT_TRACK_UNAVAILABLE", Bundle.EMPTY), args)
        }
    }

    /**
     * Guard Reset Handler (Clears the active debounce recovery key once the player transitions to a valid item)
     */
    fun clearSkipGuard() {
        unavailableSkipKey = null
    }
}
