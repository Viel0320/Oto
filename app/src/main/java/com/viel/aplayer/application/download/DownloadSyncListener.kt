package com.viel.aplayer.application.download

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

    // File Download Event Entry (Start byte-progress sampling before reconciling the parent book)
    // Media3 reports state changes sparsely, so each file event refreshes the poller that captures in-flight byte changes between callbacks.
    internal fun handleFileDownloadEvent(fileId: String) {
        progressPollerStarter()
        reconcileFile(fileId)
    }

    // File Removal Cleanup (Treat Media3 removal as terminal deletion instead of a missing request)
    // Reconciliation would see the removed DownloadIndex row as MISSING_REQUEST and recreate a queued task, so removal callbacks clear the parent aggregate directly.
    internal fun handleFileRemoval(fileId: String) {
        scope.launch {
            val bookId = downloadBookIdResolver.getBookIdByFileId(fileId) ?: return@launch
            downloadBookRemovalHandler(bookId)
        }
    }

    // File Event Reconciliation (Translate Media3 file-level callbacks into book-level aggregate sync)
    // DownloadManager never owns book IDs, so the listener resolves the parent row before touching durable download metadata.
    internal fun reconcileFile(fileId: String) {
        scope.launch {
            val bookId = downloadBookIdResolver.getBookIdByFileId(fileId) ?: return@launch
            downloadBookReconcilerProvider().reconcileBook(bookId)
        }
    }
}
