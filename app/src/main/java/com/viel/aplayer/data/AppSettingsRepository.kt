package com.viel.aplayer.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.viel.aplayer.shared.settings.AppLanguage
import com.viel.aplayer.shared.settings.AppSettings
import com.viel.aplayer.shared.settings.GlassEffectMode
import com.viel.aplayer.shared.settings.HomeBookStatusFilter
import com.viel.aplayer.shared.settings.HomeFilter
import com.viel.aplayer.shared.settings.HomeSortDirection
import com.viel.aplayer.shared.settings.HomeSortRule
import com.viel.aplayer.shared.settings.HomeViewStyle
import com.viel.aplayer.shared.settings.PlaybackSeekStepConfig
import com.viel.aplayer.shared.settings.SeekStepSeconds
import com.viel.aplayer.shared.settings.SleepMode
import com.viel.aplayer.shared.settings.ThemeMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Manages persistence of user configuration via Jetpack DataStore.
 */
class AppSettingsRepository internal constructor(private val dataStore: DataStore<Preferences>) :
    com.viel.aplayer.application.library.settings.AppSettingsReadModel,
    com.viel.aplayer.application.library.settings.AppSettingsCommands {

    @Volatile
    private var _cachedSettings = AppSettings()
    override val cachedSettings: AppSettings get() = _cachedSettings

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private object PlaybackSettingsBounds {
        private const val DEFAULT_PLAYBACK_SPEED = 1.0f
        private const val MIN_PLAYBACK_SPEED = 0.25f
        private const val MAX_PLAYBACK_SPEED = 2.0f
        private const val DEFAULT_AUTO_REWIND_SECONDS = 0
        private const val MIN_AUTO_REWIND_SECONDS = 0
        private const val MAX_AUTO_REWIND_SECONDS = 30
        private const val MIN_SUBTITLE_SYNC_OFFSET_MS = -30_000L
        private const val MAX_SUBTITLE_SYNC_OFFSET_MS = 30_000L
        private const val MIN_PLAYBACK_BUFFER_BYTES = 8L * 1024L * 1024L
        private const val MAX_PLAYBACK_BUFFER_BYTES = 256L * 1024L * 1024L

        fun speedOrDefault(value: Float?): Float =
            value?.takeIf { it.isFinite() && it in MIN_PLAYBACK_SPEED..MAX_PLAYBACK_SPEED }
                ?: DEFAULT_PLAYBACK_SPEED

        fun autoRewindSecondsOrDefault(value: Int?): Int =
            value?.coerceIn(MIN_AUTO_REWIND_SECONDS, MAX_AUTO_REWIND_SECONDS)
                ?: DEFAULT_AUTO_REWIND_SECONDS

        fun subtitleSyncOffsetMsOrDefault(value: Long?): Long =
            value?.coerceIn(MIN_SUBTITLE_SYNC_OFFSET_MS, MAX_SUBTITLE_SYNC_OFFSET_MS) ?: 0L

        fun playbackBufferBytesOrDefault(value: Long?): Long =
            value?.takeIf { it in MIN_PLAYBACK_BUFFER_BYTES..MAX_PLAYBACK_BUFFER_BYTES }
                ?: AppSettings.DEFAULT_PLAYBACK_BUFFER_MAX_BYTES

    }

    private object PreferencesKeys {
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val APP_LANGUAGE = stringPreferencesKey("app_language")
        val IS_DYNAMIC_COLOR_ENABLED = booleanPreferencesKey("is_dynamic_color_enabled")
        val IS_AMOLED_ENABLED = booleanPreferencesKey("is_amoled_enabled")
        val HOME_FILTER = stringPreferencesKey("home_filter")
        val HOME_BOOK_STATUS_FILTER = stringPreferencesKey("home_book_status_filter")
        val HOME_VIEW_STYLE = stringPreferencesKey("home_view_style")
        val HOME_SORT_RULE = stringPreferencesKey("home_sort_rule")
        val HOME_SORT_DIRECTION = stringPreferencesKey("home_sort_direction")
        val IS_GLOBAL_SPEED_ENABLED = booleanPreferencesKey("is_global_speed_enabled")
        val GLOBAL_PLAYBACK_SPEED = floatPreferencesKey("global_playback_speed")
        val IS_CHAPTER_PROGRESS_MODE = booleanPreferencesKey("is_chapter_progress_mode")
        val IS_ALLOW_INSECURE_TLS = booleanPreferencesKey("is_allow_insecure_tls")
        val IS_CLEARTEXT_TRAFFIC_ALLOWED = booleanPreferencesKey("is_cleartext_traffic_allowed")
        /**
         * Preferences key tracking whether the silence-skipping player mode is enabled.
         * Unified under default system configurations; custom duration keys have been removed.
         */
        val IS_SKIP_SILENCE_ENABLED = booleanPreferencesKey("is_skip_silence_enabled")
        val IS_SLEEP_FADE_OUT_ENABLED = booleanPreferencesKey("is_sleep_fade_out_enabled")
        val IS_SHAKE_TO_RESET_ENABLED = booleanPreferencesKey("is_shake_to_reset_enabled")
        val SLEEP_MODE = stringPreferencesKey("sleep_mode")
        val GLASS_EFFECT_MODE = stringPreferencesKey("glass_effect_mode")
        val AUTO_REWIND_SECONDS = intPreferencesKey("auto_rewind_seconds")
        val SEEK_BACKWARD_SECONDS = intPreferencesKey("seek_backward_seconds")
        val SEEK_FORWARD_SECONDS = intPreferencesKey("seek_forward_seconds")
        val SUBTITLE_SYNC_OFFSET_MS = longPreferencesKey("subtitle_sync_offset_ms")
        val IS_LAST_PLAYBACK_INTERRUPTED = booleanPreferencesKey("is_last_playback_interrupted")
        val IS_NOTIFICATION_AVOIDANCE_ENABLED = booleanPreferencesKey("is_notification_avoidance_enabled")
        val LEGACY_PLAYBACK_CACHE_MAX_BYTES = longPreferencesKey("playback_cache_max_bytes")
        val PLAYBACK_BUFFER_MAX_BYTES = longPreferencesKey("playback_buffer_max_bytes")
        val LEGACY_PLAYBACK_BUFFER_DURATION_MS = intPreferencesKey("playback_buffer_duration_ms")
        val IS_DOWNLOAD_WIFI_ONLY = booleanPreferencesKey("is_download_wifi_only")
    }

    /**
     * Exposes a Flow of updated AppSettings from the DataStore repository.
     */
    override val settingsFlow: Flow<AppSettings> = dataStore.data.map { preferences ->
        AppSettings(
            themeMode = preferences[PreferencesKeys.THEME_MODE]
                ?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() }
                ?: ThemeMode.System,
            appLanguage = preferences[PreferencesKeys.APP_LANGUAGE]
                ?.let { runCatching { AppLanguage.valueOf(it) }.getOrNull() }
                ?: AppLanguage.System,
            isDynamicColorEnabled = preferences[PreferencesKeys.IS_DYNAMIC_COLOR_ENABLED] ?: true,
            isAmoledEnabled = preferences[PreferencesKeys.IS_AMOLED_ENABLED] ?: false,
            homeFilter = preferences[PreferencesKeys.HOME_FILTER]
                ?.let { runCatching { HomeFilter.valueOf(it) }.getOrNull() }
                ?: HomeFilter.NotStarted,
            homeBookStatusFilter = preferences[PreferencesKeys.HOME_BOOK_STATUS_FILTER]
                ?.let { runCatching { HomeBookStatusFilter.valueOf(it) }.getOrNull() }
                ?: HomeBookStatusFilter.All,
            homeViewStyle = preferences[PreferencesKeys.HOME_VIEW_STYLE]
                ?.let { runCatching { HomeViewStyle.valueOf(it) }.getOrNull() }
                ?: HomeViewStyle.List,
            homeSortRule = preferences[PreferencesKeys.HOME_SORT_RULE]
                ?.let { runCatching { HomeSortRule.valueOf(it) }.getOrNull() }
                ?: HomeSortRule.Author,
            homeSortDirection = preferences[PreferencesKeys.HOME_SORT_DIRECTION]
                ?.let { runCatching { HomeSortDirection.valueOf(it) }.getOrNull() }
                ?: HomeSortDirection.Ascending,
            isGlobalSpeedEnabled = preferences[PreferencesKeys.IS_GLOBAL_SPEED_ENABLED] ?: false,
            globalPlaybackSpeed = PlaybackSettingsBounds.speedOrDefault(preferences[PreferencesKeys.GLOBAL_PLAYBACK_SPEED]),
            isChapterProgressMode = preferences[PreferencesKeys.IS_CHAPTER_PROGRESS_MODE] ?: false,
            isAllowInsecureTls = preferences[PreferencesKeys.IS_ALLOW_INSECURE_TLS] ?: false,
            isCleartextTrafficAllowed = preferences[PreferencesKeys.IS_CLEARTEXT_TRAFFIC_ALLOWED] ?: false,
            /**
             * Expose silence skipping flag, defaulting to false.
             * Custom threshold offsets and status notification settings have been pruned in this revision.
             */
            isSkipSilenceEnabled = preferences[PreferencesKeys.IS_SKIP_SILENCE_ENABLED] ?: false,
            isSleepFadeOutEnabled = preferences[PreferencesKeys.IS_SLEEP_FADE_OUT_ENABLED] ?: true,
            isShakeToResetEnabled = preferences[PreferencesKeys.IS_SHAKE_TO_RESET_ENABLED] ?: true,
            sleepMode = preferences[PreferencesKeys.SLEEP_MODE]
                ?.let { runCatching { SleepMode.valueOf(it) }.getOrNull() }
                ?: SleepMode.Regular,
            glassEffectMode = preferences[PreferencesKeys.GLASS_EFFECT_MODE]
                ?.let { runCatching { GlassEffectMode.valueOf(it) }.getOrNull() }
                ?: AppSettings.DEFAULT_GLASS_EFFECT_MODE,
            autoRewindSeconds = PlaybackSettingsBounds.autoRewindSecondsOrDefault(preferences[PreferencesKeys.AUTO_REWIND_SECONDS]),
            playbackSeekStepConfig = PlaybackSeekStepConfig.fromStored(
                backwardSeconds = preferences[PreferencesKeys.SEEK_BACKWARD_SECONDS],
                forwardSeconds = preferences[PreferencesKeys.SEEK_FORWARD_SECONDS]
            ),
            subtitleSyncOffsetMs = PlaybackSettingsBounds.subtitleSyncOffsetMsOrDefault(
                preferences[PreferencesKeys.SUBTITLE_SYNC_OFFSET_MS]
            ),
            isLastPlaybackInterrupted = preferences[PreferencesKeys.IS_LAST_PLAYBACK_INTERRUPTED] ?: false,
            isNotificationAvoidanceEnabled = preferences[PreferencesKeys.IS_NOTIFICATION_AVOIDANCE_ENABLED] ?: false,
            playbackBufferMaxBytes = PlaybackSettingsBounds.playbackBufferBytesOrDefault(
                preferences[PreferencesKeys.PLAYBACK_BUFFER_MAX_BYTES]
                    ?: preferences[PreferencesKeys.LEGACY_PLAYBACK_CACHE_MAX_BYTES]
            ),
            isDownloadWifiOnly = preferences[PreferencesKeys.IS_DOWNLOAD_WIFI_ONLY] ?: true
        )
    }

    init {
        scope.launch {
            migrateLegacyPlaybackBufferPreferences()
            settingsFlow.collect {
                _cachedSettings = it
            }
        }
    }

    private suspend fun migrateLegacyPlaybackBufferPreferences() {
        dataStore.edit { preferences ->
            val legacyBytes = preferences[PreferencesKeys.LEGACY_PLAYBACK_CACHE_MAX_BYTES]
            if (preferences[PreferencesKeys.PLAYBACK_BUFFER_MAX_BYTES] == null && legacyBytes != null) {
                preferences[PreferencesKeys.PLAYBACK_BUFFER_MAX_BYTES] = PlaybackSettingsBounds.playbackBufferBytesOrDefault(legacyBytes)
            }
            if (legacyBytes != null) {
                preferences.remove(PreferencesKeys.LEGACY_PLAYBACK_CACHE_MAX_BYTES)
            }
            preferences.remove(PreferencesKeys.LEGACY_PLAYBACK_BUFFER_DURATION_MS)
        }
    }

    override suspend fun updateHomeFilter(filter: HomeFilter) {
        dataStore.edit { it[PreferencesKeys.HOME_FILTER] = filter.name }
    }

    override suspend fun updateHomeBookStatusFilter(filter: HomeBookStatusFilter) {
        dataStore.edit { it[PreferencesKeys.HOME_BOOK_STATUS_FILTER] = filter.name }
    }

    override suspend fun updateHomeViewStyle(style: HomeViewStyle) {
        dataStore.edit { it[PreferencesKeys.HOME_VIEW_STYLE] = style.name }
    }

    override suspend fun updateHomeSortRule(rule: HomeSortRule) {
        dataStore.edit { it[PreferencesKeys.HOME_SORT_RULE] = rule.name }
    }

    override suspend fun updateHomeSortDirection(direction: HomeSortDirection) {
        dataStore.edit { it[PreferencesKeys.HOME_SORT_DIRECTION] = direction.name }
    }

    override suspend fun updateGlobalSpeedEnabled(enabled: Boolean) {
        dataStore.edit { it[PreferencesKeys.IS_GLOBAL_SPEED_ENABLED] = enabled }
    }

    override suspend fun updateGlobalPlaybackSpeed(speed: Float) {
        dataStore.edit { it[PreferencesKeys.GLOBAL_PLAYBACK_SPEED] = PlaybackSettingsBounds.speedOrDefault(speed) }
    }

    override suspend fun updateChapterProgressMode(enabled: Boolean) {
        dataStore.edit { it[PreferencesKeys.IS_CHAPTER_PROGRESS_MODE] = enabled }
    }

    override suspend fun updateAllowInsecureTls(enabled: Boolean) {
        dataStore.edit { it[PreferencesKeys.IS_ALLOW_INSECURE_TLS] = enabled }
    }

    override suspend fun updateCleartextTrafficAllowed(enabled: Boolean) {
        dataStore.edit { it[PreferencesKeys.IS_CLEARTEXT_TRAFFIC_ALLOWED] = enabled }
    }

    /**
      * Persist skip silence preferences.
      * Custom thresholds and notification triggers are managed implicitly under system defaults.
      */
    override suspend fun updateSkipSilenceEnabled(enabled: Boolean) {
        dataStore.edit { it[PreferencesKeys.IS_SKIP_SILENCE_ENABLED] = enabled }
    }

    override suspend fun updateSleepFadeOutEnabled(enabled: Boolean) {
        dataStore.edit { it[PreferencesKeys.IS_SLEEP_FADE_OUT_ENABLED] = enabled }
    }

    override suspend fun updateShakeToResetEnabled(enabled: Boolean) {
        dataStore.edit { it[PreferencesKeys.IS_SHAKE_TO_RESET_ENABLED] = enabled }
    }

    override suspend fun updateSleepMode(mode: SleepMode) {
        dataStore.edit { it[PreferencesKeys.SLEEP_MODE] = mode.name }
    }

    override suspend fun updateGlassEffectMode(mode: GlassEffectMode) {
        dataStore.edit { it[PreferencesKeys.GLASS_EFFECT_MODE] = mode.name }
    }

    override suspend fun updateAutoRewindSeconds(seconds: Int) {
        dataStore.edit { it[PreferencesKeys.AUTO_REWIND_SECONDS] = PlaybackSettingsBounds.autoRewindSecondsOrDefault(seconds) }
    }

    override suspend fun updateSeekBackwardSeconds(step: SeekStepSeconds) {
        dataStore.edit { it[PreferencesKeys.SEEK_BACKWARD_SECONDS] = step.seconds }
    }

    override suspend fun updateSeekForwardSeconds(step: SeekStepSeconds) {
        dataStore.edit { it[PreferencesKeys.SEEK_FORWARD_SECONDS] = step.seconds }
    }

    override suspend fun updateSubtitleSyncOffsetMs(offsetMs: Long) {
        dataStore.edit { it[PreferencesKeys.SUBTITLE_SYNC_OFFSET_MS] = PlaybackSettingsBounds.subtitleSyncOffsetMsOrDefault(offsetMs) }
    }

    override suspend fun updateLastPlaybackInterrupted(interrupted: Boolean) {
        dataStore.edit { it[PreferencesKeys.IS_LAST_PLAYBACK_INTERRUPTED] = interrupted }
    }

    override suspend fun updateNotificationAvoidanceEnabled(enabled: Boolean) {
        dataStore.edit { it[PreferencesKeys.IS_NOTIFICATION_AVOIDANCE_ENABLED] = enabled }
    }

    override suspend fun updatePlaybackBufferMaxBytes(bytes: Long) {
        dataStore.edit { it[PreferencesKeys.PLAYBACK_BUFFER_MAX_BYTES] = PlaybackSettingsBounds.playbackBufferBytesOrDefault(bytes) }
    }

    override suspend fun updateDownloadWifiOnly(enabled: Boolean) {
        dataStore.edit { it[PreferencesKeys.IS_DOWNLOAD_WIFI_ONLY] = enabled }
    }

    override suspend fun updateThemeMode(mode: ThemeMode) {
        dataStore.edit { it[PreferencesKeys.THEME_MODE] = mode.name }
    }

    override suspend fun updateAppLanguage(language: AppLanguage) {
        dataStore.edit { it[PreferencesKeys.APP_LANGUAGE] = language.name }
    }

    override suspend fun updateDynamicColorEnabled(enabled: Boolean) {
        dataStore.edit { it[PreferencesKeys.IS_DYNAMIC_COLOR_ENABLED] = enabled }
    }

    override suspend fun updateAmoledEnabled(enabled: Boolean) {
        dataStore.edit { it[PreferencesKeys.IS_AMOLED_ENABLED] = enabled }
    }

    companion object {
        @androidx.annotation.VisibleForTesting
        fun createForTesting(dataStore: DataStore<Preferences>): AppSettingsRepository =
            AppSettingsRepository(dataStore)
    }
}
