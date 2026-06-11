package com.viel.aplayer.data.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.viel.aplayer.data.db.AudiobookSchema

/**
 * Directory Modification Cache Schema (Room entity storing directory lastModified timestamps for scanning optimizations)
 * Establishes a cascade delete relationship (CASCADE) with LibraryRootEntity as a foreign key.
 * When a library root is deleted, Room automatically purges all child folder timestamps from SQLite.
 * This guarantees zero orphaned cache indices left inside databases, completing self-healing cycles.
 */
@Entity(
    tableName = "directory_cache",
    foreignKeys = [
        ForeignKey(
            entity = LibraryRootEntity::class,
            parentColumns = ["id"],
            childColumns = ["rootId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("rootId")]
)
data class DirectoryCacheEntity(
    /**
     * Cache Key (Primary key combined as rootId/sourcePath to decouple from raw SAF URIs)
     */
    @PrimaryKey
    val cacheKey: String,

    /**
     * VFS Directory Path (Abstract path shareable between local SAF directories and remote WebDAV directories)
     */
    val sourcePath: String,

    /**
     * Modification Timestamp (The last modified time read via directory.lastModified())
     */
    val lastModified: Long,

    /**
     * Remote Directory ETag (Reserved for WebDAV delta checks; SAF tracks have no etag and remain null)
     */
    val etag: String? = null,

    /**
     * Child File Signature (Used for delta detection on WebDAV folders lacking etags; skipped on SAF)
     */
    val childSignature: String? = null,

    /**
     * Verification Time (Timestamp of the last scanning checks; used to throttle scans on remote drives)
     */
    val lastCheckedAt: Long = 0L,

    /**
     * Directory Reachability Status (Restricts failure domains so transient folder issues do not revoke library roots)
     */
    val availabilityStatus: AudiobookSchema.AvailabilityStatus = AudiobookSchema.AvailabilityStatus.UNKNOWN,

    /**
     * Parent Root Identifier (Associated media library root ID used for CASCADE deletions)
     */
    val rootId: String
)
