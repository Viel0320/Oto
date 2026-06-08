package com.viel.aplayer.application.library.search

/**
 * Search Library Commands (Scene-level search history mutation surface)
 * Keeps search-history writes separate from the broad library facade during the scene migration.
 */
interface SearchLibraryCommands {
    suspend fun saveSearchHistory(query: String)

    /**
     * Delete Search History Item (Scene projection delete command)
     *
     * Accepts the search-scene item instead of the DataStore entry, letting the module translate deletion to the stable query identity.
     */
    suspend fun deleteSearchHistory(history: SearchHistoryItem)

    suspend fun clearSearchHistory()
}
