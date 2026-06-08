package com.viel.aplayer.data.store

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

private val Context.searchHistoryDataStore: DataStore<Preferences> by preferencesDataStore(name = "search_history")

/**
 * Search History Entry (Data transfer object)
 * Represents a single search query record along with its timestamp.
 */
data class SearchHistoryEntry(
    val query: String,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Search history is lightweight UI state, so it lives in DataStore instead of the main Room database.
 */
class SearchHistoryStore private constructor(private val dataStore: DataStore<Preferences>) {

    private object PreferencesKeys {
        // A single JSON array keeps ordering and timestamps in one atomic DataStore value.
        val ITEMS_JSON = stringPreferencesKey("items_json")
    }

    // Decoupled DataStore Stream (Context-free data flow)
    // Fetches history query states via private DataStore instances, avoiding context bindings.
    val history: Flow<List<SearchHistoryEntry>> = dataStore.data.map { preferences ->
        // Bad or old values should not break search UI; they are treated as an empty history list.
        decodeHistory(preferences[PreferencesKeys.ITEMS_JSON])
    }

    suspend fun add(query: String) {
        val normalizedQuery = query.trim()
        if (normalizedQuery.isBlank()) return
        // Modify DataStore directly (Asynchronous editor write)
        // Mutates Preference states directly via standard DataStore edit callbacks.
        dataStore.edit { preferences ->
            // Re-adding the same query moves it to the top, matching the previous primary-key replacement behavior.
            val updated = listOf(SearchHistoryEntry(normalizedQuery, System.currentTimeMillis())) +
                decodeHistory(preferences[PreferencesKeys.ITEMS_JSON])
                    .filterNot { it.query == normalizedQuery }
            preferences[PreferencesKeys.ITEMS_JSON] = encodeHistory(updated.take(MAX_HISTORY_ITEMS))
        }
    }

    suspend fun delete(query: String) {
        val normalizedQuery = query.trim()
        if (normalizedQuery.isBlank()) return
        dataStore.edit { preferences ->
            // Query Identity Deletion (Remove records using the user-visible stable key)
            // DataStore keeps timestamps for ordering, but deletion intentionally ignores time so duplicate stale rows collapse together.
            val updated = decodeHistory(preferences[PreferencesKeys.ITEMS_JSON])
                .filterNot { it.query == normalizedQuery }
            if (updated.isEmpty()) {
                preferences.remove(PreferencesKeys.ITEMS_JSON)
            } else {
                preferences[PreferencesKeys.ITEMS_JSON] = encodeHistory(updated)
            }
        }
    }

    suspend fun clear() {
        dataStore.edit { preferences ->
            // Clear removes the DataStore key so every observer gets an empty list immediately.
            preferences.remove(PreferencesKeys.ITEMS_JSON)
        }
    }

    private fun decodeHistory(rawJson: String?): List<SearchHistoryEntry> {
        if (rawJson.isNullOrBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(rawJson)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val query = item.optString(FIELD_QUERY).trim()
                    if (query.isNotBlank()) {
                        // Missing timestamps are placed at the end but kept readable for older values.
                        add(SearchHistoryEntry(query, item.optLong(FIELD_TIMESTAMP, 0L)))
                    }
                }
            }.distinctBy { it.query }
                .sortedByDescending { it.timestamp }
                .take(MAX_HISTORY_ITEMS)
        }.getOrDefault(emptyList())
    }

    private fun encodeHistory(history: List<SearchHistoryEntry>): String {
        val array = JSONArray()
        history.take(MAX_HISTORY_ITEMS).forEach { item ->
            // JSON encoding avoids delimiter bugs for queries containing tabs, commas, or line breaks.
            array.put(JSONObject().apply {
                put(FIELD_QUERY, item.query)
                put(FIELD_TIMESTAMP, item.timestamp)
            })
        }
        return array.toString()
    }

    companion object {
        private const val MAX_HISTORY_ITEMS = 20
        private const val FIELD_QUERY = "query"
        private const val FIELD_TIMESTAMP = "timestamp"

        @Volatile
        private var INSTANCE: SearchHistoryStore? = null

        fun getInstance(context: Context): SearchHistoryStore {
            return INSTANCE ?: synchronized(this) {
                // Context Decoupled Initialization (Memory leak prevention)
                // Passes applicationContext's dataStore to the constructor, keeping singleton references static without tracking raw contexts.
                INSTANCE ?: SearchHistoryStore(context.applicationContext.searchHistoryDataStore).also { INSTANCE = it }
            }
        }
    }
}
