package com.viel.oto.media

import com.viel.oto.shared.settings.PlaybackSeekStepConfig
import com.viel.oto.shared.settings.SeekStepSeconds
import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackSeekStepPolicyTest {
    @Test
    fun `invalid stored seek steps fall back to direction defaults`() {
        val config = PlaybackSeekStepConfig.fromStored(
            backwardSeconds = 15,
            forwardSeconds = 15
        )

        assertEquals(SeekStepSeconds.Ten, config.backward)
        assertEquals(SeekStepSeconds.Twenty, config.forward)
    }

    @Test
    fun `valid stored seek steps are preserved`() {
        assertEquals(SeekStepSeconds.Ten, SeekStepSeconds.fromSecondsOrDefault(10, SeekStepSeconds.Thirty))
        assertEquals(SeekStepSeconds.Twenty, SeekStepSeconds.fromSecondsOrDefault(20, SeekStepSeconds.Ten))
        assertEquals(SeekStepSeconds.Thirty, SeekStepSeconds.fromSecondsOrDefault(30, SeekStepSeconds.Ten))
    }

    @Test
    fun `backward seek clamps below zero`() {
        val config = PlaybackSeekStepConfig(
            backward = SeekStepSeconds.Thirty,
            forward = SeekStepSeconds.Twenty
        )

        assertEquals(0L, PlaybackSeekStepPolicy.backwardTarget(5_000L, config))
    }

    @Test
    fun `forward seek clamps above duration`() {
        val config = PlaybackSeekStepConfig(
            backward = SeekStepSeconds.Ten,
            forward = SeekStepSeconds.Thirty
        )

        assertEquals(25_000L, PlaybackSeekStepPolicy.forwardTarget(10_000L, 25_000L, config))
    }
}
