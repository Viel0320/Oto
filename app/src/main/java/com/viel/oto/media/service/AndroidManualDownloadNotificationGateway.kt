package com.viel.oto.media.service

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
import com.viel.oto.MainActivity
import com.viel.oto.R
import com.viel.oto.application.download.ManualDownloadDisplayTextPolicy
import com.viel.oto.application.download.ManualDownloadNotificationGateway
import com.viel.oto.data.dao.BookDao
import com.viel.oto.data.entity.DownloadMetadataEntity
import com.viel.oto.data.entity.DownloadStatus
import com.viel.oto.shared.formatFileSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Render one Android progress notification per manual book download.
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
                .setContentTitle(statusTitle)
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
        val intent = MainActivity.createOpenDownloadManagementIntent(appContext)
        return PendingIntent.getActivity(
            appContext,
            notificationIdForBook(bookId),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun notificationIdForBook(bookId: String): Int =
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
