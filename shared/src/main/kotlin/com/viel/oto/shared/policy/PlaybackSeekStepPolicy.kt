package com.viel.oto.shared.policy

import com.viel.oto.shared.model.PlaybackSeekStepConfig

/**
 * Computes seek targets from persisted user-configurable playback step values.
 *
 * Keeping the arithmetic in shared policy lets UI, widget, and playback callers resolve the same
 * clamped target without each layer re-encoding the backward/forward bounds.
 */
object PlaybackSeekStepPolicy {
    fun backwardTarget(currentPositionMs: Long, config: PlaybackSeekStepConfig): Long =
        (currentPositionMs - config.backward.toMillis()).coerceAtLeast(0L)

    fun forwardTarget(currentPositionMs: Long, durationMs: Long, config: PlaybackSeekStepConfig): Long =
        (currentPositionMs + config.forward.toMillis()).coerceAtMost(durationMs.coerceAtLeast(0L))
}
