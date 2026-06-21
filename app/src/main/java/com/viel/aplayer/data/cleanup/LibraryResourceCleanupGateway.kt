package com.viel.aplayer.data.cleanup

/**
 * Derived artifact cleanup seam.
 * Application management use cases coordinate when cleanup happens, while the data/cache layer owns how cover files,
 * directory snapshots, and range blocks are physically removed.
 */
interface LibraryResourceCleanupGateway {
    /**
     * Remove book-owned artwork before the book is soft-deleted.
     * Keeps book deletion from retaining orphaned cover and thumbnail files after the catalog row changes to DELETED.
     */
    suspend fun clearBookCoverCache(bookId: String)

    /**
     * Remove root-owned artwork, directory snapshots, and range cache.
     * Runs before root cascade deletion so cleanup can still read persisted book paths and root-scoped cache ownership.
     */
    suspend fun clearRootDerivedCaches(rootId: String)
}
