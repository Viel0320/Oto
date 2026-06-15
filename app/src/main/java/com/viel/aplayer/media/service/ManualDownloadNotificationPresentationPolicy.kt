package com.viel.aplayer.media.service

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
        // Book Progress Title Format (Match the requested notification title contract)
        // The title intentionally keeps three spaces before the percentage so dense Android notification rows separate metadata from progress.
        "${author.ifBlank { bookTitle }} - ${bookTitle.ifBlank { author }}   ${progressPercent.coerceIn(0, 100)}%"

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
