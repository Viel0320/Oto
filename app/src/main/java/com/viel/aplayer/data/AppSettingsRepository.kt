package com.viel.aplayer.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.viel.aplayer.data.store.AppSettings
import com.viel.aplayer.data.store.GlassEffectMode
import com.viel.aplayer.data.store.SleepMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "app_settings")

/**
 * App Settings Storage (Manages persistence of user configuration via Jetpack DataStore)
 */
class AppSettingsRepository private constructor(private val dataStore: DataStore<Preferences>) {

    private object PreferencesKeys {
        val HOME_FILTER = stringPreferencesKey("home_filter")
        val IS_GLOBAL_SPEED_ENABLED = booleanPreferencesKey("is_global_speed_enabled")
        val GLOBAL_PLAYBACK_SPEED = floatPreferencesKey("global_playback_speed")
        val IS_CHAPTER_PROGRESS_MODE = booleanPreferencesKey("is_chapter_progress_mode")
        // Cleartext HTTP Switch (Preferences key indicating if cleartext HTTP traffic is permitted for WebDAV/remote servers)
        val IS_CLEARTEXT_TRAFFIC_ALLOWED = booleanPreferencesKey("is_cleartext_traffic_allowed")
        /**
         * Skip Silence Storage (Preferences key tracking whether the silence-skipping player mode is enabled)
         * Unified under default system configurations; custom duration keys have been removed.
         */
        val IS_SKIP_SILENCE_ENABLED = booleanPreferencesKey("is_skip_silence_enabled")
        // Sleep Timer Fade-Out (Preferences key for enabling gradual volume reduction at timer expiration)
        val IS_SLEEP_FADE_OUT_ENABLED = booleanPreferencesKey("is_sleep_fade_out_enabled")
        // Shake-to-Reset Sensor (Preferences key tracking whether shake movements reset active sleep timers)
        val IS_SHAKE_TO_RESET_ENABLED = booleanPreferencesKey("is_shake_to_reset_enabled")
        // Sleep Timer Target Mode (Preferences key storing the target sleep duration behavior, e.g. Regular or EndOfChapter)
        val SLEEP_MODE = stringPreferencesKey("sleep_mode")
        // Glassmorphic Rendering Mode (Preferences key tracking dialog blur style: MiuixBlur vs Material)
        val GLASS_EFFECT_MODE = stringPreferencesKey("glass_effect_mode")
        // Post-Interrupt Auto Rewind (Preferences key storing rewind offset in seconds; 0 disables the feature)
        val AUTO_REWIND_SECONDS = intPreferencesKey("auto_rewind_seconds")
        // Interrupted State Tracker (Preferences key flagging whether the previous session terminated abnormally, e.g. system kill)
        val IS_LAST_PLAYBACK_INTERRUPTED = booleanPreferencesKey("is_last_playback_interrupted")
        // Audio Focus Ducking Avoidance (Preferences key tracking whether focus loss forces explicit playback pause)
        val IS_NOTIFICATION_AVOIDANCE_ENABLED = booleanPreferencesKey("is_notification_avoidance_enabled")
    }

    /**
     * Live Preferences Stream (Exposes a Flow of updated AppSettings from the DataStore repository)
     */
    val settingsFlow: Flow<AppSettings> = dataStore.data.map { preferences ->
        AppSettings(
            homeFilter = preferences[PreferencesKeys.HOME_FILTER] ?: "NotStarted",
            isGlobalSpeedEnabled = preferences[PreferencesKeys.IS_GLOBAL_SPEED_ENABLED] ?: false,
            globalPlaybackSpeed = preferences[PreferencesKeys.GLOBAL_PLAYBACK_SPEED] ?: 1.0f,
            isChapterProgressMode = preferences[PreferencesKeys.IS_CHAPTER_PROGRESS_MODE] ?: false,
            // Read HTTP Policy (Resolve cleartext connection permission, falling back to true for initial WebDAV onboarding)
            isCleartextTrafficAllowed = preferences[PreferencesKeys.IS_CLEARTEXT_TRAFFIC_ALLOWED] ?: true,
            /**
             * Read Skip Silence (Expose silence skipping flag, defaulting to false)
             * Custom threshold offsets and status notification settings have been pruned in this revision.
             */
            isSkipSilenceEnabled = preferences[PreferencesKeys.IS_SKIP_SILENCE_ENABLED] ?: false,
            // Read Sleep Fade-Out (Expose volume fading status, defaulting to true)
            isSleepFadeOutEnabled = preferences[PreferencesKeys.IS_SLEEP_FADE_OUT_ENABLED] ?: true,
            // Read Shake-to-Reset (Expose shake reset flag, defaulting to true)
            isShakeToResetEnabled = preferences[PreferencesKeys.IS_SHAKE_TO_RESET_ENABLED] ?: true,
            // Read Sleep Mode (Parse active SleepMode, defaulting to Regular if empty or invalid)
            sleepMode = preferences[PreferencesKeys.SLEEP_MODE]
                ?.let { runCatching { SleepMode.valueOf(it) }.getOrNull() }
                ?: SleepMode.Regular,
            // Read Glass Mode (Parse glass effect style, falling back to the designated AppSettings default)
            glassEffectMode = preferences[PreferencesKeys.GLASS_EFFECT_MODE]
                ?.let { runCatching { GlassEffectMode.valueOf(it) }.getOrNull() }
                ?: AppSettings.DEFAULT_GLASS_EFFECT_MODE,
            // Read Auto Rewind (Expose rewind duration, defaulting to 0 for disabled state)
            autoRewindSeconds = preferences[PreferencesKeys.AUTO_REWIND_SECONDS] ?: 0,
            // Read Session Interruption (Flag indicating abnormal application restarts, defaulting to false)
            isLastPlaybackInterrupted = preferences[PreferencesKeys.IS_LAST_PLAYBACK_INTERRUPTED] ?: false,
            // Read Ducking Avoidance (Expose focus loss avoidance status, defaulting to false for safe media defaults)
            isNotificationAvoidanceEnabled = preferences[PreferencesKeys.IS_NOTIFICATION_AVOIDANCE_ENABLED] ?: false
        )
    }
    suspend fun updateHomeFilter(filter: String) {
        dataStore.edit { it[PreferencesKeys.HOME_FILTER] = filter }
    }

    suspend fun updateGlobalSpeedEnabled(enabled: Boolean) {
        dataStore.edit { it[PreferencesKeys.IS_GLOBAL_SPEED_ENABLED] = enabled }
    }

    suspend fun updateGlobalPlaybackSpeed(speed: Float) {
        dataStore.edit { it[PreferencesKeys.GLOBAL_PLAYBACK_SPEED] = speed }
    }

    suspend fun updateChapterProgressMode(enabled: Boolean) {
        dataStore.edit { it[PreferencesKeys.IS_CHAPTER_PROGRESS_MODE] = enabled }
    }

    // Write HTTP Policy (Persist user permission settings regarding cleartext HTTP connections)
    suspend fun updateCleartextTrafficAllowed(enabled: Boolean) {
        dataStore.edit { it[PreferencesKeys.IS_CLEARTEXT_TRAFFIC_ALLOWED] = enabled }
    }

     /**
      * Write Skip Silence (Persist skip silence preferences)
      * Custom thresholds and notification triggers are managed implicitly under system defaults.
      */
    suspend fun updateSkipSilenceEnabled(enabled: Boolean) {
        dataStore.edit { it[PreferencesKeys.IS_SKIP_SILENCE_ENABLED] = enabled }
    }

    // Write Sleep Fade-Out (Persist volume fade-out switch settings for sleep timer expirations)
    suspend fun updateSleepFadeOutEnabled(enabled: Boolean) {
        dataStore.edit { it[PreferencesKeys.IS_SLEEP_FADE_OUT_ENABLED] = enabled }
    }

    // Write Shake-to-Reset (Persist shake reset switch settings to toggle sensor inputs on timer start)
    suspend fun updateShakeToResetEnabled(enabled: Boolean) {
        dataStore.edit { it[PreferencesKeys.IS_SHAKE_TO_RESET_ENABLED] = enabled }
    }

    // Write Sleep Mode (Persist target SleepMode layout options into the local data store)
    suspend fun updateSleepMode(mode: SleepMode) {
        dataStore.edit { it[PreferencesKeys.SLEEP_MODE] = mode.name }
    }

    // Write Glass Effect (Persist rendering preference for glassmorphic overlay sheets)
    suspend fun updateGlassEffectMode(mode: GlassEffectMode) {
        dataStore.edit { it[PreferencesKeys.GLASS_EFFECT_MODE] = mode.name }
    }

    // Write Auto Rewind (Persist rewind offset in seconds to offset post-interruption session restarts)
    suspend fun updateAutoRewindSeconds(seconds: Int) {
        dataStore.edit { it[PreferencesKeys.AUTO_REWIND_SECONDS] = seconds }
    }

    // Write Interruption Flag (Flag whether the current playback loop ended normally or via process terminations)
    suspend fun updateLastPlaybackInterrupted(interrupted: Boolean) {
        dataStore.edit { it[PreferencesKeys.IS_LAST_PLAYBACK_INTERRUPTED] = interrupted }
    }

    // Write Ducking Avoidance (Persist notification avoidance configurations for audio focus loss)
    suspend fun updateNotificationAvoidanceEnabled(enabled: Boolean) {
        dataStore.edit { it[PreferencesKeys.IS_NOTIFICATION_AVOIDANCE_ENABLED] = enabled }
    }

    companion object {
        @Volatile
        private var INSTANCE: AppSettingsRepository? = null

        fun getInstance(context: Context): AppSettingsRepository {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: AppSettingsRepository(context.applicationContext.dataStore).also { INSTANCE = it }
            }
        }
    }
}
