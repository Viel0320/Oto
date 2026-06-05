package com.viel.aplayer.data.cache

import com.viel.aplayer.data.entity.BookEntity

/**
 * Cover Cache Invalidation Policy (Centralizes cover timestamp decisions without owning storage or image loading)
 * Keeps UI cache-version decisions close to the BookEntity state while preventing ABS sync, local recovery, and Coil request
 * creation from each maintaining separate timestamp rules.
 */
object CoverCacheInvalidationPolicy {
    /**
     * Resolve Last Scanned Timestamp (Calculates the cache-version stamp for the next persisted cover snapshot)
     * Returns a fresh sync timestamp only when a book first gains cover assets, cover paths change, or a remote catalog
     * version changes while reusing the previous BookEntity.lastScannedAt for unchanged snapshots.
     */
    fun resolveLastScannedAt(
        existing: BookEntity?,
        nextCoverPath: String?,
        nextThumbnailPath: String?,
        syncedAt: Long,
        remoteVersionChanged: Boolean = false
    ): Long {
        if (existing == null) {
            return if (nextCoverPath != null || nextThumbnailPath != null) syncedAt else 0L
        }

        val coverPathChanged = existing.coverPath != nextCoverPath
        val thumbnailPathChanged = existing.thumbnailPath != nextThumbnailPath
        return if (coverPathChanged || thumbnailPathChanged || remoteVersionChanged) {
            syncedAt
        } else {
            existing.lastScannedAt
        }
    }
}
