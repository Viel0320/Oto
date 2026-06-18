package com.viel.aplayer.media

import com.viel.aplayer.shared.settings.PlaybackSeekStepConfig

// Playback Seek Step Policy (Calculates clamped short-seek targets for player controls)
// The full player delegates its rewind and fast-forward math here so boundary handling is testable without constructing MediaController or ViewModel state.
object PlaybackSeekStepPolicy {
    fun backwardTarget(currentPositionMs: Long, config: PlaybackSeekStepConfig): Long =
        (currentPositionMs - config.backward.toMillis()).coerceAtLeast(0L)

    fun forwardTarget(currentPositionMs: Long, durationMs: Long, config: PlaybackSeekStepConfig): Long =
        (currentPositionMs + config.forward.toMillis()).coerceAtMost(durationMs.coerceAtLeast(0L))
}
