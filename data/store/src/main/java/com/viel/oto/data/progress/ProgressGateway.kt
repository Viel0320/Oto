package com.viel.oto.data.progress

import com.viel.oto.data.entity.BookProgressEntity

/**
 * ProgressGateway.
 * Focuses on audiobook playback position updates and memory persistence.
 *
 * Core Design Goals:
 * 1. Fine-Grained Interface Design: Isolates high-frequency player progress database insertions from generic book metadata and scanner operations.
 * 2. Support Standalone Testing: Decouples the media player controller so it depends solely on this interface, avoiding database transaction overhead in generic repositories.
 */
interface ProgressGateway {

    /**
     * High-frequency progress persistence.
     * Maps global progress millisecond offsets to physical track positions, scheduling asynchronous database writes safely.
     */
    fun updateProgress(bookId: String, position: Long)

    /**
     * Explicit position override.
     * Explicitly saves or overwrites a playback progress record in the database.
     */
    suspend fun saveProgress(progress: BookProgressEntity)

    /**
     * Ordering-aware checkpoint persistence.
     * Returns whether the checkpoint was accepted so playback sync callers can avoid propagating stale local snapshots to remote services.
     */
    suspend fun saveProgressIfNewer(progress: BookProgressEntity): Boolean {
        saveProgress(progress)
        return true
    }

    /**
     * Cold start resumption helper.
     * Synchronously queries the most recent playback progress record to restore player states upon application boot.
     */
    suspend fun getLastPlayedProgressSync(): BookProgressEntity?

    /**
     * Targeted progress lookup for a single audiobook.
     * Provides conflict-resolution flows with the current local checkpoint without expanding the gateway into catalog queries.
     */
    suspend fun getProgressForBookSync(bookId: String): BookProgressEntity?

}
