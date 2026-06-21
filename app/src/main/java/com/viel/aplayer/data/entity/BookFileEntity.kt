package com.viel.aplayer.data.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.viel.aplayer.data.db.AudiobookSchema

/**
 * Entity representing physical audio tracks or manifest configurations.
 * An audiobook contains one or more physical files located using the Virtual File System (VFS).
 */
@Entity(
    tableName = "book_files",
    foreignKeys = [
        ForeignKey(
            entity = BookEntity::class,
            parentColumns = ["id"],
            childColumns = ["bookId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = LibraryRootEntity::class,
            parentColumns = ["id"],
            childColumns = ["rootId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("bookId"), Index("sourceIdentity"), Index("rootId")]
)
data class BookFileEntity(
    @PrimaryKey
    val id: String,
    val bookId: String,
    val fileRole: AudiobookSchema.FileRole = AudiobookSchema.FileRole.AUDIO,
    val rootId: String,
    val index: Int,
    val sourcePath: String,
    val sourceIdentity: String,
    val etag: String? = null,
    val manifestEntryPath: String? = null,
    val displayName: String,
    val durationMs: Long,
    val fileSize: Long,
    val lastModified: Long,
    val fingerprint: String? = null,
    val lastSeenScanId: String? = null,
    val status: AudiobookSchema.FileStatus = AudiobookSchema.FileStatus.READY
)
