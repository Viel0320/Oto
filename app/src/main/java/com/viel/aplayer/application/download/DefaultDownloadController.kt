package com.viel.aplayer.application.download

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadRequest
import com.viel.aplayer.data.dao.DownloadMetadataDao
import com.viel.aplayer.data.entity.BookFileEntity
import com.viel.aplayer.data.entity.DownloadMetadataEntity
import com.viel.aplayer.data.entity.DownloadStatus
import com.viel.aplayer.media.VfsPlaybackUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(UnstableApi::class)
class DefaultDownloadController(
    private val downloadableBookFileSelector: DownloadableBookFileSelector,
    private val downloadMetadataDao: DownloadMetadataDao,
    private val downloadRuntimeGateway: DownloadRuntimeGateway,
    private val downloadRequestRepairer: DownloadRequestRepairer,
    private val manualDownloadNotificationGateway: ManualDownloadNotificationGateway = ManualDownloadNotificationGateway.NoOp,
    private val nowProvider: () -> Long = { System.currentTimeMillis() }
) : DownloadController, ManualDownloadCleanupGateway {
    override suspend fun downloadBook(bookId: String) = withContext(Dispatchers.IO) {
        val remoteFiles = remoteAudioFilesForBook(bookId)
        if (remoteFiles.isEmpty()) {
            downloadMetadataDao.deleteByBookId(bookId)
            manualDownloadNotificationGateway.cancel(bookId)
            return@withContext
        }
        val existing = downloadMetadataDao.getMetadata(bookId)
        val now = nowProvider()
        val queuedMetadata = DownloadMetadataEntity(
            bookId = bookId,
            status = existing?.status?.takeIf { status -> status == DownloadStatus.PAUSED } ?: DownloadStatus.QUEUED,
            totalFiles = remoteFiles.size,
            completedFiles = existing?.completedFiles?.coerceIn(0, remoteFiles.size) ?: 0,
            totalBytes = remoteFiles.sumOf { file -> file.fileSize.coerceAtLeast(0L) },
            downloadedBytes = existing?.downloadedBytes?.coerceAtLeast(0L) ?: 0L,
            createdAt = existing?.createdAt ?: now,
            updatedAt = now
        )
        downloadMetadataDao.insertOrReplace(queuedMetadata)
        manualDownloadNotificationGateway.publish(queuedMetadata)
        val missingFiles = downloadRequestRepairer.findMissingFiles(bookId, remoteFiles)
        missingFiles.sortedBy { file -> file.index }.forEach { file ->
            downloadRuntimeGateway.addDownload(file.toDownloadRequest())
        }
    }

    override suspend fun cancelDownload(bookId: String) {
        deleteDownload(bookId)
    }

    override suspend fun pauseDownload(bookId: String) = withContext(Dispatchers.IO) {
        remoteAudioFilesForBook(bookId).forEach { file ->
            downloadRuntimeGateway.setStopReason(file.id, USER_PAUSED_STOP_REASON)
        }
        updateExistingStatus(bookId, DownloadStatus.PAUSED)
    }

    override suspend fun resumeDownload(bookId: String) = withContext(Dispatchers.IO) {
        remoteAudioFilesForBook(bookId).forEach { file ->
            downloadRuntimeGateway.setStopReason(file.id, Download.STOP_REASON_NONE)
        }
        updateExistingStatus(bookId, DownloadStatus.QUEUED)
    }

    override suspend fun deleteDownload(bookId: String) = withContext(Dispatchers.IO) {
        remoteAudioFilesForBook(bookId).forEach { file ->
            downloadRuntimeGateway.removeDownload(file.id)
        }
        downloadMetadataDao.deleteByBookId(bookId)
        manualDownloadNotificationGateway.cancel(bookId)
    }

    private suspend fun updateExistingStatus(bookId: String, status: DownloadStatus) {
        val existing = downloadMetadataDao.getMetadata(bookId) ?: return
        val updated = existing.copy(
            status = status,
            updatedAt = nowProvider()
        )
        downloadMetadataDao.insertOrReplace(updated)
        manualDownloadNotificationGateway.publish(updated)
    }

    private suspend fun remoteAudioFilesForBook(bookId: String): List<BookFileEntity> =
        downloadableBookFileSelector.remoteAudioFilesForBook(bookId)

    private fun BookFileEntity.toDownloadRequest(): DownloadRequest =
        DownloadRequest.Builder(id, VfsPlaybackUri.fromBookFile(this))
            .setCustomCacheKey(id)
            .build()

    private companion object {
        private const val USER_PAUSED_STOP_REASON = 1
    }
}
