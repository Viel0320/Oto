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
 * Data transfer object.
 * Represents a single search query record along with its timestamp.
 */
data class SearchHistoryEntry(
    val query: String,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Search history is lightweight UI state, so it lives in DataStore instead of the main Room database.
 */
class SearchHistoryStore internal constructor(private val dataStore: DataStore<Preferences>) {

    private object PreferencesKeys {
        val ITEMS_JSON = stringPreferencesKey("items_json")
    }

    val history: Flow<List<SearchHistoryEntry>> = dataStore.data.map { preferences ->
        decodeHistory(preferences[PreferencesKeys.ITEMS_JSON])
    }

    suspend fun add(query: String) {
        val normalizedQuery = query.trim()
        if (normalizedQuery.isBlank()) return
        dataStore.edit { preferences ->
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
    }
}
