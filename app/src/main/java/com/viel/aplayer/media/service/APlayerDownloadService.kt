package com.viel.aplayer.media.service

import android.app.Notification
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadService
import androidx.media3.exoplayer.scheduler.Scheduler
import com.viel.aplayer.APlayerApplication
import com.viel.aplayer.R

// Download Notification Identity (Reserve a stable foreground notification slot for manual cache work)
// Media3 updates this notification while downloads are active, so the ID must stay stable across service restarts.
internal const val DOWNLOAD_NOTIFICATION_ID = 4213
// Download Notification Channel (Separate offline-cache work from playback media controls)
// Keeping a distinct channel lets Android Settings expose manual download visibility without affecting active playback notifications.
internal const val DOWNLOAD_NOTIFICATION_CHANNEL_ID = "manual_downloads"
// Download Notification Group (Collect foreground summary and per-book progress rows together)
// Android still requires one foreground notification, while book-level notifications carry the user-facing progress details.
internal const val DOWNLOAD_NOTIFICATION_GROUP = "manual_downloads_group"

/**
 * Manual Download Foreground Service (Hosts Media3 offline download execution)
 *
 * Media3 requires a concrete DownloadService to own foreground-service lifecycle, notifications,
 * and restart commands, while APlayer keeps book-level commands inside the application download
 * module through DownloadController.
 */
@UnstableApi
class APlayerDownloadService : DownloadService(
    DOWNLOAD_NOTIFICATION_ID,
    DEFAULT_FOREGROUND_NOTIFICATION_UPDATE_INTERVAL,
    DOWNLOAD_NOTIFICATION_CHANNEL_ID,
    R.string.app_name,
    0
) {
    private val notificationHelper by lazy {
        // Foreground Notification Helper (Separate notification presentation from Media3 service lifecycle)
        // The service owns callbacks and manager access, while the helper owns user-visible notification construction.
        DownloadNotificationHelper(this, DOWNLOAD_NOTIFICATION_CHANNEL_ID)
    }

    override fun getDownloadManager(): DownloadManager =
        // Process Download Runtime (Resolve the same manager owned by DownloadGraph)
        // This keeps service callbacks, controller commands, recovery, and sync listeners on one Media3 DownloadIndex.
        APlayerApplication.getProcessContainer(this).media3DownloadManager

    override fun getScheduler(): Scheduler? =
        // Scheduler Deferral (Keep phase-three service startup minimal until WiFi policy work adds restart constraints)
        // DownloadManager still handles active foreground commands; later stages can add PlatformScheduler without changing controller boundaries.
        null

    override fun getForegroundNotification(downloads: List<Download>, notMetRequirements: Int): Notification =
        notificationHelper.buildForegroundNotification(downloads, notMetRequirements)
}
