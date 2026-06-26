package com.viel.oto.media.service

import com.viel.oto.application.usecase.BuildPlaybackPlanUseCase
import com.viel.oto.media.BookPlaybackPlan

/**
 * Bridges MediaSession resumption into the application playback-plan use case.
 *
 * Media service owns the platform resumption callback, while application remains the owner of
 * book-level playback-plan construction.
 */
class ApplicationPlaybackResumePlanProvider(
    private val buildPlaybackPlanUseCase: BuildPlaybackPlanUseCase
) : PlaybackResumePlanProvider {
    override suspend fun buildPlaybackPlan(bookId: String): BookPlaybackPlan? =
        buildPlaybackPlanUseCase(bookId)
}
