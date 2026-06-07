package com.viel.aplayer.data.gateway

/**
 * Cover Asset Gateway (Application-facing cover file seam)
 *
 * Owns only user-managed cover image writes so metadata rescans, subtitle parsing, and availability checks
 * cannot accumulate behind a generic cover interface again.
 */
interface CoverAssetGateway {
    /**
     * Save Custom Cover File (User manual cover override)
     *
     * Saves the cropped temporary cover image, removes stale artwork files, and updates persisted cover paths.
     */
    suspend fun saveCustomCover(bookId: String, tempCoverPath: String)
}
