package com.viel.aplayer.data.cover

import com.viel.aplayer.data.entity.BookEntity

/**
 * Cover Recovery Gateway (Application-facing cover self-heal seam)
 *
 * Single data-layer entry point for cached-artwork self-healing. It wraps the CoverRecoveryHelper engine so
 * catalog, metadata-refresh, and scan callers no longer hold that concrete class directly. The shared helper keeps
 * its own dedup, presence cache, and regeneration scope, so every method here is a thin idempotent forward to it.
 */
interface CoverRecoveryGateway {
    /**
     * Trigger Recovery (Non-blocking single-book self-heal)
     * Fire-and-forget: returns immediately while any missing artwork is regenerated on the helper's background scope.
     */
    fun triggerRecovery(book: BookEntity)

    /**
     * Force Regenerate (Bypass the failed-attempt cache and rebuild now)
     * Used after a metadata rescan that may expose embedded artwork the presence cache had already written off.
     */
    suspend fun forceRegenerate(bookId: String): Boolean

    /**
     * Recover Missing Covers (Single background sweep over the active catalog)
     *
     * Snapshots the non-deleted books once and replays [triggerRecovery] for each, keeping filesystem presence
     * checks off the first-frame path. Idempotent across cold starts because the helper dedups in-flight work.
     */
    suspend fun recoverMissingCovers()
}
