package com.viel.aplayer.data.metadata

/**
 * Metadata Refresh Gateway (Application-facing ingestion recovery seam)
 *
 * Exposes user-triggered audio tag and chapter rescans separately from cover asset writes.
 */
interface MetadataRefreshGateway {
    /**
     * Force Regenerate Cover And Metadata (Physical rescan override)
     *
     * Re-extracts metadata and chapters from the primary audio track, then refreshes the generated cover asset.
     */
    suspend fun forceRegenerateCoverAndMetadata(bookId: String)
}
