package com.viel.aplayer.data.store

import com.viel.aplayer.data.db.AudiobookSchema

// Glass Effect Visual Mode (UI decoration options)
// Material represents standard native containers, while Haze enables haze-based Compose native frosted glass visuals.
enum class GlassEffectMode {
    Material,
    Haze
}

// Sleep Inactivity Modes (Timer termination policies)
// Regular: Counts down directly after a predefined duration.
// MotionTracking: Pauses countdown when movement is detected, resuming only when the device is still.
// SleepTracking: Requests activity recognition and starts counting down only when user sleep states are detected.
enum class SleepMode {
    Regular,
    MotionTracking,
    SleepTracking
}

// Theme Mode Selection (Support theme mode preference settings) Added ThemeMode enum to support System, Light, and Dark mode selections.
enum class ThemeMode {
    System,
    Light,
    Dark
}

// App Language Selection (Persist app-level locale choices independently from translated labels)
// Locale tags use BCP 47 values so DataStore, Android LocaleManager, and resource qualifiers can share one stable representation.
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
        // Locale Tag Mapping (Normalize platform and stored tags into supported app-language values)
        // Android can return regional aliases such as zh-CN, so this mapper folds equivalent tags back to the canonical enum.
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

// Home View Style Preference (Controls the catalog item renderer on the Home screen)
// List keeps adaptive listgroup columns as the default reading model, while Grid switches the catalog to single-line Cardgroup carousels.
enum class HomeViewStyle {
    List,
    Grid
}

// Home Sort Rule Preference (Controls the primary grouping pivot for script-clustered Home ordering)
// Author remains the default catalog organization, while Narrator and Series let users pivot the same library without changing the underlying book domain model.
enum class HomeSortRule {
    Author,
    Narrator,
    Series
}

// Home Sort Direction Preference (Controls in-cluster ordering while preserving the fixed script cluster sequence)
// Cluster order stays Chinese, Japanese, Korean, English, then Other; this enum only flips comparisons inside each cluster.
enum class HomeSortDirection {
    Ascending,
    Descending
}

// HomeFilter Enum: Home Library Filter Options.
enum class HomeFilter {
    /** Reading in progress (playback progress > 0 and unfinished) */
    InProgress,
    /** Not started */
    NotStarted,
    /** Finished reading */
    Finished
}

// Home Book Status Filter: User-facing availability filter for the Home catalog.
enum class HomeBookStatusFilter(private val schemaStatus: AudiobookSchema.BookStatus?) {
    // All Statuses Filter (Default option that preserves the full visible Home catalog)
    All(schemaStatus = null),

    // Ready Status Filter (Shows fully available books)
    Ready(schemaStatus = AudiobookSchema.BookStatus.READY),

    // Partial Status Filter (Shows books with at least one unavailable file)
    Partial(schemaStatus = AudiobookSchema.BookStatus.PARTIAL),

    // Unavailable Status Filter (Shows books whose playable files are currently unavailable)
    Unavailable(schemaStatus = AudiobookSchema.BookStatus.UNAVAILABLE);

    // Match Status Type Safe: Accept BookStatus enum parameter for type safety.
    fun matches(bookStatus: AudiobookSchema.BookStatus): Boolean {
        return schemaStatus == null || bookStatus == schemaStatus
    }

    companion object {
        // Stored Preference Parsing (Resolve persisted enum names with a safe All fallback)
        fun fromStoredName(value: String): HomeBookStatusFilter =
            runCatching { valueOf(value) }.getOrDefault(All)
    }
}

// Seek Step Value (Constrains short transport jumps to supported audiobook increments)
// Keeping validation in this value type prevents UI, notification, and widget surfaces from interpreting arbitrary stored integers differently.
enum class SeekStepSeconds(val seconds: Int) {
    Ten(10),
    Twenty(20),
    Thirty(30);

    fun toMillis(): Long = seconds * 1000L

    companion object {
        val supported: List<SeekStepSeconds> = entries

        // Stored Seek Step Validation (Maps DataStore integers into stable seek-step values)
        // Invalid or stale preferences return the caller-provided direction default so repository reads stay crash-free.
        fun fromSecondsOrDefault(seconds: Int?, defaultValue: SeekStepSeconds): SeekStepSeconds =
            supported.firstOrNull { it.seconds == seconds } ?: defaultValue
    }
}

// Playback Seek Step Configuration (Groups backward and forward short-seek choices)
// Consumers receive one validated pair instead of duplicating fallback rules for each direction.
data class PlaybackSeekStepConfig(
    val backward: SeekStepSeconds = SeekStepSeconds.Ten,
    val forward: SeekStepSeconds = SeekStepSeconds.Twenty
) {
    companion object {
        // Stored Seek Step Pair Parsing (Builds a validated playback seek config from persisted integers)
        // Direction-specific defaults live here so repository reads can expose one already-safe configuration object.
        fun fromStored(backwardSeconds: Int?, forwardSeconds: Int?): PlaybackSeekStepConfig =
            PlaybackSeekStepConfig(
                backward = SeekStepSeconds.fromSecondsOrDefault(backwardSeconds, SeekStepSeconds.Ten),
                forward = SeekStepSeconds.fromSecondsOrDefault(forwardSeconds, SeekStepSeconds.Twenty)
            )
    }
}

data class AppSettings(
    // Theme Mode Setting (Expose themeMode configurations, defaulting to System) Binds active theme configuration.
    val themeMode: ThemeMode = ThemeMode.System,
    // App Language Setting (Stores the preferred application locale)
    // System keeps Android in charge, while explicit values feed both the platform LocaleManager and the Compose fallback context.
    val appLanguage: AppLanguage = AppLanguage.System,
    // Dynamic Color Option (Enable wallpaper-based color theme extraction) Adds isDynamicColorEnabled field to AppSettings with a default value of true to support Monet dynamic coloring.
    val isDynamicColorEnabled: Boolean = true,
    /** Filter state on the home screen */
    // Home Filter Type Safe: Use HomeFilter enum instead of String for type safety.
    val homeFilter: HomeFilter = HomeFilter.NotStarted,
    // Home Book Status Filter Setting (Persist the Home dialog availability filter)
    // Defaults to All so the new BookStatus filter does not hide any existing catalog entries until the user chooses a narrower status.
    // Home Book Status Filter Type Safe: Use HomeBookStatusFilter enum instead of String for type safety.
    val homeBookStatusFilter: HomeBookStatusFilter = HomeBookStatusFilter.All,
    // Home View Style Setting (Persist the selected Home catalog renderer)
    // Defaults to List so existing users keep the current listgroup-based home layout until they explicitly switch to Cardgroup rows.
    val homeViewStyle: HomeViewStyle = HomeViewStyle.List,
    // Home Sort Rule Setting (Persist the selected Home catalog grouping and order)
    // Defaults to Author to preserve the current author-centered browsing model while allowing narrator and series pivots.
    val homeSortRule: HomeSortRule = HomeSortRule.Author,
    // Home Sort Direction Setting (Persist ascending or descending order inside each script cluster)
    // Defaults to Ascending to keep the current C/J/K/E/Other policy behavior for existing users until they choose otherwise.
    val homeSortDirection: HomeSortDirection = HomeSortDirection.Ascending,
    /** Determines if the playback speed configuration is stored globally */
    val isGlobalSpeedEnabled: Boolean = false,
    /** Global speed scale value */
    val globalPlaybackSpeed: Float = 1.0f,
    /** If true, the progress bar bounds represent individual chapter durations rather than total length */
    val isChapterProgressMode: Boolean = false,
    // Insecure TLS Config: Add a configuration flag to allow bypassing SSL certificate checks for self-signed remote servers.
    val isAllowInsecureTls: Boolean = false,
    // Cleartext Network Permission (Explicit user opt-in for unencrypted remote endpoints)
    // Blocks HTTP by default so WebDAV, ABS, and playback requests only use cleartext after the user enables the global compatibility switch.
    val isCleartextTrafficAllowed: Boolean = false,
    /**
     * Skip Silence Controller (Playback audio filter switch)
     * Automatically skips silent periods in audio streams; utilizes standard Media3 behaviours.
     */
    val isSkipSilenceEnabled: Boolean = false,
    // Volume Fade-Out Switch (Smooth pause animation)
    // Gradually fades out audio volume before pausing to avoid abrupt sound terminations.
    val isSleepFadeOutEnabled: Boolean = true,
    // Shake Reset Switch (No-look physical interaction helper)
    // Allows shaking the device to reset the active sleep timer without turning on the screen.
    val isShakeToResetEnabled: Boolean = true,
    // Active Sleep Routine (Countdown strategy selection)
    // Configures countdown strategies (Regular, MotionTracking, or SleepTracking).
    val sleepMode: SleepMode = SleepMode.Regular,
    // Glass Morphism Config (Visual schema definition)
    // Persistent visual scheme type for dialogs and control panels.
    val glassEffectMode: GlassEffectMode = DEFAULT_GLASS_EFFECT_MODE,
    // Auto-Rewind Seconds (Playback offset backing size)
    // Configuration in seconds to rewind the playback offset upon resuming; 0 means disabled.
    val autoRewindSeconds: Int = 0,
    // Short Seek Step Config (Controls transport rewind and fast-forward command increments)
    // Defaults keep rewind at 10 seconds while moving fast-forward to the product default of 20 seconds.
    val playbackSeekStepConfig: PlaybackSeekStepConfig = PlaybackSeekStepConfig(),
    // Playback Interruption Flag (Crash recovery marker)
    // Sets to true during active playback, and resets to false upon explicit pauses. Used for crash resumption.
    val isLastPlaybackInterrupted: Boolean = false,
    // Notification Focus Ducking (System interruption bypass)
    // When enabled, automatically pauses playback on audio focus loss and resumes without triggering auto-rewinds.
    val isNotificationAvoidanceEnabled: Boolean = false,
    // Playback Buffer Size (Stores the memory buffer target after migrating the removed disk-cache key)
    // DataStore migrates the former disk-cache key into a dedicated buffer key so the domain model no longer exposes playback disk-storage settings.
    val playbackBufferMaxBytes: Long = DEFAULT_PLAYBACK_BUFFER_MAX_BYTES,
    // Download Network Policy (Stores whether manual downloads require unmetered connectivity)
    // Download runtime creation stays lazy; changing this value must not construct DownloadManager when no download work exists.
    val isDownloadWifiOnly: Boolean = true
) {
    companion object {
        // Shared Accent Default (Decoration fallback values)
        // Unified fallback visuals definition to avoid splitting UI constants across Compose widgets.
        val DEFAULT_GLASS_EFFECT_MODE: GlassEffectMode = GlassEffectMode.Material

        // Default Playback Buffer Size (Keep the RAM target separate from removed disk-storage defaults)
        // The value is intentionally much smaller than the removed disk cache default to avoid treating RAM as storage.
        const val DEFAULT_PLAYBACK_BUFFER_MAX_BYTES: Long = 64L * 1024L * 1024L
    }
}
