package com.viel.aplayer.data.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

enum class DownloadStatus {
    QUEUED,
    DOWNLOADING,
    PAUSED,
    COMPLETED,
    FAILED
}

@Entity(
    tableName = "download_metadata",
    indices = [
        Index(value = ["status"])
    ]
)
data class DownloadMetadataEntity(
    @PrimaryKey val bookId: String,
    val status: DownloadStatus,
    val totalFiles: Int,
    val completedFiles: Int,
    val totalBytes: Long,
    val downloadedBytes: Long,
    val createdAt: Long,
    val updatedAt: Long
)
