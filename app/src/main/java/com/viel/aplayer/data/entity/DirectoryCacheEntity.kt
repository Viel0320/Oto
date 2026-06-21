package com.viel.aplayer.data.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.viel.aplayer.data.db.AudiobookSchema

/**
 * Room entity storing directory timestamps and delta hints for scan optimization.
 *
 * The LibraryRootEntity foreign key cascades deletion so folder timestamps cannot survive after
 * their owning library root has been removed.
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
     * Primary key combined as rootId/sourcePath to decouple from raw SAF URIs.
     */
    @PrimaryKey
    val cacheKey: String,

    /**
     * Abstract path shareable between local SAF directories and remote WebDAV directories.
     */
    val sourcePath: String,

    /**
     * Last modified timestamp reported by the directory provider.
     */
    val lastModified: Long,

    /**
     * Reserved for WebDAV delta checks; SAF tracks have no etag and remain null.
     */
    val etag: String? = null,

    /**
     * Used for delta detection on WebDAV folders lacking etags; skipped on SAF.
     */
    val childSignature: String? = null,

    /**
     * Timestamp of the last scanning checks; used to throttle scans on remote drives.
     */
    val lastCheckedAt: Long = 0L,

    /**
     * Restricts failure domains so transient folder issues do not revoke library roots.
     */
    val availabilityStatus: AudiobookSchema.AvailabilityStatus = AudiobookSchema.AvailabilityStatus.UNKNOWN,

    /**
     * Associated media library root ID used for CASCADE deletions.
     */
    val rootId: String
)
