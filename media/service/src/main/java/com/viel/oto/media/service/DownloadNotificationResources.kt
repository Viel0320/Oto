package com.viel.oto.media.service

import android.content.Context
import com.viel.oto.data.entity.DownloadStatus

/**
 * Resolves notification copy and icon resource ids without depending on app resources.
 *
 * The service module owns notification assembly, while the app module owns localized resource
 * selection and launcher icon identity.
 */
interface DownloadNotificationResources {
    val smallIconRes: Int

    fun appName(context: Context): String

    fun foregroundStatus(context: Context, state: DownloadForegroundNotificationState): String

    fun unknownBookTitle(context: Context): String

    fun unknownAuthor(context: Context): String

    fun statusText(context: Context, status: DownloadStatus): String

    fun actionText(context: Context, action: ManualDownloadNotificationAction): String

    fun actionIcon(action: ManualDownloadNotificationAction): Int
}

enum class DownloadForegroundNotificationState {
    Active,
    Waiting,
    Failed,
    Idle
}
