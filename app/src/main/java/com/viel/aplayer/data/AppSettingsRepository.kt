package com.viel.aplayer.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import com.viel.aplayer.data.store.AppSettings

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "app_settings")

/**
 * 负责应用设置的持久化管理，基于 Jetpack DataStore。
 */
class AppSettingsRepository private constructor(private val context: Context) {

    private object PreferencesKeys {
        val HOME_FILTER = stringPreferencesKey("home_filter")
        val IS_GLOBAL_SPEED_ENABLED = booleanPreferencesKey("is_global_speed_enabled")
        val GLOBAL_PLAYBACK_SPEED = floatPreferencesKey("global_playback_speed")
        val IS_CHAPTER_PROGRESS_MODE = booleanPreferencesKey("is_chapter_progress_mode")
    }

    /**
     * 获取实时设置流。
     */
    val settingsFlow: Flow<AppSettings> = context.dataStore.data.map { preferences ->
        AppSettings(
            homeFilter = preferences[PreferencesKeys.HOME_FILTER] ?: "NotStarted",
            isGlobalSpeedEnabled = preferences[PreferencesKeys.IS_GLOBAL_SPEED_ENABLED] ?: false,
            globalPlaybackSpeed = preferences[PreferencesKeys.GLOBAL_PLAYBACK_SPEED] ?: 1.0f,
            isChapterProgressMode = preferences[PreferencesKeys.IS_CHAPTER_PROGRESS_MODE] ?: false
        )
    }

    suspend fun updateHomeFilter(filter: String) {
        context.dataStore.edit { it[PreferencesKeys.HOME_FILTER] = filter }
    }

    suspend fun updateGlobalSpeedEnabled(enabled: Boolean) {
        context.dataStore.edit { it[PreferencesKeys.IS_GLOBAL_SPEED_ENABLED] = enabled }
    }

    suspend fun updateGlobalPlaybackSpeed(speed: Float) {
        context.dataStore.edit { it[PreferencesKeys.GLOBAL_PLAYBACK_SPEED] = speed }
    }

    suspend fun updateChapterProgressMode(enabled: Boolean) {
        context.dataStore.edit { it[PreferencesKeys.IS_CHAPTER_PROGRESS_MODE] = enabled }
    }

    companion object {
        @Volatile
        private var INSTANCE: AppSettingsRepository? = null

        fun getInstance(context: Context): AppSettingsRepository {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: AppSettingsRepository(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}