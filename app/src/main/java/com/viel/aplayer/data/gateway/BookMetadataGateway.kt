package com.viel.aplayer.data.gateway

/**
 * Book Metadata Gateway (Application-facing audiobook metadata mutation seam)
 *
 * Contains only semantic book metadata writes so read models, bookmarks, chapters, and deletion flows can
 * avoid depending on user-edit and synchronization update commands.
 */
interface BookMetadataGateway {
    /**
     * Update Reading Status (User state modification)
     *
     * Manually updates the reading progress category, such as STARTED or FINISHED, for the audiobook.
     */
    suspend fun updateBookReadStatus(bookId: String, readStatus: String)

    /**
     * Update Text Metadata (Manual editor override)
     *
     * Overwrites text attributes of the audiobook in the database, including series information.
     */
    suspend fun updateBookDetails(
        id: String,
        title: String,
        author: String,
        narrator: String,
        description: String,
        year: String,
        series: String
    )

    /**
     * Update Tag Metadata (Silent parser synchronization)
     *
     * Allows background sync pipelines to silently update audio tag fields and duration without exposing scan machinery.
     */
    fun updateMetadata(
        bookId: String,
        title: String?,
        author: String?,
        narrator: String?,
        description: String?,
        duration: Long
    )
}
