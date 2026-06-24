package com.viel.oto.data.cover

/**
 * Application-facing cover file seam.
 *
 * Owns only user-managed cover image writes so metadata rescans, subtitle parsing, and availability checks
 * cannot accumulate behind a generic cover interface again.
 */
interface CoverAssetGateway {
    /**
     * User manual cover override.
     *
     * Saves the custom cover image from the given URI or path, removes stale artwork files, and updates persisted cover paths.
     */
    suspend fun saveCustomCover(bookId: String, coverUri: String)
}
