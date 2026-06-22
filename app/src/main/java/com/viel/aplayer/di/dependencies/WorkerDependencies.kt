package com.viel.aplayer.di.dependencies

import com.viel.aplayer.abs.sync.AbsCatalogSynchronizer
import com.viel.aplayer.data.scan.ScanScheduler
import com.viel.aplayer.event.AppEventSink

/**
 * Background local-library sync dependency view.
 * Exposes only the scan scheduler needed by WorkManager library refresh jobs.
 */
interface LibrarySyncWorkerDependencies {
    /**
     * Background library sync command seam.
     * Lets WorkManager trigger scan work without learning UI facade or playback dependencies.
     */
    val scanScheduler: ScanScheduler
}

/**
 * Background AudiobookShelf sync dependency view.
 * Exposes only the feedback sink and catalog synchronizer required by ABS WorkManager jobs.
 */
interface AbsSyncWorkerDependencies {
    /**
     * Background user-feedback command seam.
     * Allows sync workers to report root preflight failures without importing playback event streams.
     */
    val appEventSink: AppEventSink

    /**
     * Root-scoped remote mirror processor.
     * Lets sync workers run catalog mirroring without seeing settings, playback, or VFS dependencies.
     */
    val absCatalogSynchronizer: AbsCatalogSynchronizer
}
