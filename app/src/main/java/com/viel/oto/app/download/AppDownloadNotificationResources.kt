package com.viel.oto.app.download

import android.content.Context
import com.viel.oto.R
import com.viel.oto.data.entity.DownloadStatus
import com.viel.oto.media.service.DownloadForegroundNotificationState
import com.viel.oto.media.service.DownloadNotificationResources
import com.viel.oto.media.service.ManualDownloadNotificationAction

/**
 * Resolves localized download-notification strings and icons from app resources.
 *
 * The service module assembles notifications, while this adapter keeps localization and launcher
 * icon ownership in the app module.
 */
class AppDownloadNotificationResources : DownloadNotificationResources {
    override val smallIconRes: Int = R.mipmap.ic_launcher

    override fun appName(context: Context): String =
        context.getString(R.string.app_name)

    override fun foregroundStatus(context: Context, state: DownloadForegroundNotificationState): String =
        context.getString(
            when (state) {
                DownloadForegroundNotificationState.Active -> R.string.download_notification_active
                DownloadForegroundNotificationState.Waiting -> R.string.download_notification_waiting
                DownloadForegroundNotificationState.Failed -> R.string.download_notification_failed
                DownloadForegroundNotificationState.Idle -> R.string.download_notification_idle
            }
        )

    override fun unknownBookTitle(context: Context): String =
        context.getString(R.string.common_unknown_title)

    override fun unknownAuthor(context: Context): String =
        context.getString(R.string.common_unknown)

    override fun statusText(context: Context, status: DownloadStatus): String =
        context.getString(
            when (status) {
                DownloadStatus.QUEUED -> R.string.download_management_status_queued
                DownloadStatus.DOWNLOADING -> R.string.download_management_status_downloading
                DownloadStatus.PAUSED -> R.string.download_management_status_paused
                DownloadStatus.COMPLETED -> R.string.download_management_status_completed
                DownloadStatus.FAILED -> R.string.download_management_status_failed
            }
        )

    override fun actionText(context: Context, action: ManualDownloadNotificationAction): String =
        context.getString(
            when (action) {
                ManualDownloadNotificationAction.Pause -> R.string.detail_download_pause_action
                ManualDownloadNotificationAction.Resume -> R.string.detail_download_resume_action
                ManualDownloadNotificationAction.Retry -> R.string.detail_download_resume_action
                ManualDownloadNotificationAction.Cancel -> R.string.detail_download_cancel_action
            }
        )

    override fun actionIcon(action: ManualDownloadNotificationAction): Int =
        when (action) {
            ManualDownloadNotificationAction.Pause -> R.drawable.pause
            ManualDownloadNotificationAction.Resume -> R.drawable.play
            ManualDownloadNotificationAction.Retry -> android.R.drawable.ic_popup_sync
            ManualDownloadNotificationAction.Cancel -> android.R.drawable.ic_menu_close_clear_cancel
        }
}
