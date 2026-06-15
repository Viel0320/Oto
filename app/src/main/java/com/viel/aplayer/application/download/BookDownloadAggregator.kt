package com.viel.aplayer.application.download

import com.viel.aplayer.data.entity.BookFileEntity
import com.viel.aplayer.data.entity.DownloadMetadataEntity
import com.viel.aplayer.data.entity.DownloadStatus

enum class FileDownloadState {
    QUEUED,
    DOWNLOADING,
    PAUSED,
    COMPLETED,
    FAILED,
    MISSING_REQUEST
}

data class FileDownloadSnapshot(
    val fileId: String,
    val state: FileDownloadState,
    val downloadedBytes: Long,
    val totalBytes: Long
)

object BookDownloadAggregator {
    // Book Download Aggregate (Project Media3 file-level downloads into one durable book-level row)
    // UI and recovery code observe book progress, while DownloadManager remains the authoritative source for per-file state.
    fun aggregate(
        bookId: String,
        files: List<BookFileEntity>,
        snapshots: List<FileDownloadSnapshot>,
        existing: DownloadMetadataEntity?,
        nowMillis: Long
    ): DownloadMetadataEntity? {
        if (files.isEmpty()) return null
        val snapshotsById = snapshots.associateBy { snapshot -> snapshot.fileId }
        val normalizedSnapshots = files.map { file ->
            snapshotsById[file.id] ?: FileDownloadSnapshot(
                fileId = file.id,
                state = FileDownloadState.MISSING_REQUEST,
                downloadedBytes = 0L,
                totalBytes = file.fileSize
            )
        }
        val status = when {
            normalizedSnapshots.any { snapshot -> snapshot.state == FileDownloadState.FAILED } -> DownloadStatus.FAILED
            normalizedSnapshots.all { snapshot -> snapshot.state == FileDownloadState.COMPLETED } -> DownloadStatus.COMPLETED
            normalizedSnapshots.any { snapshot -> snapshot.state == FileDownloadState.DOWNLOADING } -> DownloadStatus.DOWNLOADING
            normalizedSnapshots.any { snapshot -> snapshot.state == FileDownloadState.PAUSED } -> DownloadStatus.PAUSED
            else -> DownloadStatus.QUEUED
        }
        val totalBytes = normalizedSnapshots.sumOf { snapshot ->
            snapshot.totalBytes.takeIf { bytes -> bytes > 0L }
                ?: files.firstOrNull { file -> file.id == snapshot.fileId }?.fileSize
                ?: 0L
        }
        val downloadedBytes = normalizedSnapshots.sumOf { snapshot ->
            snapshot.downloadedBytes.coerceAtLeast(0L)
        }
        return DownloadMetadataEntity(
            bookId = bookId,
            status = status,
            totalFiles = files.size,
            completedFiles = normalizedSnapshots.count { snapshot -> snapshot.state == FileDownloadState.COMPLETED },
            totalBytes = totalBytes,
            downloadedBytes = downloadedBytes.coerceAtMost(totalBytes.takeIf { it > 0L } ?: Long.MAX_VALUE),
            createdAt = existing?.createdAt ?: nowMillis,
            updatedAt = nowMillis
        )
    }
}
