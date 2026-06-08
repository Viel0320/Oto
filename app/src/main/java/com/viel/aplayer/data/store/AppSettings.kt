package com.viel.aplayer.data.store


/**
 * Application Global Settings (Persistent configuration model DTO)
 */
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

// Home View Style Preference (Controls the catalog item renderer on the Home screen)
// List keeps adaptive listgroup columns as the default reading model, while Grid switches the catalog to single-line cardgroup carousels.
enum class HomeViewStyle {
    List,
    Grid
}

// Home Sort Rule Preference (Controls the primary grouping and descending pinyin order on the Home screen)
// Author remains the default catalog organization, while Narrator and Series let users pivot the same library without changing the underlying book domain model.
enum class HomeSortRule {
    Author,
    Narrator,
    Series
}

data class AppSettings(
    // Theme Mode Setting (Expose themeMode configurations, defaulting to System) Binds active theme configuration.
    val themeMode: ThemeMode = ThemeMode.System,
    // Dynamic Color Option (Enable wallpaper-based color theme extraction) Adds isDynamicColorEnabled field to AppSettings with a default value of true to support Monet dynamic coloring.
    val isDynamicColorEnabled: Boolean = true,
    /** Filter state on the home screen */
    val homeFilter: String = "NotStarted",
    // Home View Style Setting (Persist the selected Home catalog renderer)
    // Defaults to List so existing users keep the current listgroup-based home layout until they explicitly switch to cardgroup rows.
    val homeViewStyle: HomeViewStyle = HomeViewStyle.List,
    // Home Sort Rule Setting (Persist the selected Home catalog grouping and order)
    // Defaults to Author to preserve the current author-centered browsing model while allowing narrator and series pivots.
    val homeSortRule: HomeSortRule = HomeSortRule.Author,
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
    // Playback Interruption Flag (Crash recovery marker)
    // Sets to true during active playback, and resets to false upon explicit pauses. Used for crash resumption.
    val isLastPlaybackInterrupted: Boolean = false,
    // Notification Focus Ducking (System interruption bypass)
    // When enabled, automatically pauses playback on audio focus loss and resumes without triggering auto-rewinds.
    val isNotificationAvoidanceEnabled: Boolean = false
) {
    companion object {
        // Shared Accent Default (Decoration fallback values)
        // Unified fallback visuals definition to avoid splitting UI constants across Compose widgets.
        val DEFAULT_GLASS_EFFECT_MODE: GlassEffectMode = GlassEffectMode.Material
    }
}
