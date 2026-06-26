package com.viel.oto.library.scan

import com.viel.oto.library.scan.ScanOutcome

/**
 * ScanScheduler.
 * Dedicated to scheduling and triggering directory file scans and metadata sync for local and WebDAV library sources.
 *
 * Core Design Goals:
 * 1. Unify Scan Ingestions: Consolidates background periodic rescans and foreground immediate sync operations.
 * 2. Decouple WorkManager dependencies: Isolates low-level concurrency configurations, thread scheduling, and lock controls.
 */
interface ScanScheduler {

    /**
     * Direct sync command.
     * Triggers database updates and file rescan operations immediately, returning the shared scan outcome contract.
     * User-originated commands pass rootIds so the scanner does not fall back to library-wide work.
     *
     * @param trigger Origin indicating sync cause (e.g. "USER", "SYSTEM", "BACKGROUND")
     * @param rootIds Explicit directory roots affected by a user command; cold-start scans leave it empty.
     */
    suspend fun syncLibrary(trigger: String = "USER", rootIds: Set<String> = emptySet()): ScanOutcome

    /**
     * Asynchronous scan dispatch.
     * Cold-start work keeps WorkManager timing, while user commands enter the root-scoped priority
     * lane so repeated manual actions can run newest-first instead of being collapsed.
     *
     * @param trigger Origin indicating sync cause
     * @param requiresNetwork True when the queued scan was caused by a remote-only root mutation.
     * @param rootIds Explicit directory roots affected by a user command; cold-start scans leave it empty.
     */
    fun scheduleLibrarySync(
        trigger: String = "USER",
        requiresNetwork: Boolean = false,
        rootIds: Set<String> = emptySet()
    )
}
