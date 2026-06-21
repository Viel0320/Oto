package com.viel.aplayer.di.dependencies

import com.viel.aplayer.application.download.DownloadCacheAccess
import com.viel.aplayer.application.download.DownloadController
import com.viel.aplayer.application.download.DownloadRuntimeGateway

interface DownloadRuntimeDependencies {
    /**
     * Narrow manual-download runtime seam.
     * Callers can submit, remove, pause, resume, and reconfigure downloads without receiving the raw Media3 DownloadManager.
     */
    val downloadRuntimeGateway: DownloadRuntimeGateway

    /**
     * Manual-cache playback seam.
     * Playback can resolve the L1 manual cache handle without constructing DownloadManager, observers, or download service state.
     */
    val downloadCacheAccess: DownloadCacheAccess
}

interface ManualDownloadNotificationActionDependencies {
    /**
     * Book-level command surface for notification action buttons.
     * Notification receivers can pause, resume, retry, or cancel one task without receiving broader UI or Media3 runtime dependencies.
     */
    val downloadController: DownloadController
}
