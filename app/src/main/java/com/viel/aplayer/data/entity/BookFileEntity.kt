package com.viel.aplayer.data.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.viel.aplayer.data.db.AudiobookSchema

/**
 * Physical Track Asset (Entity representing physical audio tracks or manifest configurations)
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
        // Cascade File Cleanup (Establishes cascade delete with LibraryRootEntity to remove files when root is deleted)
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
    val id: String, // Unique stable identifier
    val bookId: String,
    // Shared Asset Mapping (SOURCE_MANIFEST and AUDIO roles share the same files table, all indexed via VFS paths)
    val fileRole: AudiobookSchema.FileRole = AudiobookSchema.FileRole.AUDIO,
    val rootId: String, // Associated library root identifier
    val index: Int, // Logical position order inside the media playback queue
    // Virtual File Pathing (Avoids storing raw URIs; all tracks are resolved via composite VFS path keys (rootId + sourcePath))
    val sourcePath: String,
    // Source Identifier Claims (Stores provider-specific stable keys as auxiliary claims instead of raw URIs)
    val sourceIdentity: String,
    // WebDAV ETag Check (Used for incremental sync on WebDAV servers; SAF tracks have no etag and default to null)
    val etag: String? = null,
    // Diagnostic Manifest Path (Stores raw track names inside CUE/M3U8 playlists for debug inspections)
    val manifestEntryPath: String? = null,
    val displayName: String,
    val durationMs: Long,
    val fileSize: Long,
    val lastModified: Long,
    val fingerprint: String? = null,
    val lastSeenScanId: String? = null,
    val status: AudiobookSchema.FileStatus = AudiobookSchema.FileStatus.READY // READY / MISSING
)
