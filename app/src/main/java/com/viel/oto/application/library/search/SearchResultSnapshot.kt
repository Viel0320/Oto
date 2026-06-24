package com.viel.oto.application.library.search

/**
 * UI-facing search result projection.
 *
 * Carries only the fields rendered by the search scene so Room relationship models stay behind
 * the search module adapter instead of becoming part of the ViewModel or composable interface.
 */
data class SearchResultSnapshot(
    val id: String,
    val title: String,
    val author: String,
    val narrator: String,
    val totalDurationMs: Long,
    val thumbnailPath: String?,
    val coverPath: String?,
    val coverLastUpdated: Long,
    val progressPercent: Int
)
