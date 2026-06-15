package com.viel.aplayer.data.gateway

/**
 * Library Resource Cleanup Gateway (Derived artifact cleanup seam)
 * Application management use cases coordinate when cleanup happens, while the data/cache layer owns how cover files,
 * directory snapshots, and range blocks are physically removed.
 */
interface LibraryResourceCleanupGateway {
    /**
     * Clear Book Cover Cache (Remove book-owned artwork before the book is soft-deleted)
     * Keeps book deletion from retaining orphaned cover and thumbnail files after the catalog row changes to DELETED.
     */
    suspend fun clearBookCoverCache(bookId: String)

    /**
     * Clear Root Derived Caches (Remove root-owned artwork, directory snapshots, and range cache)
     * Runs before root cascade deletion so cleanup can still read persisted book paths and root-scoped cache ownership.
     */
    suspend fun clearRootDerivedCaches(rootId: String)
}
