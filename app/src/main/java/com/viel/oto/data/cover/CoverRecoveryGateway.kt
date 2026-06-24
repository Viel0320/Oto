package com.viel.oto.data.cover

import com.viel.oto.data.entity.BookEntity

/**
 * Application-facing cover self-heal seam.
 *
 * Single data-layer entry point for cached-artwork self-healing. It wraps the CoverRecoveryHelper engine so
 * catalog, metadata-refresh, and scan callers no longer hold that concrete class directly. The shared helper keeps
 * its own dedup, presence cache, and regeneration scope, so every method here is a thin idempotent forward to it.
 */
interface CoverRecoveryGateway {
    /**
     * Non-blocking single-book self-heal.
     * Fire-and-forget: returns immediately while any missing artwork is regenerated on the helper's background scope.
     */
    fun triggerRecovery(book: BookEntity)

    /**
     * Bypass the failed-attempt cache and rebuild now.
     * Used after a metadata rescan that may expose embedded artwork the presence cache had already written off.
     */
    suspend fun forceRegenerate(bookId: String): Boolean

    /**
     * Budgeted background sweep over the active catalog.
     *
     * Samples a bounded set of non-deleted books and replays [triggerRecovery] in small batches, keeping filesystem
     * presence checks and VFS-side recovery work away from the first-frame and early-scroll path.
     */
    suspend fun recoverMissingCovers()
}
