package com.viel.aplayer.data.gateway

import com.viel.aplayer.data.store.SearchHistoryEntry
import kotlinx.coroutines.flow.Flow

/**
 * Decoupled Domain Gateway Interface (SearchHistoryGateway)
 * Focuses on reactive updates and persistence management of search history queries.
 * 
 * Core Design Goals:
 * 1. Eradicate God-Class Dependencies: Exposes narrow read/write history tracking operations directly for search ViewModels.
 * 2. Promote Dependency Inversion: Abstracts underlying DataStore implementations, decoupling data format layers.
 */
interface SearchHistoryGateway {

    /**
     * Observe Search History (Reactive queries stream)
     * Reactively tracks the complete list of search history entries, sorted in descending chronological order.
     */
    val searchHistory: Flow<List<SearchHistoryEntry>>

    /**
     * Append History Entry (Asynchronous update operation)
     * Asynchronously inserts or moves a search term to the top of the search history lists.
     */
    suspend fun addToHistory(query: String)

    /**
     * Delete History Entry (Specific entry deletion)
     * Permanently deletes a single search query record from the history list.
     */
    suspend fun deleteFromHistory(history: SearchHistoryEntry)

    /**
     * Purge Search History (Global database purge)
     * Clears all search history entries completely.
     */
    suspend fun clearHistory()
}
