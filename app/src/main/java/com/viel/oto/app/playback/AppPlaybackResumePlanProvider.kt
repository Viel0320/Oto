package com.viel.oto.app.playback

import com.viel.oto.application.usecase.BuildPlaybackPlanUseCase
import com.viel.oto.media.BookPlaybackPlan
import com.viel.oto.media.service.PlaybackResumePlanProvider

/**
 * Bridges MediaSession resumption into the existing application playback-plan use case.
 *
 * Keeping this adapter in the app module prevents the service module from depending on
 * application-layer implementation classes while preserving current resume behavior.
 */
class AppPlaybackResumePlanProvider(
    private val buildPlaybackPlanUseCase: BuildPlaybackPlanUseCase
) : PlaybackResumePlanProvider {
    override suspend fun buildPlaybackPlan(bookId: String): BookPlaybackPlan? =
        buildPlaybackPlanUseCase(bookId)
}
