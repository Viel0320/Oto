package com.viel.oto.data.search

import com.viel.oto.data.store.SearchHistoryEntry
import com.viel.oto.data.store.SearchHistoryStore
import kotlinx.coroutines.flow.Flow

/**
 * Implements SearchHistoryGateway.
 *
 * Core Design Goals:
 * 1. Complete Repository Decoupling: Directly connects with SearchHistoryStore in the M6f phase, fully eliminating bloated repository delegations.
 * 2. Re-anchor DataStore Actions: Perfectly preserves underlying stream processing flows for addToHistory, deleteFromHistory, and clearHistory operations.
 */
class SearchHistoryGatewayImpl(
    private val searchHistoryStore: SearchHistoryStore
) : SearchHistoryGateway {

    override val searchHistory: Flow<List<SearchHistoryEntry>>
        get() = searchHistoryStore.history

    override suspend fun addToHistory(query: String) {
        if (query.isNotBlank()) {
            searchHistoryStore.add(query)
        }
    }

    override suspend fun deleteFromHistory(query: String) {
        searchHistoryStore.delete(query)
    }

    override suspend fun clearHistory() {
        searchHistoryStore.clear()
    }
}
