package com.viel.oto.media.service

import com.viel.oto.media.BookPlaybackPlan

/**
 * Builds a playback plan for MediaSession resumption without exposing application use cases here.
 *
 * The app module adapts `BuildPlaybackPlanUseCase` to this contract, keeping service startup and
 * platform callbacks independent from application-layer implementation classes.
 */
interface PlaybackResumePlanProvider {
    suspend fun buildPlaybackPlan(bookId: String): BookPlaybackPlan?
}
