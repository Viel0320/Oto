package com.viel.aplayer.data.store


/**
 * Application Global Settings (Persistent configuration model DTO)
 */
// Glass Effect Visual Mode (UI decoration options)
// Material represents standard native containers, while MiuixBlur enables miuix-blur hardware frosted glass visuals.
enum class GlassEffectMode {
    Material,
    MiuixBlur
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

data class AppSettings(
    /** Filter state on the home screen */
    val homeFilter: String = "NotStarted",
    /** Determines if the playback speed configuration is stored globally */
    val isGlobalSpeedEnabled: Boolean = false,
    /** Global speed scale value */
    val globalPlaybackSpeed: Float = 1.0f,
    /** If true, the progress bar bounds represent individual chapter durations rather than total length */
    val isChapterProgressMode: Boolean = false,
    // Cleartext Network Permission (Insecure server compatibility flag)
    // Permits unencrypted HTTP network connections; enabled by default for easier initial WebDAV setups.
    val isCleartextTrafficAllowed: Boolean = true,
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
