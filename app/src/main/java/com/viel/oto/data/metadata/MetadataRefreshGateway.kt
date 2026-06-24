package com.viel.oto.data.metadata

/**
 * Application-facing ingestion recovery seam.
 *
 * Exposes user-triggered audio tag and chapter rescans separately from cover asset writes.
 */
interface MetadataRefreshGateway {
    /**
     * Physical rescan override.
     *
     * Re-extracts metadata and chapters from the primary audio track, then refreshes the generated cover asset.
     */
    suspend fun forceRegenerateCoverAndMetadata(bookId: String)
}
