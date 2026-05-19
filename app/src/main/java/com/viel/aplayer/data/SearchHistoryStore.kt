package com.viel.aplayer.data

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
 * Search history is lightweight UI state, so it lives in DataStore instead of the main Room database.
 */
class SearchHistoryStore private constructor(context: Context) {
    private val context = context.applicationContext

    private object PreferencesKeys {
        // A single JSON array keeps ordering and timestamps in one atomic DataStore value.
        val ITEMS_JSON = stringPreferencesKey("items_json")
    }

    val history: Flow<List<SearchHistoryEntity>> = context.searchHistoryDataStore.data.map { preferences ->
        // Bad or old values should not break search UI; they are treated as an empty history list.
        decodeHistory(preferences[PreferencesKeys.ITEMS_JSON])
    }

    suspend fun add(query: String) {
        val normalizedQuery = query.trim()
        if (normalizedQuery.isBlank()) return
        context.searchHistoryDataStore.edit { preferences ->
            // Re-adding the same query moves it to the top, matching the previous primary-key replacement behavior.
            val updated = listOf(SearchHistoryEntity(normalizedQuery, System.currentTimeMillis())) +
                decodeHistory(preferences[PreferencesKeys.ITEMS_JSON])
                    .filterNot { it.query == normalizedQuery }
            preferences[PreferencesKeys.ITEMS_JSON] = encodeHistory(updated.take(MAX_HISTORY_ITEMS))
        }
    }

    suspend fun delete(history: SearchHistoryEntity) {
        context.searchHistoryDataStore.edit { preferences ->
            // Single-item deletion only compares the query text because it is the stable user-visible identity.
            val updated = decodeHistory(preferences[PreferencesKeys.ITEMS_JSON])
                .filterNot { it.query == history.query }
            if (updated.isEmpty()) {
                preferences.remove(PreferencesKeys.ITEMS_JSON)
            } else {
                preferences[PreferencesKeys.ITEMS_JSON] = encodeHistory(updated)
            }
        }
    }

    suspend fun clear() {
        context.searchHistoryDataStore.edit { preferences ->
            // Clear removes the DataStore key so every observer gets an empty list immediately.
            preferences.remove(PreferencesKeys.ITEMS_JSON)
        }
    }

    private fun decodeHistory(rawJson: String?): List<SearchHistoryEntity> {
        if (rawJson.isNullOrBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(rawJson)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val query = item.optString(FIELD_QUERY).trim()
                    if (query.isNotBlank()) {
                        // Missing timestamps are placed at the end but kept readable for older values.
                        add(SearchHistoryEntity(query, item.optLong(FIELD_TIMESTAMP, 0L)))
                    }
                }
            }.distinctBy { it.query }
                .sortedByDescending { it.timestamp }
                .take(MAX_HISTORY_ITEMS)
        }.getOrDefault(emptyList())
    }

    private fun encodeHistory(history: List<SearchHistoryEntity>): String {
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
                INSTANCE ?: SearchHistoryStore(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}
