package com.viel.aplayer.data.gateway

import com.viel.aplayer.data.entity.BookFileEntity
import com.viel.aplayer.data.entity.BookProgressEntity

/**
 * Decoupled Domain Gateway Interface (ProgressGateway)
 * Focuses on audiobook playback position updates, memory persistence, and multi-track physical availability transitions.
 * 
 * Core Design Goals:
 * 1. Fine-Grained Interface Design: Isolates high-frequency player progress database insertions and disaster recovery track-hopping computations from generic book metadata and scanner operations.
 * 2. Support Standalone Testing: Decouples the media player controller so it depends solely on this interface, avoiding database transaction overhead in generic repositories.
 */
interface ProgressGateway {

    /**
     * Update Playback Position (High-frequency progress persistence)
     * Maps global progress millisecond offsets to physical track positions, scheduling asynchronous database writes safely.
     */
    fun updateProgress(bookId: String, position: Long)

    /**
     * Save Progress Entity (Explicit position override)
     * Explicitly saves or overwrites a playback progress record in the database.
     */
    suspend fun saveProgress(progress: BookProgressEntity)

    /**
     * Fetch Last Played Progress (Cold start resumption helper)
     * Synchronously queries the most recent playback progress record to restore player states upon application boot.
     */
    suspend fun getLastPlayedProgressSync(): BookProgressEntity?

    /**
     * Fetch Book Progress (Targeted progress lookup for a single audiobook)
     * Provides conflict-resolution flows with the current local checkpoint without expanding the gateway into catalog queries.
     */
    suspend fun getProgressForBookSync(bookId: String): BookProgressEntity?

    /**
     * Check Playback File Availability (VFS file system verification)
     * Validates physical reachability of the currently active audiobook track via VFS.
     */
    suspend fun checkCurrentPlaybackFileAvailability(bookId: String): Boolean

    /**
     * Mark Track Unavailable (Ingestion disaster handling)
     * Flags a specific audio track within an audiobook as corrupted or physically missing.
     */
    suspend fun markPlaybackFileUnavailable(bookId: String, queueIndex: Int)

    /**
     * Disaster Track Hopping (Graceful skip failover)
     * Searches the subsequent track sequence to match the first available, readable audio file upon failure.
     */
    suspend fun findNextAvailablePlaybackFile(
        bookId: String,
        afterQueueIndex: Int
    ): Pair<Int, BookFileEntity>?
}
