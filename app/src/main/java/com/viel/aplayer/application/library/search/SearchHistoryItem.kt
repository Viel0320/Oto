package com.viel.aplayer.application.library.search

/**
 * Scene-owned history projection.
 *
 * Carries only the user-visible query text and creation time needed by the search scene, keeping
 * DataStore-specific field names and persistence DTOs out of ViewModel and Compose contracts.
 */
data class SearchHistoryItem(
    val query: String,
    val createdAt: Long
)
