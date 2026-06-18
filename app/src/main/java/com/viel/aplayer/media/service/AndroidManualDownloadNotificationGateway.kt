package com.viel.aplayer.media.service

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.viel.aplayer.MainActivity
import com.viel.aplayer.R
import com.viel.aplayer.application.download.ManualDownloadDisplayTextPolicy
import com.viel.aplayer.application.download.ManualDownloadNotificationGateway
import com.viel.aplayer.data.dao.BookDao
import com.viel.aplayer.data.entity.DownloadMetadataEntity
import com.viel.aplayer.data.entity.DownloadStatus
import com.viel.aplayer.shared.formatFileSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Book Download Notification Gateway (Render one Android progress notification per manual book download)
 *
 * Media3 still owns the foreground summary notification required by DownloadService, while this gateway
 * turns the durable Room aggregate into the user-facing per-book progress row.
 */
class AndroidManualDownloadNotificationGateway(
    context: Context,
    private val bookDao: BookDao
) : ManualDownloadNotificationGateway {
    private val appContext = context.applicationContext
    private val notificationManager = NotificationManagerCompat.from(appContext)

    @SuppressLint("MissingPermission")
    override suspend fun publish(metadata: DownloadMetadataEntity) = withContext(Dispatchers.IO) {
        // Notification Permission Guard (Skip publishing when Android cannot display manual-download progress)
        // UI entry points request POST_NOTIFICATIONS before creating tasks, but this defensive check keeps background sync safe after permission changes.
        if (!canPostNotifications()) return@withContext
        ensureNotificationChannel()
        val book = bookDao.getBookById(metadata.bookId)
        val bookTitle = book?.title?.takeIf { value -> value.isNotBlank() }
            ?: appContext.getString(R.string.common_unknown_title)
        val author = book?.author?.takeIf { value -> value.isNotBlank() }
            ?: appContext.getString(R.string.common_unknown)
        val bookProgressLabel = ManualDownloadNotificationPresentationPolicy.title(
            author = author,
            bookTitle = bookTitle,
            progressPercent = progressPercent(metadata)
        )
        val statusTitle = statusTitle(metadata)
        notificationManager.notify(
            notificationIdForBook(metadata.bookId),
            NotificationCompat.Builder(appContext, DOWNLOAD_NOTIFICATION_CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                // Status Title Promotion (Make the task state text the bold notification title)
                // The book identity remains in subText/contentText so SystemUI can still surface author-title-progress around the status row.
                .setContentTitle(statusTitle)
                // Header SubText Mirror (Feed the same book progress label into SystemUI's notification header slot)
                // Some Android builds render subText where the app name header appears, so this restores the user-visible author-title-progress text there.
                .setSubText(bookProgressLabel)
                .setContentIntent(createContentIntent(metadata.bookId))
                .setOnlyAlertOnce(true)
                .setOngoing(metadata.status in ACTIVE_NOTIFICATION_STATUSES)
                .setAutoCancel(metadata.status in TERMINAL_NOTIFICATION_STATUSES)
                .setGroup(DOWNLOAD_NOTIFICATION_GROUP)
                .setCategory(NotificationCompat.CATEGORY_PROGRESS)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .applyProgress(metadata)
                .applyControlActions(metadata)
                .build()
        )
    }

    override suspend fun cancel(bookId: String) = withContext(Dispatchers.IO) {
        // Book Notification Cancellation (Remove the stable notification slot for a deleted manual task)
        // The same ID mapping is used for publish and cancel so controller deletes and Media3 removal callbacks clear the visible row deterministically.
        notificationManager.cancel(notificationIdForBook(bookId))
    }

    private fun NotificationCompat.Builder.applyProgress(metadata: DownloadMetadataEntity): NotificationCompat.Builder {
        val totalBytes = metadata.totalBytes.coerceAtLeast(0L)
        return when {
            metadata.status == DownloadStatus.COMPLETED -> setProgress(PROGRESS_MAX, PROGRESS_MAX, false)
            totalBytes > 0L -> setProgress(PROGRESS_MAX, progressPercent(metadata), false)
            metadata.status in ACTIVE_NOTIFICATION_STATUSES -> setProgress(0, 0, true)
            else -> setProgress(0, 0, false)
        }
    }

    private fun NotificationCompat.Builder.applyControlActions(metadata: DownloadMetadataEntity): NotificationCompat.Builder {
        // Book Notification Actions (Expose state-specific task controls beside every active book notification)
        // The actions reuse the same controller commands as the management screen, so SystemUI and in-app operations cannot drift.
        ManualDownloadNotificationPresentationPolicy.actionsFor(metadata.status).forEach { action ->
            addAction(
                action.iconRes(),
                action.titleText(),
                ManualDownloadNotificationActionReceiver.pendingIntent(appContext, action, metadata.bookId)
            )
        }
        return this
    }

    private fun statusTitle(metadata: DownloadMetadataEntity): String {
        // Shared Supplemental Status Title (Compose notification status through the same policy as management rows)
        // The percent stays in bookProgressLabel and the progress bar, while this title carries compact file count and byte progress details.
        // Use unified formatFileSize helper from shared package to ensure consistent formatting across settings and notifications.
        val downloadedSizeText = metadata.totalBytes
            .takeIf { totalBytes -> totalBytes > 0L }
            ?.let { formatFileSize(metadata.downloadedBytes) }
        val totalSizeText = metadata.totalBytes
            .takeIf { totalBytes -> totalBytes > 0L }
            ?.let { totalBytes -> formatFileSize(totalBytes) }
        return ManualDownloadDisplayTextPolicy.taskSupplementalLabel(
            statusText = appContext.getString(metadata.status.statusTextRes()),
            completedFiles = metadata.completedFiles,
            totalFiles = metadata.totalFiles,
            downloadedSizeText = downloadedSizeText,
            totalSizeText = totalSizeText
        )
    }

    private fun progressPercent(metadata: DownloadMetadataEntity): Int {
        val totalBytes = metadata.totalBytes
        if (totalBytes <= 0L) return if (metadata.status == DownloadStatus.COMPLETED) PROGRESS_MAX else 0
        return ((metadata.downloadedBytes.coerceAtLeast(0L) * PROGRESS_MAX) / totalBytes)
            .toInt()
            .coerceIn(0, PROGRESS_MAX)
    }

    // Redundant formatBytes helper has been removed in favor of formatFileSize from shared utilities.

    private fun canPostNotifications(): Boolean {
        val granted = ContextCompat.checkSelfPermission(appContext, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) return false
        return notificationManager.areNotificationsEnabled()
    }

    private fun ensureNotificationChannel() {
        val manager = appContext.getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            DOWNLOAD_NOTIFICATION_CHANNEL_ID,
            appContext.getString(R.string.app_name),
            NotificationManager.IMPORTANCE_LOW
        )
        manager.createNotificationChannel(channel)
    }

    private fun createContentIntent(bookId: String): PendingIntent {
        // Book Notification Destination (Open the settings-hosted download management page)
        // Book-level notifications are task-management surfaces, so tapping them should land on the task list instead of the generic app shell.
        val intent = MainActivity.createOpenDownloadManagementIntent(appContext)
        return PendingIntent.getActivity(
            appContext,
            notificationIdForBook(bookId),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun notificationIdForBook(bookId: String): Int =
        // Stable Book Notification Id (Derive one reusable Android notification slot from the book id)
        // Updating the same slot lets progress refresh in place instead of creating a new notification for each sync tick.
        BOOK_NOTIFICATION_ID_PREFIX or (bookId.hashCode() and BOOK_NOTIFICATION_ID_MASK)

    private fun ManualDownloadNotificationAction.iconRes(): Int =
        when (this) {
            ManualDownloadNotificationAction.Pause -> R.drawable.pause
            ManualDownloadNotificationAction.Resume -> R.drawable.play
            ManualDownloadNotificationAction.Retry -> android.R.drawable.ic_popup_sync
            ManualDownloadNotificationAction.Cancel -> android.R.drawable.ic_menu_close_clear_cancel
        }

    private fun ManualDownloadNotificationAction.titleText(): String =
        when (this) {
            ManualDownloadNotificationAction.Pause -> appContext.getString(R.string.detail_download_pause_action)
            ManualDownloadNotificationAction.Resume -> appContext.getString(R.string.detail_download_resume_action)
            ManualDownloadNotificationAction.Retry -> appContext.getString(R.string.detail_download_resume_action)
            ManualDownloadNotificationAction.Cancel -> appContext.getString(R.string.detail_download_cancel_action)
        }

    private fun DownloadStatus.statusTextRes(): Int =
        when (this) {
            DownloadStatus.QUEUED -> R.string.download_management_status_queued
            DownloadStatus.DOWNLOADING -> R.string.download_management_status_downloading
            DownloadStatus.PAUSED -> R.string.download_management_status_paused
            DownloadStatus.COMPLETED -> R.string.download_management_status_completed
            DownloadStatus.FAILED -> R.string.download_management_status_failed
        }

    private companion object {
        private const val PROGRESS_MAX = 100
        private const val BOOK_NOTIFICATION_ID_PREFIX = 0x31000000
        private const val BOOK_NOTIFICATION_ID_MASK = 0x00FFFFFF
        private val ACTIVE_NOTIFICATION_STATUSES = setOf(
            DownloadStatus.QUEUED,
            DownloadStatus.DOWNLOADING
        )
        private val TERMINAL_NOTIFICATION_STATUSES = setOf(
            DownloadStatus.COMPLETED,
            DownloadStatus.FAILED
        )
    }
}
