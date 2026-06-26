package com.viel.oto.data.metadata

import com.viel.oto.data.entity.BookFileEntity
import com.viel.oto.data.entity.ChapterEntity

/**
 * Data-owned projection of audio metadata needed by the forced refresh workflow.
 *
 * Parser modules can map their richer metadata model into this shape, while Room-facing code keeps ownership of
 * persistence rules and no longer imports media parser implementation types.
 */
data class MetadataRefreshRecord(
    val title: String,
    val author: String,
    val narrator: String,
    val album: String,
    val description: String,
    val year: String,
    val durationMs: Long,
    val chapters: List<ChapterEntity>
)

/**
 * Narrow parser adapter contract for user-triggered metadata refresh.
 *
 * The data layer only supplies the selected persisted file and receives the normalized fields it can write back to
 * Room; media-owned routing, tag parsing, and range reads stay behind the adapter implementation.
 */
interface MetadataRefreshSource {
    /**
     * Extracts persisted metadata fields from the selected primary audio file.
     */
    suspend fun extract(file: BookFileEntity): MetadataRefreshRecord
}
