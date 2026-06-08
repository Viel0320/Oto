package com.viel.aplayer.application.library.search

import com.viel.aplayer.data.entity.BookWithProgress
import com.viel.aplayer.data.gateway.SearchHistoryGateway
import com.viel.aplayer.data.store.SearchHistoryEntry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Default Search Library Module (Adapter from granular gateways to the search scene)
 * Combines search-history persistence with query planning without exposing a broad library facade to UI code.
 */
class DefaultSearchLibraryModule(
    private val searchHistoryGateway: SearchHistoryGateway,
    private val queryPlanner: SearchQueryPlanner
) : SearchLibraryReadModel, SearchLibraryCommands {
    override val searchHistory: Flow<List<SearchHistoryItem>>
        get() = searchHistoryGateway.searchHistory.map { entries ->
            // Search History Projection (Hide DataStore DTOs behind the search-scene module)
            // The persistence layer keeps timestamp storage details while UI contracts receive createdAt on a scene-owned item.
            entries.map { entry -> entry.toSearchHistoryItem() }
        }

    override fun search(query: String): Flow<List<SearchResultSnapshot>> {
        return queryPlanner.search(query).map { results ->
            // Search Result Projection (Hide Room relationship rows behind the search adapter)
            // The planner can keep using gateway-native rows while callers receive only the fields the scene renders.
            results.map { result -> result.toSearchResultSnapshot() }
        }
    }

    override suspend fun saveSearchHistory(query: String) {
        val normalizedQuery = query.trim()
        if (normalizedQuery.isBlank()) return

        // Search History Normalization (Keep blank filtering at the command boundary)
        // The ViewModel can pass raw text while this module guarantees only meaningful trimmed queries reach storage.
        searchHistoryGateway.addToHistory(normalizedQuery)
    }

    override suspend fun deleteSearchHistory(history: SearchHistoryItem) {
        // Search History Delete Translation (Use query as the stable storage identity)
        // Search history entries are unique by query text, so the scene item is translated to the persistence command here.
        searchHistoryGateway.deleteFromHistory(history.query)
    }

    override suspend fun clearSearchHistory() {
        searchHistoryGateway.clearHistory()
    }
}

/**
 * Search Result Mapper (Convert gateway rows into search-scene snapshots)
 *
 * Keeps BookWithProgress knowledge local to the default adapter so SearchLibraryReadModel and UI callers
 * never need to import Room entity or relationship types.
 */
private fun BookWithProgress.toSearchResultSnapshot(): SearchResultSnapshot {
    return SearchResultSnapshot(
        id = book.id,
        title = book.title,
        author = book.author,
        narrator = book.narrator,
        totalDurationMs = book.totalDurationMs,
        thumbnailPath = book.thumbnailPath,
        coverPath = book.coverPath,
        coverLastUpdated = book.lastScannedAt,
        progressPercent = progressPercent
    )
}

/**
 * Search History Mapper (Convert persistence history rows into scene items)
 *
 * Keeps SearchHistoryEntry usage local to this adapter so ViewModel, route, screen, and scene interfaces stay DataStore-free.
 */
private fun SearchHistoryEntry.toSearchHistoryItem(): SearchHistoryItem {
    return SearchHistoryItem(
        query = query,
        createdAt = timestamp
    )
}
