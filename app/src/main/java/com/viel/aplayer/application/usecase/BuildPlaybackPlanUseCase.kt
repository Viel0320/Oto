package com.viel.aplayer.application.usecase

import com.viel.aplayer.media.BookPlaybackPlan
import com.viel.aplayer.media.PlaybackPlanGateway

/**
 * UI-facing playback startup operation.
 *
 * Gives presentation code a named application operation while playback-core services can still depend
 * directly on the fine-grained PlaybackPlanGateway.
 */
class BuildPlaybackPlanUseCase(
    private val playbackPlanGateway: PlaybackPlanGateway
) {
    /**
     * Forward the requested book into the playback planning seam.
     *
     * The use case name keeps PlayerViewModel from calling a generic book-query gateway for playback startup work.
     */
    suspend operator fun invoke(bookId: String): BookPlaybackPlan? {
        return playbackPlanGateway.buildPlaybackPlan(bookId)
    }
}
