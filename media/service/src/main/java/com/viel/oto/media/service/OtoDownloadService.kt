package com.viel.oto.media.service

import android.app.Notification
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadService
import androidx.media3.exoplayer.scheduler.Scheduler
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

internal const val DOWNLOAD_NOTIFICATION_ID = 4213
internal const val DOWNLOAD_NOTIFICATION_CHANNEL_ID = "manual_downloads"
internal const val DOWNLOAD_NOTIFICATION_GROUP = "manual_downloads_group"

/**
 * Hosts Media3 offline download execution.
 *
 * Media3 requires a concrete DownloadService to own foreground-service lifecycle, notifications,
 * and restart commands, while Oto keeps book-level commands inside the application download
 * module through DownloadController.
 */
@OptIn(UnstableApi::class)
class OtoDownloadService : DownloadService(
    DOWNLOAD_NOTIFICATION_ID,
    DEFAULT_FOREGROUND_NOTIFICATION_UPDATE_INTERVAL,
    DOWNLOAD_NOTIFICATION_CHANNEL_ID,
    R.string.media_service_download_channel_name,
    0
), KoinComponent {
    private val injectedDownloadManager: DownloadManager by inject()
    private val launchIntentFactory: MediaServiceLaunchIntentFactory by inject()
    private val notificationResources: DownloadNotificationResources by inject()

    private val notificationHelper by lazy {
        DownloadNotificationHelper(
            context = this,
            channelId = DOWNLOAD_NOTIFICATION_CHANNEL_ID,
            launchIntentFactory = launchIntentFactory,
            notificationResources = notificationResources
        )
    }

    override fun getDownloadManager(): DownloadManager =
        injectedDownloadManager

    override fun getScheduler(): Scheduler? =
        null

    override fun getForegroundNotification(downloads: List<Download>, notMetRequirements: Int): Notification =
        notificationHelper.buildForegroundNotification(downloads, notMetRequirements)
}
