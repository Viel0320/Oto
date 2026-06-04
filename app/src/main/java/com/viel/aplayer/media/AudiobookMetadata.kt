package com.viel.aplayer.media

import com.viel.aplayer.data.entity.ChapterEntity

/**
 * Metadata Extraction Model (Represents structured metadata parsed from audio files)
 */
data class AudiobookMetadata(
    val title: String,
    val author: String,
    val narrator: String,
    /** Album Identifier (Used during folder scanning to group multiple files into one book) */
    val album: String = "",
    /** Track Sequence Number (Used during heuristic sorting to arrange segments sequentially) */
    val trackIndex: Int? = null,
    val description: String,
    val year: String,
    val durationMs: Long,
    /** Extracted Chapter Entities (List of parsed chapter boundaries, unbound to book ID) */
    val chapters: List<ChapterEntity> = emptyList()
)