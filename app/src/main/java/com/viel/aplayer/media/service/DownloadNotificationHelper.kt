package com.viel.aplayer.media.service

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.offline.Download
import com.viel.aplayer.MainActivity
import com.viel.aplayer.R

/**
 * Download Notification Helper (Build foreground status for manual offline cache work)
 *
 * Keeping notification assembly outside APlayerDownloadService leaves the service focused on Media3
 * lifecycle callbacks and makes future management-screen deep links easier to add.
 */
@UnstableApi
internal class DownloadNotificationHelper(
    private val context: Context,
    private val channelId: String
) {
    fun buildForegroundNotification(downloads: List<Download>, notMetRequirements: Int): Notification {
        val hasFailures = downloads.any { download -> download.state == Download.STATE_FAILED }
        val hasActiveDownloads = downloads.any { download ->
            download.state == Download.STATE_QUEUED ||
                download.state == Download.STATE_DOWNLOADING ||
                download.state == Download.STATE_RESTARTING
        }
        val contentText = when {
            notMetRequirements != 0 -> context.getString(R.string.download_notification_waiting)
            hasFailures -> context.getString(R.string.download_notification_failed)
            hasActiveDownloads -> context.getString(R.string.download_notification_active)
            else -> context.getString(R.string.download_notification_idle)
        }
        return NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(contentText)
            .setContentIntent(createContentIntent())
            .setOnlyAlertOnce(true)
            .setGroup(DOWNLOAD_NOTIFICATION_GROUP)
            .setGroupSummary(true)
            .setOngoing(hasActiveDownloads)
            .build()
    }

    private fun createContentIntent(): PendingIntent {
        // Download Notification Destination (Return users to the main app shell from foreground download status)
        // The download management UI arrives in a later phase, so the notification targets MainActivity without deep-linking to unfinished screens.
        val intent = Intent(context, MainActivity::class.java)
        return PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
