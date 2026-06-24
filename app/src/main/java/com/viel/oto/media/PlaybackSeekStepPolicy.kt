package com.viel.oto.media

import com.viel.oto.shared.settings.PlaybackSeekStepConfig

object PlaybackSeekStepPolicy {
    fun backwardTarget(currentPositionMs: Long, config: PlaybackSeekStepConfig): Long =
        (currentPositionMs - config.backward.toMillis()).coerceAtLeast(0L)

    fun forwardTarget(currentPositionMs: Long, durationMs: Long, config: PlaybackSeekStepConfig): Long =
        (currentPositionMs + config.forward.toMillis()).coerceAtMost(durationMs.coerceAtLeast(0L))
}
