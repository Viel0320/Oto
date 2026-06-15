package com.viel.aplayer.ui.player


/**
 * Playback state model (Container for active player engine states)
 * Caches high-frequency variables such as position, duration, and speed attributes.
 */
data class PlaybackState(
    /** Active playing state (To indicate if the media player is currently active) */
    val isPlaying: Boolean = false,
    /** Autoplay flag (To indicate play when ready parameters) */
    val playWhenReady: Boolean = false,
    /** Current position coordinate (To track played millisecond offset) */
    val currentPosition: Long = 0L,
    /** Buffered position coordinate (To track ExoPlayer memory-buffered millisecond offset) */
    val bufferedPosition: Long = 0L,
    /** Total audio duration (To represent full track duration in milliseconds) */
    val duration: Long = 0L,
    /** Playback speed multiplier (To represent active playback speed value) */
    val playbackSpeed: Float = 1.0f,
    /** Speed override flag (To indicate if the playback speed is in manual override mode) */
    val isSpeedManualMode: Boolean = false
) {
    /** Percentage fraction getter (To calculate percentage progress float between 0.0 and 1.0) */
    val progress: Float
        get() = if (duration > 0) currentPosition.toFloat() / duration.toFloat() else 0f
}
