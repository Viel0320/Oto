package com.viel.oto.application.download

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadIndex
import java.io.IOException

interface DownloadIndexSnapshotReader {
    /**
     * Return the current Media3 state for one file-level download.
     * Missing rows represent a request that must be repaired or resubmitted by the download command layer.
     */
    suspend fun getSnapshot(fileId: String): FileDownloadSnapshot?
}

@OptIn(UnstableApi::class)
class Media3DownloadIndexSnapshotReader(
    private val downloadIndexProvider: () -> DownloadIndex,
    private val activeDownloadsProvider: () -> List<Download> = { emptyList() }
) : DownloadIndexSnapshotReader {
    override suspend fun getSnapshot(fileId: String): FileDownloadSnapshot? {
        activeDownloadsProvider().firstOrNull { download -> download.request.id == fileId }
            ?.let { download -> return download.toFileDownloadSnapshot() }
        val download = try {
            downloadIndexProvider().getDownload(fileId)
        } catch (_: IOException) {
            null
        } ?: return null
        return download.toFileDownloadSnapshot()
    }

    private fun Download.toFileDownloadSnapshot(): FileDownloadSnapshot =
        FileDownloadSnapshot(
            fileId = request.id,
            state = when (state) {
                Download.STATE_COMPLETED -> FileDownloadState.COMPLETED
                Download.STATE_FAILED -> FileDownloadState.FAILED
                Download.STATE_DOWNLOADING,
                Download.STATE_RESTARTING -> FileDownloadState.DOWNLOADING
                Download.STATE_STOPPED -> FileDownloadState.PAUSED
                Download.STATE_QUEUED,
                Download.STATE_REMOVING -> FileDownloadState.QUEUED
                else -> FileDownloadState.QUEUED
            },
            downloadedBytes = bytesDownloaded,
            totalBytes = contentLength
        )
}
