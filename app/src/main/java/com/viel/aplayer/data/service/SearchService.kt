package com.viel.aplayer.data.service

import com.viel.aplayer.data.gateway.SearchHistoryGateway
import com.viel.aplayer.data.store.SearchHistoryEntry
import com.viel.aplayer.data.store.SearchHistoryStore
import kotlinx.coroutines.flow.Flow

/**
 * Search History Persistence Service (Implements SearchHistoryGateway)
 * 
 * Core Design Goals:
 * 1. Complete Repository Decoupling: Directly connects with SearchHistoryStore in the M6f phase, fully eliminating bloated repository delegations.
 * 2. Re-anchor DataStore Actions: Perfectly preserves underlying stream processing flows for addToHistory, deleteFromHistory, and clearHistory operations.
 */
class SearchService(
    private val searchHistoryStore: SearchHistoryStore
) : SearchHistoryGateway {

    override val searchHistory: Flow<List<SearchHistoryEntry>>
        get() = searchHistoryStore.history

    override suspend fun addToHistory(query: String) {
        // Validate and Append Search Query (DataStore write optimization)
        // Verifies the search query is non-blank before asynchronously prepending it to the persistent history database.
        if (query.isNotBlank()) {
            searchHistoryStore.add(query)
        }
    }

    override suspend fun deleteFromHistory(query: String) {
        // Delete Specific History Query (Targeted entry purge by stable text identity)
        // Permanently removes the designated search history record without exposing the DataStore DTO to upstream scenes.
        searchHistoryStore.delete(query)
    }

    override suspend fun clearHistory() {
        // Clear All Search History (Bulk storage format purge)
        // Cleans the persistent DataStore list, deleting all cached search queries.
        searchHistoryStore.clear()
    }
}
