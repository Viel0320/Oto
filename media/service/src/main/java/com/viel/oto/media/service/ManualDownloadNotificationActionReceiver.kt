package com.viel.oto.media.service

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.viel.oto.logger.SecureLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

class ManualDownloadNotificationActionReceiver : BroadcastReceiver(), KoinComponent {
    private val downloadActionGateway: ManualDownloadActionGateway by inject()

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val bookId = intent.getStringExtra(EXTRA_BOOK_ID)?.takeIf { value -> value.isNotBlank() } ?: return
        if (action !in SUPPORTED_ACTIONS) return
        val pendingResult = goAsync()
        val receiverScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        receiverScope.launch {
            try {
                withTimeout(RECEIVER_TIMEOUT_MS) {
                    when (action) {
                        ACTION_PAUSE_DOWNLOAD -> downloadActionGateway.pauseDownload(bookId)
                        ACTION_RESUME_DOWNLOAD -> downloadActionGateway.resumeDownload(bookId)
                        ACTION_RETRY_DOWNLOAD -> downloadActionGateway.retryDownload(bookId)
                        ACTION_CANCEL_DOWNLOAD -> downloadActionGateway.cancelDownload(bookId)
                    }
                }
            } catch (error: Exception) {
                SecureLog.error(TAG, "Manual download notification action failed: ${error.message}", error)
            } finally {
                pendingResult.finish()
                receiverScope.cancel()
            }
        }
    }

    companion object {
        private const val TAG = "ManualDownloadNotificationActionReceiver"
        private const val RECEIVER_TIMEOUT_MS = 9_000L
        private const val EXTRA_BOOK_ID = "com.viel.oto.extra.MANUAL_DOWNLOAD_BOOK_ID"
        private const val ACTION_PAUSE_DOWNLOAD = "com.viel.oto.download.action.PAUSE"
        private const val ACTION_RESUME_DOWNLOAD = "com.viel.oto.download.action.RESUME"
        private const val ACTION_RETRY_DOWNLOAD = "com.viel.oto.download.action.RETRY"
        private const val ACTION_CANCEL_DOWNLOAD = "com.viel.oto.download.action.CANCEL"
        private val SUPPORTED_ACTIONS = setOf(
            ACTION_PAUSE_DOWNLOAD,
            ACTION_RESUME_DOWNLOAD,
            ACTION_RETRY_DOWNLOAD,
            ACTION_CANCEL_DOWNLOAD
        )

        internal fun pendingIntent(
            context: Context,
            action: ManualDownloadNotificationAction,
            bookId: String
        ): PendingIntent {
            val actionString = action.toIntentAction()
            val intent = Intent(context, ManualDownloadNotificationActionReceiver::class.java)
                .setAction(actionString)
                .setPackage(context.packageName)
                .putExtra(EXTRA_BOOK_ID, bookId)
            return PendingIntent.getBroadcast(
                context,
                requestCodeFor(actionString, bookId),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        private fun ManualDownloadNotificationAction.toIntentAction(): String =
            when (this) {
                ManualDownloadNotificationAction.Pause -> ACTION_PAUSE_DOWNLOAD
                ManualDownloadNotificationAction.Resume -> ACTION_RESUME_DOWNLOAD
                ManualDownloadNotificationAction.Retry -> ACTION_RETRY_DOWNLOAD
                ManualDownloadNotificationAction.Cancel -> ACTION_CANCEL_DOWNLOAD
            }

        private fun requestCodeFor(action: String, bookId: String): Int =
            0x32000000 or ((31 * action.hashCode() + bookId.hashCode()) and 0x00FFFFFF)
    }
}
