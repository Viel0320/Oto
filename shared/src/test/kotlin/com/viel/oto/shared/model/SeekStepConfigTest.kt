package com.viel.oto.shared.model

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Boundary coverage for the persisted seek-step value parsing that is not already exercised
 * by [com.viel.oto.shared.policy.PlaybackSeekStepPolicyTest].
 *
 * Focus: [SeekStepSeconds.fromSecondsOrDefault] fallback for null/unknown values, the
 * [SeekStepSeconds.toMillis] conversion for every supported step, and the per-direction
 * assembly performed by [PlaybackSeekStepConfig.fromStored].
 */
class SeekStepConfigTest {

    @Test
    fun `null stored seconds fall back to provided default`() {
        assertEquals(SeekStepSeconds.Ten, SeekStepSeconds.fromSecondsOrDefault(null, SeekStepSeconds.Ten))
        assertEquals(SeekStepSeconds.Thirty, SeekStepSeconds.fromSecondsOrDefault(null, SeekStepSeconds.Thirty))
    }

    @Test
    fun `unknown stored seconds fall back to provided default`() {
        assertEquals(SeekStepSeconds.Twenty, SeekStepSeconds.fromSecondsOrDefault(0, SeekStepSeconds.Twenty))
        assertEquals(SeekStepSeconds.Twenty, SeekStepSeconds.fromSecondsOrDefault(-10, SeekStepSeconds.Twenty))
        assertEquals(SeekStepSeconds.Ten, SeekStepSeconds.fromSecondsOrDefault(11, SeekStepSeconds.Ten))
        assertEquals(SeekStepSeconds.Thirty, SeekStepSeconds.fromSecondsOrDefault(45, SeekStepSeconds.Thirty))
    }

    @Test
    fun `valid stored seconds resolve to the matching entry`() {
        assertEquals(SeekStepSeconds.Ten, SeekStepSeconds.fromSecondsOrDefault(10, SeekStepSeconds.Thirty))
        assertEquals(SeekStepSeconds.Twenty, SeekStepSeconds.fromSecondsOrDefault(20, SeekStepSeconds.Thirty))
        assertEquals(SeekStepSeconds.Thirty, SeekStepSeconds.fromSecondsOrDefault(30, SeekStepSeconds.Ten))
    }

    @Test
    fun `supported list contains every entry`() {
        assertEquals(
            listOf(SeekStepSeconds.Ten, SeekStepSeconds.Twenty, SeekStepSeconds.Thirty),
            SeekStepSeconds.supported
        )
    }

    @Test
    fun `toMillis converts seconds to milliseconds`() {
        assertEquals(10_000L, SeekStepSeconds.Ten.toMillis())
        assertEquals(20_000L, SeekStepSeconds.Twenty.toMillis())
        assertEquals(30_000L, SeekStepSeconds.Thirty.toMillis())
    }

    @Test
    fun `fromStored assembles both directions from valid values`() {
        val config = PlaybackSeekStepConfig.fromStored(backwardSeconds = 30, forwardSeconds = 10)

        assertEquals(SeekStepSeconds.Thirty, config.backward)
        assertEquals(SeekStepSeconds.Ten, config.forward)
    }

    @Test
    fun `fromStored falls back per direction to its own default`() {
        val config = PlaybackSeekStepConfig.fromStored(backwardSeconds = null, forwardSeconds = 999)

        assertEquals(SeekStepSeconds.Ten, config.backward)
        assertEquals(SeekStepSeconds.Twenty, config.forward)
    }

    @Test
    fun `default config matches direction defaults`() {
        val config = PlaybackSeekStepConfig()

        assertEquals(SeekStepSeconds.Ten, config.backward)
        assertEquals(SeekStepSeconds.Twenty, config.forward)
    }
}
