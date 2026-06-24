package com.viel.oto.shared.settings

enum class GlassEffectMode {
    Material,
    Haze
}

enum class SleepMode {
    Regular,
    MotionTracking,
    SleepTracking
}

enum class ThemeMode {
    System,
    Light,
    Dark
}

enum class AppLanguage(val localeTag: String) {
    System(""),
    English("en"),
    ChineseSimplified("zh-Hans-CN"),
    ChineseHongKong("zh-Hant-HK"),
    ChineseTaiwan("zh-Hant-TW"),
    Japanese("ja"),
    French("fr"),
    German("de"),
    Russian("ru"),
    Spanish("es"),
    Portuguese("pt");

    companion object {
        fun fromLocaleTag(tag: String?): AppLanguage? {
            val normalizedTag = tag
                ?.trim()
                ?.replace('_', '-')
                ?.takeIf { it.isNotEmpty() }
                ?: return System
            return when {
                normalizedTag.equals("en", ignoreCase = true) ||
                    normalizedTag.startsWith("en-", ignoreCase = true) -> English
                normalizedTag.equals("zh", ignoreCase = true) ||
                    normalizedTag.equals("zh-CN", ignoreCase = true) ||
                    normalizedTag.equals("zh-Hans", ignoreCase = true) ||
                    normalizedTag.startsWith("zh-Hans-", ignoreCase = true) -> ChineseSimplified
                normalizedTag.equals("zh-HK", ignoreCase = true) ||
                    normalizedTag.equals("zh-Hant-HK", ignoreCase = true) -> ChineseHongKong
                normalizedTag.equals("zh-TW", ignoreCase = true) ||
                    normalizedTag.equals("zh-Hant-TW", ignoreCase = true) -> ChineseTaiwan
                normalizedTag.equals("ja", ignoreCase = true) ||
                    normalizedTag.startsWith("ja-", ignoreCase = true) -> Japanese
                normalizedTag.equals("fr", ignoreCase = true) ||
                    normalizedTag.startsWith("fr-", ignoreCase = true) -> French
                normalizedTag.equals("de", ignoreCase = true) ||
                    normalizedTag.startsWith("de-", ignoreCase = true) -> German
                normalizedTag.equals("ru", ignoreCase = true) ||
                    normalizedTag.startsWith("ru-", ignoreCase = true) -> Russian
                normalizedTag.equals("es", ignoreCase = true) ||
                    normalizedTag.startsWith("es-", ignoreCase = true) -> Spanish
                normalizedTag.equals("pt", ignoreCase = true) ||
                    normalizedTag.startsWith("pt-", ignoreCase = true) -> Portuguese
                else -> null
            }
        }
    }
}

enum class HomeViewStyle {
    List,
    Grid
}

enum class HomeSortRule {
    Author,
    Narrator,
    Series
}

enum class HomeSortDirection {
    Ascending,
    Descending
}

enum class HomeFilter {
    /** playback progress > 0 and unfinished. */
    InProgress,
    /** Not started */
    NotStarted,
    /** Finished reading */
    Finished
}

enum class HomeBookStatusFilter {
    All,

    Ready,

    Partial,

    Unavailable;

    companion object {
        fun fromStoredName(value: String): HomeBookStatusFilter =
            runCatching { valueOf(value) }.getOrDefault(All)
    }
}

enum class SeekStepSeconds(val seconds: Int) {
    Ten(10),
    Twenty(20),
    Thirty(30);

    fun toMillis(): Long = seconds * 1000L

    companion object {
        val supported: List<SeekStepSeconds> = entries

        fun fromSecondsOrDefault(seconds: Int?, defaultValue: SeekStepSeconds): SeekStepSeconds =
            supported.firstOrNull { it.seconds == seconds } ?: defaultValue
    }
}

data class PlaybackSeekStepConfig(
    val backward: SeekStepSeconds = SeekStepSeconds.Ten,
    val forward: SeekStepSeconds = SeekStepSeconds.Twenty
) {
    companion object {
        fun fromStored(backwardSeconds: Int?, forwardSeconds: Int?): PlaybackSeekStepConfig =
            PlaybackSeekStepConfig(
                backward = SeekStepSeconds.fromSecondsOrDefault(backwardSeconds, SeekStepSeconds.Ten),
                forward = SeekStepSeconds.fromSecondsOrDefault(forwardSeconds, SeekStepSeconds.Twenty)
            )
    }
}
data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.System,
    val appLanguage: AppLanguage = AppLanguage.System,
    val isDynamicColorEnabled: Boolean = true,
    val isAmoledEnabled: Boolean = false,
    /** Filter state on the home screen */
    val homeFilter: HomeFilter = HomeFilter.NotStarted,
    val homeBookStatusFilter: HomeBookStatusFilter = HomeBookStatusFilter.All,
    val homeViewStyle: HomeViewStyle = HomeViewStyle.List,
    val homeSortRule: HomeSortRule = HomeSortRule.Author,
    val homeSortDirection: HomeSortDirection = HomeSortDirection.Ascending,
    /** Determines if the playback speed configuration is stored globally */
    val isGlobalSpeedEnabled: Boolean = false,
    /** Global speed scale value */
    val globalPlaybackSpeed: Float = 1.0f,
    /** If true, the progress bar bounds represent individual chapter durations rather than total length */
    val isChapterProgressMode: Boolean = false,
    val isAllowInsecureTls: Boolean = false,
    val isCleartextTrafficAllowed: Boolean = false,
    /**
     * Playback audio filter switch.
     * Automatically skips silent periods in audio streams; utilizes standard Media3 behaviours.
     */
    val isSkipSilenceEnabled: Boolean = false,
    val isSleepFadeOutEnabled: Boolean = true,
    val isShakeToResetEnabled: Boolean = true,
    val sleepMode: SleepMode = SleepMode.Regular,
    val glassEffectMode: GlassEffectMode = DEFAULT_GLASS_EFFECT_MODE,
    val autoRewindSeconds: Int = 0,
    val playbackSeekStepConfig: PlaybackSeekStepConfig = PlaybackSeekStepConfig(),
    /**
     * Global subtitle timing offset applied by the player subtitle cue matcher.
     *
     * Positive values advance visible subtitles relative to audio, while negative values delay them.
     * The setting belongs to app-wide playback configuration rather than a single loaded book so
     * listeners can keep their preferred subtitle alignment across sessions and process restarts.
     */
    val subtitleSyncOffsetMs: Long = 0L,
    val isLastPlaybackInterrupted: Boolean = false,
    val isNotificationAvoidanceEnabled: Boolean = false,
    val playbackBufferMaxBytes: Long = DEFAULT_PLAYBACK_BUFFER_MAX_BYTES,
    val isDownloadWifiOnly: Boolean = true
) {
    companion object {
        val DEFAULT_GLASS_EFFECT_MODE: GlassEffectMode = GlassEffectMode.Material

        const val DEFAULT_PLAYBACK_BUFFER_MAX_BYTES: Long = 64L * 1024L * 1024L
    }
}
