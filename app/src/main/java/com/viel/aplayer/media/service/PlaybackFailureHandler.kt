package com.viel.aplayer.media.service

import android.util.Log
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.viel.aplayer.data.AppSettingsRepository
import com.viel.aplayer.data.gateway.BookAvailabilityGateway
import com.viel.aplayer.media.PlaybackDomainEvent
import com.viel.aplayer.media.PlaybackDomainEventSink
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
    private val serviceScope: CoroutineScope,
    private val bookAvailabilityGateway: BookAvailabilityGateway,
    private val settingsRepository: AppSettingsRepository,
    // Playback Domain Event Sink (Reports recovery facts without rendering Android UI)
    // Failure handling stays inside media service logic while the application bridge owns Toast and dialog decisions.
    private val playbackEventSink: PlaybackDomainEventSink
) {
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
     * Error Recovery Routine (Pauses playback, flags db entries, and emits playback-domain recovery events)
     */
    fun handleUnavailableMediaItem(player: Player) {
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
                    playbackEventSink.emit(PlaybackDomainEvent.CleartextPlaybackBlocked)
                    player.pause()
                    player.stop()
                    Log.w("FailureHandler", "安全拦截：用户未授权播放明文 HTTP 协议音频流")
                    return@launch
                }
            }

            // 2. Failed Track Status Refresh (Revalidates and persists the failing queue item's availability)
            // Remote tracks may recover after a transient error, so the gateway call explicitly refreshes status instead of blindly marking missing.
            bookAvailabilityGateway.refreshPlaybackFileUnavailableStatus(bookId, queueIndex)
            
            // Halt Loop Reloads (Enforces immediate playback pause and stop to intercept ExoPlayer's infinite buffer loop)
            player.pause()
            player.stop()
            
            // Track Recovery Event (Publish a playback-domain fact for the app bridge)
            // The bridge pairs this with the existing skip-confirmation dialog without importing UI models here.
            playbackEventSink.emit(PlaybackDomainEvent.TrackUnavailable(bookId, queueIndex))
            com.viel.aplayer.logger.PlaybackFailureLogger.logTrackMarkedUnavailable(skipKey)
        }
    }

    /**
     * Initial Media Load Failure (Reports source load failures before playback has ever started)
     * Stops the player and shows a direct user-facing error without marking files missing, running remote availability retries, or triggering track-skip recovery.
     */
    fun handleInitialMediaLoadFailure(player: Player, error: PlaybackException) {
        serviceScope.launch {
            player.pause()
            player.stop()
            val message = error.localizedMessage?.takeIf { it.isNotBlank() }
                ?: error.message?.takeIf { it.isNotBlank() }
                ?: "未知错误"
            playbackEventSink.emit(PlaybackDomainEvent.InitialMediaLoadFailed(message))
            Log.w("FailureHandler", "媒体源载入前失败，已跳过播放中恢复流程: code=${error.errorCode}, message=$message")
        }
    }

    /**
     * Guard Reset Handler (Clears the active debounce recovery key once the player transitions to a valid item)
     */
    fun clearSkipGuard() {
        unavailableSkipKey = null
    }
}
