package com.viel.aplayer.library.vfs.cache

import com.viel.aplayer.data.entity.DirectoryChildCacheEntity
import com.viel.aplayer.library.vfs.sourceProvider.SourceFileMetadata

/**
 * Converts between VFS metadata and Room directory child rows.
 * Keeps directory listing snapshots free of provider-native handles, remote URLs, and credentials while preserving the
 * cross-provider metadata fields needed to reconstruct VFS nodes during scanner traversal.
 */
object DirectoryCacheMapper {
    /**
     * Creates a stable direct-child identity inside a parent directory snapshot.
     * Uses the documented rootId, parentSourcePath, and sourcePath format so replacement writes target the same cached child row.
     */
    fun cacheKey(rootId: String, parentSourcePath: String, sourcePath: String): String =
        "$rootId|$parentSourcePath|$sourcePath"

    /**
     * Persists one direct child metadata snapshot.
     * Copies normalized VFS fields only and records cachedAt from the caller-provided write timestamp for deterministic testing.
     */
    fun toEntity(
        rootId: String,
        parentSourcePath: String,
        metadata: SourceFileMetadata,
        cachedAt: Long
    ): DirectoryChildCacheEntity =
        DirectoryChildCacheEntity(
            cacheKey = cacheKey(rootId, parentSourcePath, metadata.sourcePath),
            rootId = rootId,
            parentSourcePath = parentSourcePath,
            sourcePath = metadata.sourcePath,
            identity = metadata.identity,
            parentIdentity = metadata.parentIdentity,
            displayName = metadata.displayName,
            isDirectory = metadata.isDirectory,
            fileSize = metadata.fileSize,
            lastModified = metadata.lastModified,
            etag = metadata.etag,
            mimeType = metadata.mimeType,
            cachedAt = cachedAt
        )

    /**
     * Reconstructs a VFS metadata snapshot from Room.
     * Restores only SourceFileMetadata fields so the VFS can wrap cached entries without exposing database entities upstream.
     */
    fun toMetadata(entity: DirectoryChildCacheEntity): SourceFileMetadata =
        SourceFileMetadata(
            sourcePath = entity.sourcePath,
            identity = entity.identity,
            parentSourcePath = entity.parentSourcePath,
            parentIdentity = entity.parentIdentity,
            displayName = entity.displayName,
            isDirectory = entity.isDirectory,
            fileSize = entity.fileSize,
            lastModified = entity.lastModified,
            etag = entity.etag,
            mimeType = entity.mimeType
        )
}
