package com.viel.aplayer.data.gateway

/**
 * Decoupled Domain Gateway Interface (CoverGateway)
 * Focuses on physical audiobook cover file management, tag/metadata extraction, and theme color computations.
 * 
 * Core Design Goals:
 * 1. Eradicate God-Class Dependencies: Exposes dedicated read/write logic for covers and tags to upstream ViewModels and scanners.
 * 2. Promote Dependency Inversion: Isolates heavy low-level disk I/O operations (such as cover self-healing and tag parsing) from business logic.
 */
interface CoverGateway {

    /**
     * Save Custom Cover File (User manual modification)
     * Saves and overwrites the custom physical cover image for the target book.
     * 
     * @param bookId Unique identifier of the target audiobook
     * @param tempCoverPath Local absolute file path of the temporary new cover image
     */
    suspend fun saveCustomCover(bookId: String, tempCoverPath: String)

    /**
     * Force Ingestion Recovery (Physical rescan override)
     * Triggers a physical scan of audio tracks to extract metadata tags and recover missing covers.
     */
    suspend fun forceRegenerateCoverAndMetadata(bookId: String)

    /**
     * Cache Theme Accent Color (UI palette persistence)
     * Updates and persists the primary ARGB color value extracted from the cover to match the UI theme.
     */
    fun updateBackgroundColor(id: String, color: Int)

    /**
     * Validate Ingestion Availability (Detail view validation)
     * Verifies physical existence of the primary track and cached covers to toggle play button interactivity.
     */
    suspend fun checkDetailAvailability(bookId: String): Boolean

    /**
     * Verify Primary Audio Track (VFS reachability validation)
     * Asserts whether the primary audio file is physically accessible and readable via the VFS interface.
     */
    suspend fun checkPrimaryAudioFileExists(bookId: String): Boolean

    /**
     * Load Associated Subtitles (VFS sidecar parsing)
     * Asynchronously retrieves and parses sidecar subtitle files matching the specified track ID via VFS.
     */
    suspend fun loadSubtitlesForBookFile(bookFileId: String): List<com.viel.aplayer.ui.player.components.SubtitleLine>
}
