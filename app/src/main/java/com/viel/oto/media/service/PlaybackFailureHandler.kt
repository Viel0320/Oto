package com.viel.oto.media.service

import androidx.annotation.OptIn
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.viel.oto.data.AppSettingsRepository
import com.viel.oto.data.availability.BookAvailabilityGateway
import com.viel.oto.logger.SecureLog
import com.viel.oto.media.PlaybackDomainEvent
import com.viel.oto.media.PlaybackDomainEventSink
import com.viel.oto.media.PlaybackMediaId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Safeguards cleartext network compliance and intercepts database state changes on file missing.
 * Captures core I/O errors, halts looping reloads on error, and broadcasts unavailable track indicators to control interfaces.
 * Decouples system recovery complexity and storage validation limits from the core player service domain.
 */
@OptIn(UnstableApi::class)
class PlaybackFailureHandler(
    private val serviceScope: CoroutineScope,
    private val bookAvailabilityGateway: BookAvailabilityGateway,
    private val settingsRepository: AppSettingsRepository,
    private val playbackEventSink: PlaybackDomainEventSink
) {
    private var unavailableSkipKey: String? = null

    /**
     * Determines if the exception represents a dynamic file or network accessibility failure.
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
        return isIoError && error.cause !is androidx.media3.common.ParserException
    }

    /**
     * Pauses playback, flags db entries, and emits playback-domain recovery events.
     */
    fun handleUnavailableMediaItem(player: Player) {
        val mediaItem = player.currentMediaItem ?: return
        val mediaParts = PlaybackMediaId.parse(mediaItem.mediaId) ?: return
        val bookId = mediaParts.bookId
        val queueIndex = player.currentMediaItemIndex.coerceAtLeast(0)
        val bookTitle = mediaItem.mediaMetadata.title?.toString()

        val skipKey = "$bookId:$queueIndex"
        if (unavailableSkipKey == skipKey) return
        unavailableSkipKey = skipKey

        serviceScope.launch {
            val currentUri = mediaItem.localConfiguration?.uri?.toString() ?: ""
            if (currentUri.startsWith("http://")) {
                val isAllowed = settingsRepository.settingsFlow.first().isCleartextTrafficAllowed
                if (!isAllowed) {
                    playbackEventSink.emit(PlaybackDomainEvent.CleartextPlaybackBlocked(bookTitle = bookTitle))
                    player.pause()
                    player.stop()
                    SecureLog.warn("FailureHandler", "安全拦截：用户未授权播放明文 HTTP 协议音频流")
                    return@launch
                }
            }

            bookAvailabilityGateway.refreshPlaybackFileUnavailableStatus(bookId, queueIndex)

            player.pause()
            player.stop()

            playbackEventSink.emit(PlaybackDomainEvent.TrackUnavailable(bookId, queueIndex, bookTitle))
            com.viel.oto.logger.PlaybackFailureLogger.logTrackMarkedUnavailable(skipKey)
        }
    }

    /**
     * Reports source load failures before playback has ever started.
     * Stops the player and shows a direct user-facing error without marking files missing, running remote availability retries, or triggering track-skip recovery.
     */
    fun handleInitialMediaLoadFailure(player: Player, error: PlaybackException) {
        serviceScope.launch {
            player.pause()
            player.stop()
            val message = error.localizedMessage?.takeIf { it.isNotBlank() }
                ?: error.message?.takeIf { it.isNotBlank() }
                ?: "未知错误"
            val bookTitle = player.currentMediaItem?.mediaMetadata?.title?.toString()
            playbackEventSink.emit(PlaybackDomainEvent.InitialMediaLoadFailed(message, bookTitle))
            SecureLog.warn("FailureHandler", "媒体源载入前失败，已跳过播放中恢复流程: code=${error.errorCode}, message=$message", error)
        }
    }

    /**
     * Clears the active debounce recovery key once the player transitions to a valid item.
     */
    fun clearSkipGuard() {
        unavailableSkipKey = null
    }
}
