package com.viel.aplayer.application.di.dependencies

import com.viel.aplayer.abs.sync.AbsCatalogSynchronizer
import com.viel.aplayer.data.gateway.ScanScheduler
import com.viel.aplayer.event.AppEventSink

/**
 * Library Sync Worker Dependencies (Background local-library sync dependency view)
 * Exposes only the scan scheduler needed by WorkManager library refresh jobs.
 */
interface LibrarySyncWorkerDependencies {
    /**
     * Scanner Gateway Adapter (Background library sync command seam)
     * Lets WorkManager trigger scan work without learning UI facade or playback dependencies.
     */
    val scanScheduler: ScanScheduler
}

/**
 * ABS Sync Worker Dependencies (Background Audiobookshelf sync dependency view)
 * Exposes only the feedback sink and catalog synchronizer required by ABS WorkManager jobs.
 */
interface AbsSyncWorkerDependencies {
    /**
     * Application Event Sink (Background user-feedback command seam)
     * Allows sync workers to report root preflight failures without importing playback event streams.
     */
    val appEventSink: AppEventSink

    /**
     * ABS Catalog Synchronizer (Root-scoped remote mirror processor)
     * Lets sync workers run catalog mirroring without seeing settings, playback, or VFS dependencies.
     */
    val absCatalogSynchronizer: AbsCatalogSynchronizer
}
