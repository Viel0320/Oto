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
import com.viel.oto.data.dao.BookDao
import com.viel.oto.data.entity.DownloadMetadataEntity
import com.viel.oto.data.entity.DownloadStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.log10
import kotlin.math.pow

/**
 * Render one Android progress notification per manual book download.
 *
 * Media3 still owns the foreground summary notification required by DownloadService, while this gateway
 * turns the durable Room aggregate into the user-facing per-book progress row.
 */
class AndroidManualDownloadNotificationGateway(
    context: Context,
    private val bookDao: BookDao,
    private val launchIntentFactory: MediaServiceLaunchIntentFactory,
    private val notificationResources: DownloadNotificationResources
) {
    private val appContext = context.applicationContext
    private val notificationManager = NotificationManagerCompat.from(appContext)

    @SuppressLint("MissingPermission")
    suspend fun publish(metadata: DownloadMetadataEntity) = withContext(Dispatchers.IO) {
        if (!canPostNotifications()) return@withContext
        ensureNotificationChannel()
        val book = bookDao.getBookById(metadata.bookId)
        val bookTitle = book?.title?.takeIf { value -> value.isNotBlank() }
            ?: notificationResources.unknownBookTitle(appContext)
        val author = book?.author?.takeIf { value -> value.isNotBlank() }
            ?: notificationResources.unknownAuthor(appContext)
        val bookProgressLabel = ManualDownloadNotificationPresentationPolicy.title(
            author = author,
            bookTitle = bookTitle,
            progressPercent = progressPercent(metadata)
        )
        val statusTitle = statusTitle(metadata)
        notificationManager.notify(
            notificationIdForBook(metadata.bookId),
            NotificationCompat.Builder(appContext, DOWNLOAD_NOTIFICATION_CHANNEL_ID)
                .setSmallIcon(notificationResources.smallIconRes)
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

    suspend fun cancel(bookId: String) = withContext(Dispatchers.IO) {
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
                notificationResources.actionIcon(action),
                notificationResources.actionText(appContext, action),
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
        return ManualDownloadNotificationPresentationPolicy.taskSupplementalLabel(
            statusText = notificationResources.statusText(appContext, metadata.status),
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
            notificationResources.appName(appContext),
            NotificationManager.IMPORTANCE_LOW
        )
        manager.createNotificationChannel(channel)
    }

    private fun createContentIntent(bookId: String): PendingIntent {
        val intent = launchIntentFactory.openDownloadManagementIntent(appContext)
        return PendingIntent.getActivity(
            appContext,
            notificationIdForBook(bookId),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun notificationIdForBook(bookId: String): Int =
        BOOK_NOTIFICATION_ID_PREFIX or (bookId.hashCode() and BOOK_NOTIFICATION_ID_MASK)

    private fun formatFileSize(sizeInBytes: Long): String {
        if (sizeInBytes <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        val digitGroups = (log10(sizeInBytes.toDouble()) / log10(BYTES_PER_UNIT.toDouble()))
            .toInt()
            .coerceAtMost(units.lastIndex)
        return String.format(
            Locale.getDefault(),
            "%.1f %s",
            sizeInBytes / BYTES_PER_UNIT.toDouble().pow(digitGroups.toDouble()),
            units[digitGroups]
        )
    }

    private companion object {
        private const val BYTES_PER_UNIT = 1024
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
