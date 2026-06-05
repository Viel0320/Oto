package com.viel.aplayer.library.vfs.cache

import com.viel.aplayer.library.vfs.VfsNode
import java.security.MessageDigest

/**
 * VFS Range Cache Key (Builds stable file-safe identifiers for bounded metadata reads)
 * Hashes root, source path, and file version inputs so cache file names never contain raw provider paths, etags, URLs, or
 * library identifiers.
 */
data class VfsRangeCacheKey(
    val rootIdHash: String,
    val sourcePathHash: String,
    val version: String,
    val offset: Long,
    val length: Int
) {
    /**
     * To File Name (Serializes the cache key into a deterministic disk block name)
     * Uses only hexadecimal hash segments plus numeric range bounds, preserving a simple root-prefixed eviction pattern.
     */
    fun toFileName(): String =
        "${rootIdHash}_${sourcePathHash}_${version}_${offset}_${length}.bin"

    companion object {
        /**
         * Build From VFS Node (Creates a range key only for valid bounded reads)
         * Rejects negative offsets and non-positive lengths before hashing so callers can bypass invalid cache operations.
         */
        fun from(file: VfsNode, offset: Long, length: Int): VfsRangeCacheKey? {
            if (offset < 0L || length <= 0) return null
            return VfsRangeCacheKey(
                rootIdHash = hashIdentifier(file.root.id),
                sourcePathHash = hashIdentifier(file.metadata.sourcePath),
                version = versionHash(
                    etag = file.metadata.etag,
                    lastModified = file.metadata.lastModified,
                    fileSize = file.metadata.fileSize
                ),
                offset = offset,
                length = length
            )
        }

        /**
         * Version Hash (Derives file-version identity without exposing raw etag or timestamps)
         * Prefers provider etags when available and falls back to lastModified plus fileSize for sources without etag support.
         */
        fun versionHash(etag: String?, lastModified: Long, fileSize: Long): String {
            val versionSource = etag?.takeIf { it.isNotBlank() } ?: "${lastModified}_${fileSize}"
            return hashIdentifier(versionSource)
        }

        /**
         * Hash Identifier (Converts sensitive cache coordinates to short hexadecimal labels)
         * Produces compact SHA-256 prefixes that are deterministic enough for cache hits while avoiding reversible file names.
         */
        fun hashIdentifier(value: String): String {
            val normalized = value.trim().replace('\\', '/')
            val digest = MessageDigest.getInstance("SHA-256").digest(normalized.toByteArray())
            return digest.take(8).joinToString(separator = "") { byte -> "%02x".format(byte) }
        }
    }
}
