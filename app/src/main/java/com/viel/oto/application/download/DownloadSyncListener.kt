package com.viel.oto.application.download

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@OptIn(UnstableApi::class)
class DownloadSyncListener(
    private val downloadBookIdResolver: DownloadBookIdResolver,
    private val downloadBookReconcilerProvider: () -> DownloadBookReconciler,
    private val downloadBookRemovalHandler: suspend (String) -> Unit = {},
    private val progressPollerStarter: () -> Unit = {},
    private val scope: CoroutineScope
) : DownloadManager.Listener {
    override fun onDownloadChanged(downloadManager: DownloadManager, download: Download, finalException: Exception?) {
        handleFileDownloadEvent(download.request.id)
    }

    override fun onDownloadRemoved(downloadManager: DownloadManager, download: Download) {
        handleFileRemoval(download.request.id)
    }

    internal fun handleFileDownloadEvent(fileId: String) {
        progressPollerStarter()
        reconcileFile(fileId)
    }

    internal fun handleFileRemoval(fileId: String) {
        scope.launch {
            val bookId = downloadBookIdResolver.getBookIdByFileId(fileId) ?: return@launch
            downloadBookRemovalHandler(bookId)
        }
    }

    internal fun reconcileFile(fileId: String) {
        scope.launch {
            val bookId = downloadBookIdResolver.getBookIdByFileId(fileId) ?: return@launch
            downloadBookReconcilerProvider().reconcileBook(bookId)
        }
    }
}
