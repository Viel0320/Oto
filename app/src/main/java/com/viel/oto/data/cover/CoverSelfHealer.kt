package com.viel.oto.data.cover

import com.viel.oto.data.entity.BookEntity

/**
 * Cover regeneration contract behind CoverRecoveryHelper.
 *
 * Narrow seam over the cover self-heal engine so data-layer gateways depend on this contract instead of the
 * concrete @UnstableApi helper, and so the behavior can be faked in tests without a real VFS/extractor stack.
 */
interface CoverSelfHealer {
    /**
     * Non-blocking single-book self-heal.
     * Returns immediately; any missing artwork is rebuilt on the implementation's own background scope.
     */
    fun checkAndTriggerCoverRegeneration(book: BookEntity)

    /**
     * Rebuild now, bypassing the failed-attempt cache.
     * Returns whether artwork was rebuilt. Used after a metadata rescan that may expose new embedded covers.
     */
    suspend fun forceRegenerateCover(bookId: String): Boolean
}
