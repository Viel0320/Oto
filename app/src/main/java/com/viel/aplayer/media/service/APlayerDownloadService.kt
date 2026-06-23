package com.viel.aplayer.media.service

import android.app.Notification
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadService
import androidx.media3.exoplayer.scheduler.Scheduler
import com.viel.aplayer.R
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

internal const val DOWNLOAD_NOTIFICATION_ID = 4213
internal const val DOWNLOAD_NOTIFICATION_CHANNEL_ID = "manual_downloads"
internal const val DOWNLOAD_NOTIFICATION_GROUP = "manual_downloads_group"

/**
 * Hosts Media3 offline download execution.
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
), KoinComponent {
    private val injectedDownloadManager: DownloadManager by inject()

    private val notificationHelper by lazy {
        DownloadNotificationHelper(this, DOWNLOAD_NOTIFICATION_CHANNEL_ID)
    }

    override fun getDownloadManager(): DownloadManager =
        injectedDownloadManager

    override fun getScheduler(): Scheduler? =
        null

    override fun getForegroundNotification(downloads: List<Download>, notMetRequirements: Int): Notification =
        notificationHelper.buildForegroundNotification(downloads, notMetRequirements)
}
