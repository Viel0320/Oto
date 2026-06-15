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

// Download Metadata Aggregate (Stores the book-level view derived from Media3 file-level downloads)
// NONE is intentionally absent from this table; application read models derive no-cache state when a row does not exist.
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
