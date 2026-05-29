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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import com.viel.aplayer.data.store.AppSettings
import com.viel.aplayer.data.store.GlassEffectMode
import com.viel.aplayer.data.store.SleepMode

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "app_settings")

/**
 * 负责应用设置的持久化管理，基于 Jetpack DataStore。
 */
class AppSettingsRepository private constructor(private val dataStore: DataStore<Preferences>) {

    private object PreferencesKeys {
        val HOME_FILTER = stringPreferencesKey("home_filter")
        val IS_GLOBAL_SPEED_ENABLED = booleanPreferencesKey("is_global_speed_enabled")
        val GLOBAL_PLAYBACK_SPEED = floatPreferencesKey("global_playback_speed")
        val IS_CHAPTER_PROGRESS_MODE = booleanPreferencesKey("is_chapter_progress_mode")
        // 新增 PreferenceKey 存储键名，用于指示是否开启明文 http 连接授权状态。
        val IS_CLEARTEXT_TRAFFIC_ALLOWED = booleanPreferencesKey("is_cleartext_traffic_allowed")
        /**
         * 自动跳过静音开关持久化存储 Key。已经过重构去除了自定义判定时长和通知开关相关的 Key。
         */
        val IS_SKIP_SILENCE_ENABLED = booleanPreferencesKey("is_skip_silence_enabled")
        // 新增睡眠定时器音量渐隐机制的持久化存储 Key
        val IS_SLEEP_FADE_OUT_ENABLED = booleanPreferencesKey("is_sleep_fade_out_enabled")
        // 新增摇晃手机重置睡眠定时器机制的持久化存储 Key
        val IS_SHAKE_TO_RESET_ENABLED = booleanPreferencesKey("is_shake_to_reset_enabled")
        // 新增睡眠模式持久化存储 Key
        val SLEEP_MODE = stringPreferencesKey("sleep_mode")
        // 新增悬浮层玻璃效果模式持久化存储 Key，字符串值直接保存 GlassEffectMode.name 方便兼容未来扩展。
        val GLASS_EFFECT_MODE = stringPreferencesKey("glass_effect_mode")
        // 新增自动回退播放进度秒数（0-30s）持久化存储 Key，0 表示不开启自动回退。
        val AUTO_REWIND_SECONDS = intPreferencesKey("auto_rewind_seconds")
        // 新增上次播放是否为非正常中断（如系统强杀）持久化存储 Key。
        val IS_LAST_PLAYBACK_INTERRUPTED = booleanPreferencesKey("is_last_playback_interrupted")
        // 新增通知避让机制是否启用的持久化存储 Key，开启时失去焦点将执行暂停并由自主逻辑在重获焦点后恢复。
        val IS_NOTIFICATION_AVOIDANCE_ENABLED = booleanPreferencesKey("is_notification_avoidance_enabled")
    }

    /**
     * 获取实时设置流。
     */
    val settingsFlow: Flow<AppSettings> = dataStore.data.map { preferences ->
        AppSettings(
            homeFilter = preferences[PreferencesKeys.HOME_FILTER] ?: "NotStarted",
            isGlobalSpeedEnabled = preferences[PreferencesKeys.IS_GLOBAL_SPEED_ENABLED] ?: false,
            globalPlaybackSpeed = preferences[PreferencesKeys.GLOBAL_PLAYBACK_SPEED] ?: 1.0f,
            isChapterProgressMode = preferences[PreferencesKeys.IS_CHAPTER_PROGRESS_MODE] ?: false,
            // 从 DataStore 缓存中提取明文 http 流量授权状态，缺失则以 true 默认授权状态加载，以提供更友好的初始 WebDAV 配置体验。
            isCleartextTrafficAllowed = preferences[PreferencesKeys.IS_CLEARTEXT_TRAFFIC_ALLOWED] ?: true,
            /**
             * 从 DataStore 物理读取自动跳过静音的开关状态，默认值为 false。
             * 经过重构，移除了自定义时长和提示通知字段的读取，遵循官方默认配置。
             */
            isSkipSilenceEnabled = preferences[PreferencesKeys.IS_SKIP_SILENCE_ENABLED] ?: false,
            // 从 DataStore 物理读取睡眠定时音量渐隐的开关状态，默认值为 true
            isSleepFadeOutEnabled = preferences[PreferencesKeys.IS_SLEEP_FADE_OUT_ENABLED] ?: true,
            // 从 DataStore 读取摇晃重置睡眠定时器的开关状态，默认值为 true
            isShakeToResetEnabled = preferences[PreferencesKeys.IS_SHAKE_TO_RESET_ENABLED] ?: true,
            // 从 DataStore 读取睡眠模式，缺失或非法历史值统一回落到常规模式（Regular）。
            sleepMode = preferences[PreferencesKeys.SLEEP_MODE]
                ?.let { runCatching { SleepMode.valueOf(it) }.getOrNull() }
                ?: SleepMode.Regular,
            // 从 DataStore 读取玻璃效果模式，缺失或非法历史值统一回落到 AppSettings 声明 of 设置默认值。
            glassEffectMode = preferences[PreferencesKeys.GLASS_EFFECT_MODE]
                ?.let { runCatching { GlassEffectMode.valueOf(it) }.getOrNull() }
                ?: AppSettings.DEFAULT_GLASS_EFFECT_MODE,
            // 从 DataStore 中读取自动回退秒数，默认为 0 秒（已关闭）。
            autoRewindSeconds = preferences[PreferencesKeys.AUTO_REWIND_SECONDS] ?: 0,
            // 从 DataStore 中读取上次播放是否为异常非正常中断的标志，默认为 false。
            isLastPlaybackInterrupted = preferences[PreferencesKeys.IS_LAST_PLAYBACK_INTERRUPTED] ?: false,
            // 从 DataStore 中读取通知避让选项开关的最新状态，如果配置不存在则以极高安全防护的默认状态（false，即不开启）来加载。
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

    // 提供外部调用修改明文流量持久化设置的接口函数。
    suspend fun updateCleartextTrafficAllowed(enabled: Boolean) {
        dataStore.edit { it[PreferencesKeys.IS_CLEARTEXT_TRAFFIC_ALLOWED] = enabled }
    }

    /**
     * 提供修改自动跳过静音开关持久化配置的接口函数。
     * 重构后已彻底移除自定义判定时长和通知提示开关持久化更新函数。
     */
    suspend fun updateSkipSilenceEnabled(enabled: Boolean) {
        dataStore.edit { it[PreferencesKeys.IS_SKIP_SILENCE_ENABLED] = enabled }
    }

    // 提供修改睡眠定时器音量渐隐配置的接口函数
    suspend fun updateSleepFadeOutEnabled(enabled: Boolean) {
        dataStore.edit { it[PreferencesKeys.IS_SLEEP_FADE_OUT_ENABLED] = enabled }
    }

    // 提供修改摇晃重置睡眠定时器配置的接口函数，由 SettingsViewModel 调用实现持久化写操作。
    suspend fun updateShakeToResetEnabled(enabled: Boolean) {
        dataStore.edit { it[PreferencesKeys.IS_SHAKE_TO_RESET_ENABLED] = enabled }
    }

    // 提供修改睡眠模式的持久化接口，由 SettingsViewModel 调用并实现持久化写操作。
    suspend fun updateSleepMode(mode: SleepMode) {
        dataStore.edit { it[PreferencesKeys.SLEEP_MODE] = mode.name }
    }

    // 提供修改悬浮层玻璃效果模式的持久化接口，由设置页切换 Material/miuix-blur 时调用。
    suspend fun updateGlassEffectMode(mode: GlassEffectMode) {
        dataStore.edit { it[PreferencesKeys.GLASS_EFFECT_MODE] = mode.name }
    }

    // 提供修改自动回退播放进度秒数（0-30s）持久化配置的接口函数，由 ViewModel 调用并写入 DataStore。
    suspend fun updateAutoRewindSeconds(seconds: Int) {
        dataStore.edit { it[PreferencesKeys.AUTO_REWIND_SECONDS] = seconds }
    }

    // 提供修改上次播放是否为非正常中断持久化配置的接口函数，用来在播放器开始/暂停以及冷启动自愈时写入。
    suspend fun updateLastPlaybackInterrupted(interrupted: Boolean) {
        dataStore.edit { it[PreferencesKeys.IS_LAST_PLAYBACK_INTERRUPTED] = interrupted }
    }

    // 提供修改是否启用通知避让机制持久化配置的接口函数，由 SettingsViewModel 异步调用并落盘写入 DataStore。
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
