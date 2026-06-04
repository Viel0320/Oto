package com.viel.aplayer.widget

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.appwidget.updateAll
import androidx.glance.state.PreferencesGlanceStateDefinition

/**
 * Desktop widget state synchronization helper.
 *
 * Core Responsibilities:
 * 1. Persists real-time audiobook playback states, titles, authors, and local cover paths from PlaybackService into the Glance-exclusive DataStore.
 * 2. Trigger GlanceAppWidgetManager to immediately refresh all running widgets on the home screen upon state commits, ensuring real-time UI consistency.
 * 3. Isolates core playback logic and complex layout rendering to maintain loose coupling between components and avoid creating a god class.
 */
object PlayerWidgetStateHelper {

    private const val TAG = "PlayerWidgetStateHelper"

    // Glance preference keys. Definition of keys used for managing widget preferences within Glance.
    val KEY_IS_PLAYING = booleanPreferencesKey("is_playing")
    val KEY_TITLE = stringPreferencesKey("title")
    val KEY_AUTHOR = stringPreferencesKey("author")
    val KEY_COVER_PATH = stringPreferencesKey("cover_path")

    /**
     * Update widget state. Asynchronously commits updated state data and triggers widget recomposition.
     * 
     * @param context Application context.
     * @param isPlaying Indicates if an audiobook is currently active.
     * @param title Name of the audiobook.
     * @param author Author of the audiobook.
     * @param coverPath Local physical cover image file path.
     */
    suspend fun updateWidgetState(
        context: Context,
        isPlaying: Boolean,
        title: String?,
        author: String?,
        coverPath: String?
    ) {
        try {
            // 1. Retrieve the Glance widget manager.
            val manager = GlanceAppWidgetManager(context)
            // Resolve all registered widget IDs representing the PlayerWidget layout.
            val glanceIds = manager.getGlanceIds(PlayerWidget::class.java)
            
            if (glanceIds.isEmpty()) {
                // Skip state writes entirely if there are no active widgets on the screen to conserve I/O costs.
                return
            }

            // 2. Iterate through active widgets and persist the new state values in their respective Preferences DataStores.
            glanceIds.forEach { glanceId ->
                updateAppWidgetState(context, PreferencesGlanceStateDefinition, glanceId) { preferences ->
                    preferences.toMutablePreferences().apply {
                        this[KEY_IS_PLAYING] = isPlaying
                        this[KEY_TITLE] = title ?: ""
                        this[KEY_AUTHOR] = author ?: ""
                        this[KEY_COVER_PATH] = coverPath ?: ""
                    }
                }
            }

            // 3. Request actual UI recomposition.
            PlayerWidget().updateAll(context)
        } catch (e: Exception) {
            Log.e(TAG, "Encountered physical exception when updating widget DataStore state: ${e.message}", e)
        }
    }
}
