package com.viel.aplayer.data.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Directory Children Cache Schema (Persists direct child snapshots for a scanned directory)
 * Stores only normalized VFS metadata owned by a library root, allowing WebDAV scans to reuse child listings without
 * persisting provider-native handles, credentials, or complete remote URLs.
 */
@Entity(
    tableName = "directory_child_cache",
    foreignKeys = [
        ForeignKey(
            entity = LibraryRootEntity::class,
            parentColumns = ["id"],
            childColumns = ["rootId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index("rootId", "parentSourcePath"),
        Index("rootId")
    ]
)
data class DirectoryChildCacheEntity(
    /**
     * Directory Child Cache Key (Uniquely identifies one child within a parent directory snapshot)
     * Combines rootId, parentSourcePath, and sourcePath so repeated scans replace the exact same VFS child record.
     */
    @PrimaryKey
    val cacheKey: String,
    val rootId: String,
    val parentSourcePath: String,
    val sourcePath: String,
    val identity: String,
    val parentIdentity: String,
    val displayName: String,
    val isDirectory: Boolean,
    val fileSize: Long,
    val lastModified: Long,
    val etag: String?,
    val mimeType: String?,
    val cachedAt: Long
)
