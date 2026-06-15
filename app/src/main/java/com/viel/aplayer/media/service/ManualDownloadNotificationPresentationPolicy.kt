package com.viel.aplayer.media.service

import com.viel.aplayer.application.download.ManualDownloadDisplayTextPolicy
import com.viel.aplayer.data.entity.DownloadStatus

internal enum class ManualDownloadNotificationAction {
    Pause,
    Resume,
    Retry,
    Cancel
}

/**
 * Manual Download Notification Presentation Policy (Keep title and action mapping deterministic)
 *
 * Android notification rendering stays in AndroidManualDownloadNotificationGateway, while this policy
 * keeps the user-visible title format and state-specific action list easy to test without platform APIs.
 */
internal object ManualDownloadNotificationPresentationPolicy {
    fun title(author: String, bookTitle: String, progressPercent: Int): String =
        // Notification Progress Label Delegation (Reuse the shared manual-download label formatter)
        // Notifications and the download management list must truncate long authors the same way.
        ManualDownloadDisplayTextPolicy.progressBookLabel(
            progressPercent = progressPercent,
            author = author,
            bookTitle = bookTitle
        )

    fun actionsFor(status: DownloadStatus): List<ManualDownloadNotificationAction> =
        // Task Action Mapping (Expose only actions that make sense for the current durable aggregate state)
        // Active tasks can pause, paused tasks can resume, failed tasks can retry, and all non-completed tasks can be cancelled.
        when (status) {
            DownloadStatus.QUEUED,
            DownloadStatus.DOWNLOADING -> listOf(
                ManualDownloadNotificationAction.Pause,
                ManualDownloadNotificationAction.Cancel
            )
            DownloadStatus.PAUSED -> listOf(
                ManualDownloadNotificationAction.Resume,
                ManualDownloadNotificationAction.Cancel
            )
            DownloadStatus.FAILED -> listOf(
                ManualDownloadNotificationAction.Retry,
                ManualDownloadNotificationAction.Cancel
            )
            DownloadStatus.COMPLETED -> emptyList()
        }

}
