package com.viel.aplayer.application.library.search

import kotlinx.coroutines.flow.Flow

/**
 * Scene-level catalog and history read surface.
 * Exposes only the reactive history stream and scene-owned result projection needed by the search overlay.
 */
interface SearchLibraryReadModel {
    /**
     * DataStore-free search history projection.
     *
     * Emits history rows as SearchHistoryItem so UI callers never depend on the persistence DTO or its timestamp naming.
     */
    val searchHistory: Flow<List<SearchHistoryItem>>

    fun search(query: String): Flow<List<SearchResultSnapshot>>
}
