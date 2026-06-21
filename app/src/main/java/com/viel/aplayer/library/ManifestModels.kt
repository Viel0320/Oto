package com.viel.aplayer.library


data class ChapterCandidate(
    val title: String,
    val fileKey: String,
    val fileOffsetMs: Long,
    val durationMs: Long = 0L
)

data class MetadataSuggestion(
    val title: String? = null,
    val author: String? = null,
    val narrator: String? = null,
    val year: String? = null,
    val description: String? = null
)
