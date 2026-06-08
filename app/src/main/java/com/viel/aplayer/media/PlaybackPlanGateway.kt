package com.viel.aplayer.media

/**
 * Playback Plan Gateway (Fine-grained playback-start read model)
 *
 * Exposes only the operation required to materialize a BookPlaybackPlan for media startup.
 * Keeping this interface outside BookCatalogGateway prevents general book reads from inheriting playback startup semantics.
 */
interface PlaybackPlanGateway {
    /**
     * Build Playback Plan (Materialize ordered tracks and resume position)
     *
     * Returns null when the book cannot produce a playable track sequence.
     */
    suspend fun buildPlaybackPlan(bookId: String): BookPlaybackPlan?
}
