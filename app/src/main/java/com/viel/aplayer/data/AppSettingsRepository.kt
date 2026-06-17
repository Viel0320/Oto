package com.viel.aplayer.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.viel.aplayer.data.store.AppLanguage
import com.viel.aplayer.data.store.AppSettings
import com.viel.aplayer.data.store.GlassEffectMode
import com.viel.aplayer.data.store.HomeBookStatusFilter
import com.viel.aplayer.data.store.HomeFilter
import com.viel.aplayer.data.store.HomeSortDirection
import com.viel.aplayer.data.store.HomeSortRule
import com.viel.aplayer.data.store.HomeViewStyle
import com.viel.aplayer.data.store.PlaybackSeekStepConfig
import com.viel.aplayer.data.store.SeekStepSeconds
import com.viel.aplayer.data.store.SleepMode
import com.viel.aplayer.data.store.ThemeMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "app_settings")

/**
 * App Settings Storage (Manages persistence of user configuration via Jetpack DataStore)
 */
// Title: Interface Implementation Binding (Bind AppSettingsRepository to application layer interfaces to allow UI decoupling)
// Implementing AppSettingsReadModel and AppSettingsCommands allows VMs to interact with settings abstractions instead of concrete class types.
class AppSettingsRepository private constructor(private val dataStore: DataStore<Preferences>) :
    com.viel.aplayer.application.library.settings.AppSettingsReadModel,
    com.viel.aplayer.application.library.settings.AppSettingsCommands {

    // Pre-Cached AppSettings (Provides instant sync access to current settings to avoid main thread I/O or runBlocking calls)
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
        private const val MIN_PLAYBACK_BUFFER_BYTES = 8L * 1024L * 1024L
        private const val MAX_PLAYBACK_BUFFER_BYTES = 256L * 1024L * 1024L

        // Playback Speed Normalization (Keeps portable speed settings inside Media3-supported product bounds)
        // Restored DataStore payloads can contain NaN, infinities, or stale floats, so invalid values fall back to the neutral 1x rate.
        fun speedOrDefault(value: Float?): Float =
            value?.takeIf { it.isFinite() && it in MIN_PLAYBACK_SPEED..MAX_PLAYBACK_SPEED }
                ?: DEFAULT_PLAYBACK_SPEED

        // Auto-Rewind Normalization (Keeps portable rewind settings inside the settings slider contract)
        // Restored or manually edited values are clamped because 0 disables the feature and 30 seconds is the largest supported resume offset.
        fun autoRewindSecondsOrDefault(value: Int?): Int =
            value?.coerceIn(MIN_AUTO_REWIND_SECONDS, MAX_AUTO_REWIND_SECONDS)
                ?: DEFAULT_AUTO_REWIND_SECONDS

        // Playback Buffer Size Normalization (Keeps restored memory-buffer settings within safe RAM-sized choices)
        // Old disk-cache values such as multi-gigabyte limits now fall back to the memory-buffer default instead of risking excessive allocation targets.
        fun playbackBufferBytesOrDefault(value: Long?): Long =
            value?.takeIf { it in MIN_PLAYBACK_BUFFER_BYTES..MAX_PLAYBACK_BUFFER_BYTES }
                ?: AppSettings.DEFAULT_PLAYBACK_BUFFER_MAX_BYTES

    }

    private object PreferencesKeys {
        // Theme Mode Storage Key (Key tracking theme preference, e.g. System, Light, or Dark) Added string preference key for theme mode.
        val THEME_MODE = stringPreferencesKey("theme_mode")
        // App Language Storage Key (Stores the explicit app locale selection)
        // Enum names remain language-agnostic so translated labels can change without invalidating persisted preferences.
        val APP_LANGUAGE = stringPreferencesKey("app_language")
        // Dynamic Color Storage Key (Preference key to track whether dynamic Monet coloring is enabled) Adds preferences key for dynamic color option.
        val IS_DYNAMIC_COLOR_ENABLED = booleanPreferencesKey("is_dynamic_color_enabled")
        // AMOLED Dark Theme Storage Key (Tracks whether dark mode forces pure-black OLED surfaces)
        val IS_AMOLED_ENABLED = booleanPreferencesKey("is_amoled_enabled")
        val HOME_FILTER = stringPreferencesKey("home_filter")
        // Home Book Status Filter Storage Key (Tracks the Home dialog's availability filter)
        // The value stores HomeBookStatusFilter enum names while this repository stays independent from the UI enum type.
        val HOME_BOOK_STATUS_FILTER = stringPreferencesKey("home_book_status_filter")
        // Home View Style Storage Key (Tracks the user's catalog renderer preference)
        // Stores enum names instead of localized labels so preference data stays stable across language changes.
        val HOME_VIEW_STYLE = stringPreferencesKey("home_view_style")
        // Home Sort Rule Storage Key (Tracks the user's catalog grouping pivot for script-clustered sorting)
        // Stores enum names so author, narrator, and series sorting can be restored without coupling DataStore to UI text.
        val HOME_SORT_RULE = stringPreferencesKey("home_sort_rule")
        // Home Sort Direction Storage Key (Tracks the user's in-cluster ascending or descending preference)
        // The script cluster order is fixed in HomeCatalogSortPolicy, so this preference only flips comparisons inside a cluster.
        val HOME_SORT_DIRECTION = stringPreferencesKey("home_sort_direction")
        val IS_GLOBAL_SPEED_ENABLED = booleanPreferencesKey("is_global_speed_enabled")
        val GLOBAL_PLAYBACK_SPEED = floatPreferencesKey("global_playback_speed")
        val IS_CHAPTER_PROGRESS_MODE = booleanPreferencesKey("is_chapter_progress_mode")
        // Insecure TLS Storage Key: Add a Datastore preference key to store whether insecure TLS connections are permitted.
        val IS_ALLOW_INSECURE_TLS = booleanPreferencesKey("is_allow_insecure_tls")
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
        // Glassmorphic Rendering Mode (Preferences key tracking dialog blur style: Haze vs Material)
        val GLASS_EFFECT_MODE = stringPreferencesKey("glass_effect_mode")
        // Post-Interrupt Auto Rewind (Preferences key storing rewind offset in seconds; 0 disables the feature)
        val AUTO_REWIND_SECONDS = intPreferencesKey("auto_rewind_seconds")
        // Seek Backward Step Storage (Stores constrained rewind command increments in seconds)
        // Repository reads validate this integer before exposing it to playback surfaces.
        val SEEK_BACKWARD_SECONDS = intPreferencesKey("seek_backward_seconds")
        // Seek Forward Step Storage (Stores constrained fast-forward command increments in seconds)
        // Repository reads validate this integer before exposing it to playback surfaces.
        val SEEK_FORWARD_SECONDS = intPreferencesKey("seek_forward_seconds")
        // Interrupted State Tracker (Preferences key flagging whether the previous session terminated abnormally, e.g. system kill)
        val IS_LAST_PLAYBACK_INTERRUPTED = booleanPreferencesKey("is_last_playback_interrupted")
        // Audio Focus Ducking Avoidance (Preferences key tracking whether focus loss forces explicit playback pause)
        val IS_NOTIFICATION_AVOIDANCE_ENABLED = booleanPreferencesKey("is_notification_avoidance_enabled")
        // Legacy Disk Cache Size Storage (Old playback disk-cache key kept only as a migration source)
        // New writes go to PLAYBACK_BUFFER_MAX_BYTES so fresh settings snapshots no longer encode removed disk-cache terminology.
        val LEGACY_PLAYBACK_CACHE_MAX_BYTES = longPreferencesKey("playback_cache_max_bytes")
        // Playback Buffer Size Storage (Preferences key for ExoPlayer in-memory target buffer bytes)
        // This key replaces the removed playback disk-cache capacity setting and keeps the new runtime meaning explicit in persisted data.
        val PLAYBACK_BUFFER_MAX_BYTES = longPreferencesKey("playback_buffer_max_bytes")
        // Legacy Playback Buffer Duration Storage (Removed duration key kept only for cleanup migration)
        // Buffering is now size-limited only, so this stale key is removed when the repository initializes.
        val LEGACY_PLAYBACK_BUFFER_DURATION_MS = intPreferencesKey("playback_buffer_duration_ms")
        // Download WiFi Requirement Storage (Preferences key for manual download network requirements)
        // This setting can be written before DownloadManager exists and later mapped to Media3 Requirements.
        val IS_DOWNLOAD_WIFI_ONLY = booleanPreferencesKey("is_download_wifi_only")
    }

    /**
     * Live Preferences Stream (Exposes a Flow of updated AppSettings from the DataStore repository)
     */
    override val settingsFlow: Flow<AppSettings> = dataStore.data.map { preferences ->
        AppSettings(
            // Read Theme Mode (Parse active theme mode, defaulting to System if empty or invalid) Reads theme mode configuration.
            themeMode = preferences[PreferencesKeys.THEME_MODE]
                ?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() }
                ?: ThemeMode.System,
            // Read App Language (Parse locale preference with a safe system-default fallback)
            // Invalid enum names are ignored so stale preference values cannot block app startup after locale migrations.
            appLanguage = preferences[PreferencesKeys.APP_LANGUAGE]
                ?.let { runCatching { AppLanguage.valueOf(it) }.getOrNull() }
                ?: AppLanguage.System,
            // Read Dynamic Color Setting (Load persisted dynamic color option, defaulting to true) Reads dynamic color setting from DataStore.
            isDynamicColorEnabled = preferences[PreferencesKeys.IS_DYNAMIC_COLOR_ENABLED] ?: true,
            // Read AMOLED Dark Theme Setting (Load persisted pure-black dark mode option, defaulting to off)
            isAmoledEnabled = preferences[PreferencesKeys.IS_AMOLED_ENABLED] ?: false,
            // Read Home Filter (Parse persisted HomeFilter enum value)
            homeFilter = preferences[PreferencesKeys.HOME_FILTER]
                ?.let { runCatching { HomeFilter.valueOf(it) }.getOrNull() }
                ?: HomeFilter.NotStarted,
            // Read Home Book Status Filter (Parse persisted HomeBookStatusFilter enum value)
            homeBookStatusFilter = preferences[PreferencesKeys.HOME_BOOK_STATUS_FILTER]
                ?.let { runCatching { HomeBookStatusFilter.valueOf(it) }.getOrNull() }
                ?: HomeBookStatusFilter.All,
            // Read Home View Style (Parse persisted catalog renderer, defaulting to List for backward-compatible first launch behavior)
            // Invalid values are ignored so stale preference names cannot break Home screen rendering after enum changes.
            homeViewStyle = preferences[PreferencesKeys.HOME_VIEW_STYLE]
                ?.let { runCatching { HomeViewStyle.valueOf(it) }.getOrNull() }
                ?: HomeViewStyle.List,
            // Read Home Sort Rule (Parse persisted grouping rule, defaulting to Author to preserve the existing catalog organization)
            // Invalid values are ignored so older cached preferences safely fall back to the established author view.
            homeSortRule = preferences[PreferencesKeys.HOME_SORT_RULE]
                ?.let { runCatching { HomeSortRule.valueOf(it) }.getOrNull() }
                ?: HomeSortRule.Author,
            // Read Home Sort Direction (Parse persisted in-cluster direction while keeping script cluster order fixed)
            // Invalid values safely fall back to ascending so older preferences and partial writes do not destabilize Home rendering.
            homeSortDirection = preferences[PreferencesKeys.HOME_SORT_DIRECTION]
                ?.let { runCatching { HomeSortDirection.valueOf(it) }.getOrNull() }
                ?: HomeSortDirection.Ascending,
            isGlobalSpeedEnabled = preferences[PreferencesKeys.IS_GLOBAL_SPEED_ENABLED] ?: false,
            // Read Global Playback Speed (Normalize portable speed values before playback consumers observe settings)
            // Backup restore and direct preference edits can bypass UI controls, so repository reads expose only playable speed values.
            globalPlaybackSpeed = PlaybackSettingsBounds.speedOrDefault(preferences[PreferencesKeys.GLOBAL_PLAYBACK_SPEED]),
            isChapterProgressMode = preferences[PreferencesKeys.IS_CHAPTER_PROGRESS_MODE] ?: false,
            // Read Insecure TLS Option: Load insecure TLS bypass configuration from preferences, defaulting to false.
            isAllowInsecureTls = preferences[PreferencesKeys.IS_ALLOW_INSECURE_TLS] ?: false,
            // Read HTTP Policy (Resolve global cleartext permission, defaulting to false until the user explicitly opts in)
            isCleartextTrafficAllowed = preferences[PreferencesKeys.IS_CLEARTEXT_TRAFFIC_ALLOWED] ?: false,
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
            // Read Auto Rewind (Normalize portable rewind values before playback resumption observes settings)
            // Damaged backups may contain negative or oversized seconds, so repository reads expose only the supported 0-30 range.
            autoRewindSeconds = PlaybackSettingsBounds.autoRewindSecondsOrDefault(preferences[PreferencesKeys.AUTO_REWIND_SECONDS]),
            // Read Short Seek Steps (Validate persisted values before they leave the settings boundary)
            // Invalid backward values fall back to 10 seconds and invalid forward values fall back to 20 seconds, matching the product contract.
            playbackSeekStepConfig = PlaybackSeekStepConfig.fromStored(
                backwardSeconds = preferences[PreferencesKeys.SEEK_BACKWARD_SECONDS],
                forwardSeconds = preferences[PreferencesKeys.SEEK_FORWARD_SECONDS]
            ),
            // Read Session Interruption (Flag indicating abnormal application restarts, defaulting to false)
            isLastPlaybackInterrupted = preferences[PreferencesKeys.IS_LAST_PLAYBACK_INTERRUPTED] ?: false,
            // Read Ducking Avoidance (Expose focus loss avoidance status, defaulting to false for safe media defaults)
            isNotificationAvoidanceEnabled = preferences[PreferencesKeys.IS_NOTIFICATION_AVOIDANCE_ENABLED] ?: false,
            // Read Playback Buffer Size (Normalize restored values before LoadControl observes them)
            // The former playback-cache key now feeds ExoPlayer's in-memory target buffer size, so multi-GB legacy values are rejected.
            playbackBufferMaxBytes = PlaybackSettingsBounds.playbackBufferBytesOrDefault(
                preferences[PreferencesKeys.PLAYBACK_BUFFER_MAX_BYTES]
                    ?: preferences[PreferencesKeys.LEGACY_PLAYBACK_CACHE_MAX_BYTES]
            ),
            // Read Download WiFi Policy (Default manual downloads to unmetered networks)
            // The default is conservative because background downloads may be large audiobook files.
            isDownloadWifiOnly = preferences[PreferencesKeys.IS_DOWNLOAD_WIFI_ONLY] ?: true
        )
    }

    init {
        // Settings Flow Initialization Boundary (Start the cache collector only after settingsFlow has been assigned)
        // Kotlin runs property initializers and init blocks in source order, so this init block must stay below settingsFlow to prevent fast Dispatchers.IO execution from dereferencing a null flow during app startup.
        scope.launch {
            migrateLegacyPlaybackBufferPreferences()
            settingsFlow.collect {
                _cachedSettings = it
            }
        }
    }

    // Playback Buffer Preferences Migration (Move removed disk-cache capacity into the new memory-buffer key)
    // Legacy multi-gigabyte disk-cache limits are normalized through memory-safe bounds before the old key is removed.
    private suspend fun migrateLegacyPlaybackBufferPreferences() {
        dataStore.edit { preferences ->
            val legacyBytes = preferences[PreferencesKeys.LEGACY_PLAYBACK_CACHE_MAX_BYTES]
            if (preferences[PreferencesKeys.PLAYBACK_BUFFER_MAX_BYTES] == null && legacyBytes != null) {
                preferences[PreferencesKeys.PLAYBACK_BUFFER_MAX_BYTES] = PlaybackSettingsBounds.playbackBufferBytesOrDefault(legacyBytes)
            }
            if (legacyBytes != null) {
                preferences.remove(PreferencesKeys.LEGACY_PLAYBACK_CACHE_MAX_BYTES)
            }
            // Removed Playback Buffer Duration Cleanup (Drop stale time-limit preferences)
            // ExoPlayer buffering is now capped only by target bytes, so keeping the old duration key would misrepresent active settings.
            preferences.remove(PreferencesKeys.LEGACY_PLAYBACK_BUFFER_DURATION_MS)
        }
    }

    override suspend fun updateHomeFilter(filter: HomeFilter) {
        // Update Home Filter Type Safe: Use HomeFilter enum name instead of raw string to prevent status drifts.
        dataStore.edit { it[PreferencesKeys.HOME_FILTER] = filter.name }
    }

    // Write Home Book Status Filter (Persist the Home dialog availability filter)
    // The caller passes a stable enum name so the stored value remains language-independent across localized labels.
    override suspend fun updateHomeBookStatusFilter(filter: HomeBookStatusFilter) {
        // Update Home Book Status Filter Type Safe: Use HomeBookStatusFilter enum name instead of raw string to prevent status drifts.
        dataStore.edit { it[PreferencesKeys.HOME_BOOK_STATUS_FILTER] = filter.name }
    }

    // Write Home View Style (Persist the selected Home catalog renderer)
    // Saves enum names directly so the UI can switch between adaptive listgroup columns and Cardgroup carousel rows after the DataStore flow emits.
    override suspend fun updateHomeViewStyle(style: HomeViewStyle) {
        dataStore.edit { it[PreferencesKeys.HOME_VIEW_STYLE] = style.name }
    }

    // Write Home Sort Rule (Persist the selected Home catalog grouping and ordering rule)
    // Saves enum names directly so the ViewModel can rebuild grouped audiobook sections from the canonical settings stream.
    override suspend fun updateHomeSortRule(rule: HomeSortRule) {
        dataStore.edit { it[PreferencesKeys.HOME_SORT_RULE] = rule.name }
    }

    // Write Home Sort Direction (Persist the selected in-cluster ordering direction)
    // Saves only the enum name because fixed script cluster ordering remains a policy concern, not a storage concern.
    override suspend fun updateHomeSortDirection(direction: HomeSortDirection) {
        dataStore.edit { it[PreferencesKeys.HOME_SORT_DIRECTION] = direction.name }
    }

    override suspend fun updateGlobalSpeedEnabled(enabled: Boolean) {
        dataStore.edit { it[PreferencesKeys.IS_GLOBAL_SPEED_ENABLED] = enabled }
    }

    // Write Global Playback Speed (Persist only repository-normalized speed values)
    // Direct repository callers can pass values outside UI lists, so this boundary stores a playable 0.25x-2.0x speed or the neutral default.
    override suspend fun updateGlobalPlaybackSpeed(speed: Float) {
        dataStore.edit { it[PreferencesKeys.GLOBAL_PLAYBACK_SPEED] = PlaybackSettingsBounds.speedOrDefault(speed) }
    }

    override suspend fun updateChapterProgressMode(enabled: Boolean) {
        dataStore.edit { it[PreferencesKeys.IS_CHAPTER_PROGRESS_MODE] = enabled }
    }

    // Write Insecure TLS Option: Save insecure TLS bypass configuration to the local preferences store.
    override suspend fun updateAllowInsecureTls(enabled: Boolean) {
        dataStore.edit { it[PreferencesKeys.IS_ALLOW_INSECURE_TLS] = enabled }
    }

    // Write HTTP Policy (Persist user permission settings regarding cleartext HTTP connections)
    override suspend fun updateCleartextTrafficAllowed(enabled: Boolean) {
        dataStore.edit { it[PreferencesKeys.IS_CLEARTEXT_TRAFFIC_ALLOWED] = enabled }
    }

    /**
      * Write Skip Silence (Persist skip silence preferences)
      * Custom thresholds and notification triggers are managed implicitly under system defaults.
      */
    override suspend fun updateSkipSilenceEnabled(enabled: Boolean) {
        dataStore.edit { it[PreferencesKeys.IS_SKIP_SILENCE_ENABLED] = enabled }
    }

    // Write Sleep Fade-Out (Persist volume fade-out switch settings for sleep timer expirations)
    override suspend fun updateSleepFadeOutEnabled(enabled: Boolean) {
        dataStore.edit { it[PreferencesKeys.IS_SLEEP_FADE_OUT_ENABLED] = enabled }
    }

    // Write Shake-to-Reset (Persist shake reset switch settings to toggle sensor inputs on timer start)
    override suspend fun updateShakeToResetEnabled(enabled: Boolean) {
        dataStore.edit { it[PreferencesKeys.IS_SHAKE_TO_RESET_ENABLED] = enabled }
    }

    // Write Sleep Mode (Persist target SleepMode layout options into the local data store)
    override suspend fun updateSleepMode(mode: SleepMode) {
        dataStore.edit { it[PreferencesKeys.SLEEP_MODE] = mode.name }
    }

    // Write Glass Effect (Persist rendering preference for glassmorphic overlay sheets)
    override suspend fun updateGlassEffectMode(mode: GlassEffectMode) {
        dataStore.edit { it[PreferencesKeys.GLASS_EFFECT_MODE] = mode.name }
    }

    // Write Auto Rewind (Persist only repository-normalized rewind seconds)
    // Clamping at write time keeps direct callers and restored settings aligned with the 0-30 second playback resume contract.
    override suspend fun updateAutoRewindSeconds(seconds: Int) {
        dataStore.edit { it[PreferencesKeys.AUTO_REWIND_SECONDS] = PlaybackSettingsBounds.autoRewindSecondsOrDefault(seconds) }
    }

    // Write Seek Backward Step (Persist validated rewind increments only)
    // Accepting SeekStepSeconds keeps callers from writing unsupported raw integers into DataStore.
    override suspend fun updateSeekBackwardSeconds(step: SeekStepSeconds) {
        dataStore.edit { it[PreferencesKeys.SEEK_BACKWARD_SECONDS] = step.seconds }
    }

    // Write Seek Forward Step (Persist validated fast-forward increments only)
    // Accepting SeekStepSeconds keeps notification, widget, and player controls aligned on the same allowed values.
    override suspend fun updateSeekForwardSeconds(step: SeekStepSeconds) {
        dataStore.edit { it[PreferencesKeys.SEEK_FORWARD_SECONDS] = step.seconds }
    }

    // Write Interruption Flag (Flag whether the current playback loop ended normally or via process terminations)
    override suspend fun updateLastPlaybackInterrupted(interrupted: Boolean) {
        dataStore.edit { it[PreferencesKeys.IS_LAST_PLAYBACK_INTERRUPTED] = interrupted }
    }

    // Write Ducking Avoidance (Persist notification avoidance configurations for audio focus loss)
    override suspend fun updateNotificationAvoidanceEnabled(enabled: Boolean) {
        dataStore.edit { it[PreferencesKeys.IS_NOTIFICATION_AVOIDANCE_ENABLED] = enabled }
    }

    // Write Playback Buffer Size (Persist the normalized memory-buffer target through the new buffer-size key)
    // The write path clamps unsupported raw values so settings UI and direct callers cannot request storage-scale RAM buffers.
    override suspend fun updatePlaybackBufferMaxBytes(bytes: Long) {
        dataStore.edit { it[PreferencesKeys.PLAYBACK_BUFFER_MAX_BYTES] = PlaybackSettingsBounds.playbackBufferBytesOrDefault(bytes) }
    }

    // Write Download WiFi Requirement (Persist manual download network policy without forcing runtime creation)
    // DownloadRuntimeGateway will map this setting to Media3 Requirements only after a download runtime already exists.
    override suspend fun updateDownloadWifiOnly(enabled: Boolean) {
        dataStore.edit { it[PreferencesKeys.IS_DOWNLOAD_WIFI_ONLY] = enabled }
    }

    // Write Theme Mode (Persist user selected theme mode into local DataStore) Helper function to save theme mode configuration.
    override suspend fun updateThemeMode(mode: ThemeMode) {
        dataStore.edit { it[PreferencesKeys.THEME_MODE] = mode.name }
    }

    // Write App Language (Persist explicit app locale selection)
    // The caller applies platform locale APIs separately, keeping storage mutation and Android framework side effects decoupled.
    override suspend fun updateAppLanguage(language: AppLanguage) {
        dataStore.edit { it[PreferencesKeys.APP_LANGUAGE] = language.name }
    }

    // Write Dynamic Color Setting (Persist dynamic color option to DataStore) Saves dynamic color preference changes.
    override suspend fun updateDynamicColorEnabled(enabled: Boolean) {
        dataStore.edit { it[PreferencesKeys.IS_DYNAMIC_COLOR_ENABLED] = enabled }
    }

    // Write AMOLED Dark Theme Setting (Persist the pure-black dark mode option to DataStore)
    override suspend fun updateAmoledEnabled(enabled: Boolean) {
        dataStore.edit { it[PreferencesKeys.IS_AMOLED_ENABLED] = enabled }
    }

    companion object {
        @Volatile
        private var INSTANCE: AppSettingsRepository? = null

        fun getInstance(context: Context): AppSettingsRepository {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: AppSettingsRepository(context.applicationContext.dataStore).also { INSTANCE = it }
            }
        }

        // Test-Only Factory (Builds a repository over an isolated DataStore, bypassing the process singleton)
        // Unit tests seed raw preferences per case, so they construct instances directly instead of sharing getInstance() cached state.
        @androidx.annotation.VisibleForTesting
        fun createForTesting(dataStore: DataStore<Preferences>): AppSettingsRepository =
            AppSettingsRepository(dataStore)
    }
}
