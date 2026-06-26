package com.viel.oto.media.service

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import androidx.annotation.OptIn
import androidx.core.app.NotificationCompat
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.offline.Download

/**
 * Build foreground status for manual offline cache work.
 *
 * Keeping notification assembly outside OtoDownloadService leaves the service focused on Media3
 * lifecycle callbacks and makes future management-screen deep links easier to add.
 */
@OptIn(UnstableApi::class)
internal class DownloadNotificationHelper(
    private val context: Context,
    private val channelId: String,
    private val launchIntentFactory: MediaServiceLaunchIntentFactory,
    private val notificationResources: DownloadNotificationResources
) {
    fun buildForegroundNotification(downloads: List<Download>, notMetRequirements: Int): Notification {
        val hasFailures = downloads.any { download -> download.state == Download.STATE_FAILED }
        val hasActiveDownloads = downloads.any { download ->
            download.state == Download.STATE_QUEUED ||
                download.state == Download.STATE_DOWNLOADING ||
                download.state == Download.STATE_RESTARTING
        }
        val state = when {
            notMetRequirements != 0 -> DownloadForegroundNotificationState.Waiting
            hasFailures -> DownloadForegroundNotificationState.Failed
            hasActiveDownloads -> DownloadForegroundNotificationState.Active
            else -> DownloadForegroundNotificationState.Idle
        }
        return NotificationCompat.Builder(context, channelId)
            .setSmallIcon(notificationResources.smallIconRes)
            .setContentTitle(notificationResources.appName(context))
            .setContentText(notificationResources.foregroundStatus(context, state))
            .setContentIntent(createContentIntent())
            .setOnlyAlertOnce(true)
            .setGroup(DOWNLOAD_NOTIFICATION_GROUP)
            .setGroupSummary(true)
            .setOngoing(hasActiveDownloads)
            .build()
    }

    private fun createContentIntent(): PendingIntent {
        val intent = launchIntentFactory.openAppIntent(context)
        return PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
