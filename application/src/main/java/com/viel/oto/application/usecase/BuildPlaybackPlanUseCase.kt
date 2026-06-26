package com.viel.oto.application.usecase

import com.viel.oto.application.download.BookCacheState
import com.viel.oto.application.download.BookCacheStatus
import com.viel.oto.application.download.DownloadStatusReadModel
import com.viel.oto.media.BookPlaybackPlan
import com.viel.oto.media.PlaybackBufferPolicy
import com.viel.oto.media.PlaybackPlanGateway
import kotlinx.coroutines.flow.first

/**
 * UI-facing playback startup operation.
 *
 * Combines the playback plan read model with BookCacheStatus so callers receive a Media3-ready
 * source policy without making the media layer depend on application download state.
 */
class BuildPlaybackPlanUseCase(
    private val playbackPlanGateway: PlaybackPlanGateway,
    private val downloadStatusReadModel: DownloadStatusReadModel
) {
    /**
     * Builds the requested book plan and applies the cache-derived buffer policy.
     *
     * LOCAL and COMPLETED statuses are already device-resident, so they use Direct to avoid the
     * streaming memory-buffer target while every other state keeps the configured streaming buffer.
     */
    suspend operator fun invoke(bookId: String): BookPlaybackPlan? {
        val plan = playbackPlanGateway.buildPlaybackPlan(bookId) ?: return null
        val cacheStatus = downloadStatusReadModel.observeBookCacheStatus(bookId).first()
        return plan.copy(bufferPolicy = cacheStatus.toPlaybackBufferPolicy())
    }

    /**
     * Maps the presentation-safe cache projection to the media-only buffering policy.
     */
    private fun BookCacheStatus.toPlaybackBufferPolicy(): PlaybackBufferPolicy =
        when (state) {
            BookCacheState.LOCAL,
            BookCacheState.COMPLETED -> PlaybackBufferPolicy.Direct
            BookCacheState.NONE,
            BookCacheState.QUEUED,
            BookCacheState.DOWNLOADING,
            BookCacheState.PAUSED,
            BookCacheState.FAILED -> PlaybackBufferPolicy.Buffered
        }
}
