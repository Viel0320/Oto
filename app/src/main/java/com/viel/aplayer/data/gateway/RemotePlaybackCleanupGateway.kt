package com.viel.aplayer.data.gateway

/**
 * Remote Playback Cleanup Gateway (Book-scoped remote playback state pruning seam)
 *
 * Lets destructive book workflows remove remote-backend playback residue without depending on ABS session sync
 * orchestration or wider catalog services.
 */
interface RemotePlaybackCleanupGateway {
    /**
     * Delete Remote Playback State For Book (Prune stale session and pending-progress rows)
     *
     * Removes local remote-playback records tied to a single soft-deleted book so later background flushes cannot
     * discover obsolete ABS state for an audiobook that is no longer visible in the local library.
     */
    suspend fun deletePlaybackStateForBook(bookId: String)
}
