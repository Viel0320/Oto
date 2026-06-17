package com.viel.aplayer.data.availability

import com.viel.aplayer.data.entity.BookFileEntity

/**
 * Book Availability Gateway (Application-facing reachability status seam)
 *
 * Exposes audiobook availability operations separately from cover and metadata work so callers can
 * see from the interface whether a method refreshes stored status or only probes physical reachability.
 */
interface BookAvailabilityGateway {
    /**
     * Refresh Detail Availability Status (Status-writing detail playability check)
     *
     * Reprobes all stored audio tracks, refreshes Room file/book availability rows, and returns whether at least one track remains playable.
     */
    suspend fun refreshDetailAvailabilityStatus(bookId: String): Boolean

    /**
     * Check Primary Audio File Without Status Refresh (Pure reachability probe)
     *
     * Checks the primary audio file for destructive workflows without mutating persisted availability rows.
     */
    suspend fun checkPrimaryAudioFileExistsWithoutStatusRefresh(bookId: String): Boolean

    /**
     * Refresh Current Playback File Availability Status (Status-writing active-track check)
     *
     * Validates the active playback track and refreshes the matching file/book availability rows.
     */
    suspend fun refreshCurrentPlaybackFileAvailabilityStatus(bookId: String): Boolean

    /**
     * Refresh Failed Playback File Availability Status (Failure recovery status update)
     *
     * Revalidates a failed queue item and persists READY or MISSING according to the latest probe result.
     */
    suspend fun refreshPlaybackFileUnavailableStatus(bookId: String, queueIndex: Int)

    /**
     * Find Next Available Playback File And Refresh Status (Graceful skip failover with persisted updates)
     *
     * Searches subsequent tracks and writes availability status for each inspected candidate.
     */
    suspend fun findNextAvailablePlaybackFileAndRefreshStatus(
        bookId: String,
        afterQueueIndex: Int
    ): Pair<Int, BookFileEntity>?
}
