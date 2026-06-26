package com.viel.oto.application.usecase

import com.viel.oto.application.playback.PlayerPlaybackController

/**
 * UI-facing command for preparing a book in the playback runtime.
 * The use case keeps BookPlaybackPlan inside the application/media collaboration so UI scenes only
 * receive the small summary needed for timing logs and post-load cover refresh.
 */
class PrepareBookPlaybackUseCase(
    private val buildPlaybackPlanUseCase: BuildPlaybackPlanUseCase,
    private val playbackController: PlayerPlaybackController
) {
    /**
     * Builds and loads the media playback plan for the requested book.
     * Returning a summary preserves existing load instrumentation without exposing media-layer plan
     * models to the UI module.
     */
    suspend operator fun invoke(bookId: String, playWhenReady: Boolean): PreparedBookPlaybackPlan? {
        val plan = buildPlaybackPlanUseCase(bookId) ?: return null
        playbackController.loadPlaybackPlan(plan, playWhenReady)
        return PreparedBookPlaybackPlan(
            bookId = plan.bookId,
            fileCount = plan.files.size,
            startGlobalPositionMs = plan.startGlobalPositionMs
        )
    }
}

/**
 * Application-level playback load summary.
 * It intentionally excludes media source, URI, and subtitle details because callers only need
 * stable diagnostics once the playback runtime has accepted the plan.
 */
data class PreparedBookPlaybackPlan(
    val bookId: String,
    val fileCount: Int,
    val startGlobalPositionMs: Long
)
