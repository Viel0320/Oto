package com.viel.oto.media.service

import com.viel.oto.media.BookPlaybackPlan

/**
 * Builds a playback plan for MediaSession resumption without coupling PlaybackService to a use case.
 *
 * Media service owns the platform callback and injects this narrow contract, while the concrete
 * adapter can call the application playback-plan use case.
 */
interface PlaybackResumePlanProvider {
    suspend fun buildPlaybackPlan(bookId: String): BookPlaybackPlan?
}
