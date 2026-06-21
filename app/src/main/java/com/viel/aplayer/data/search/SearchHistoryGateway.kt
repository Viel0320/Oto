package com.viel.aplayer.data.search

import com.viel.aplayer.data.store.SearchHistoryEntry
import kotlinx.coroutines.flow.Flow

/**
 * SearchHistoryGateway.
 * Focuses on reactive updates and persistence management of search history queries.
 *
 * Core Design Goals:
 * 1. Eradicate God-Class Dependencies: Exposes narrow read/write history tracking operations directly for search ViewModels.
 * 2. Promote Dependency Inversion: Abstracts underlying DataStore implementations, decoupling data format layers.
 */
interface SearchHistoryGateway {

    /**
     * Reactive queries stream.
     * Reactively tracks the complete list of search history entries, sorted in descending chronological order.
     */
    val searchHistory: Flow<List<SearchHistoryEntry>>

    /**
     * Asynchronous update operation.
     * Asynchronously inserts or moves a search term to the top of the search history lists.
     */
    suspend fun addToHistory(query: String)

    /**
     * Specific entry deletion.
     * Permanently deletes a single search query record from the history list.
     */
    /**
     * Stable user-visible identity deletion.
     *
     * Deletes by normalized query text so callers outside the persistence layer do not need to carry DataStore entry objects.
     */
    suspend fun deleteFromHistory(query: String)

    /**
     * Global database purge.
     * Clears all search history entries completely.
     */
    suspend fun clearHistory()
}
