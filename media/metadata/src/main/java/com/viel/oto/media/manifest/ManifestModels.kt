package com.viel.oto.media.manifest

/**
 * Chapter candidate produced by external manifest parsers before the import pipeline binds it to a stored book.
 */
data class ChapterCandidate(
    val title: String,
    val fileKey: String,
    val fileOffsetMs: Long,
    val durationMs: Long = 0L
)

/**
 * Book-level metadata suggested by sidecar manifests before audio-file metadata and user edits are reconciled.
 */
data class MetadataSuggestion(
    val title: String? = null,
    val author: String? = null,
    val narrator: String? = null,
    val year: String? = null,
    val description: String? = null
)
