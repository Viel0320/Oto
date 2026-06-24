package com.viel.oto.media.service

import com.viel.oto.application.download.ManualDownloadDisplayTextPolicy
import com.viel.oto.data.entity.DownloadStatus

internal enum class ManualDownloadNotificationAction {
    Pause,
    Resume,
    Retry,
    Cancel
}

/**
 * Keep title and action mapping deterministic.
 *
 * Android notification rendering stays in AndroidManualDownloadNotificationGateway, while this policy
 * keeps the user-visible title format and state-specific action list easy to test without platform APIs.
 */
internal object ManualDownloadNotificationPresentationPolicy {
    fun title(author: String, bookTitle: String, progressPercent: Int): String =
        ManualDownloadDisplayTextPolicy.progressBookLabel(
            progressPercent = progressPercent,
            author = author,
            bookTitle = bookTitle
        )

    fun actionsFor(status: DownloadStatus): List<ManualDownloadNotificationAction> =
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
